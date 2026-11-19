using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.Data.Sqlite;

var builder = WebApplication.CreateBuilder(args);

var databasePath = builder.Configuration["RepoNotes:DatabasePath"]
    ?? Environment.GetEnvironmentVariable("REPONOTES_DATABASE")
    ?? "/var/lib/xreponotes/notes.db";
var apiKey = builder.Configuration["RepoNotes:ApiKey"]
    ?? Environment.GetEnvironmentVariable("REPONOTES_API_KEY");

if (string.IsNullOrWhiteSpace(apiKey))
    throw new InvalidOperationException("Set REPONOTES_API_KEY before starting the RepoNotes server.");

Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(databasePath))!);
var connectionString = new SqliteConnectionStringBuilder
{
    DataSource = databasePath,
    Mode = SqliteOpenMode.ReadWriteCreate,
    Pooling = true,
    DefaultTimeout = 10
}.ToString();

await InitializeDatabase(connectionString);

var app = builder.Build();
var verifyHttp = new HttpClient { Timeout = TimeSpan.FromSeconds(2) };
var verifyEndpoint = builder.Configuration["RepoNotes:VerifyUrl"]
    ?? Environment.GetEnvironmentVariable("REPONOTES_VERIFY_URL");

// /healthz deliberately reveals no database path, key state, or application data.
app.MapGet("/healthz", () => Results.Ok(new { status = "ok" }));

app.Use(async (context, next) =>
{
    if (context.Request.Path == "/healthz")
    {
        await next();
        return;
    }

    string? suppliedKey = null;
    if (context.Request.Headers.TryGetValue("X-RepoNotes-Key", out var headerKey))
        suppliedKey = headerKey.ToString().Trim();
    else if (context.Request.Headers.TryGetValue("X-API-Key", out var xApiKey))
        suppliedKey = xApiKey.ToString().Trim();
    else if (context.Request.Headers.TryGetValue("Authorization", out var authHeader) &&
             authHeader.ToString().StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase))
        suppliedKey = authHeader.ToString()[7..].Trim();

    if (string.IsNullOrEmpty(suppliedKey))
    {
        context.Response.StatusCode = StatusCodes.Status401Unauthorized;
        return;
    }

    var expectedBytes = Encoding.UTF8.GetBytes(apiKey);
    var suppliedBytes = Encoding.UTF8.GetBytes(suppliedKey);
    var authorized = expectedBytes.Length == suppliedBytes.Length &&
                     CryptographicOperations.FixedTimeEquals(expectedBytes, suppliedBytes);

    if (!authorized && !string.IsNullOrWhiteSpace(verifyEndpoint) && suppliedKey.StartsWith("xsk_", StringComparison.Ordinal))
    {
        try
        {
            var verifyJson = JsonSerializer.Serialize(new { key = suppliedKey, scope = "notes" });
            using var verifyContent = new StringContent(verifyJson, Encoding.UTF8, "application/json");
            using var resp = await verifyHttp.PostAsync(
                verifyEndpoint,
                verifyContent,
                context.RequestAborted);
            if (resp.IsSuccessStatusCode)
            {
                using var doc = await JsonDocument.ParseAsync(
                    await resp.Content.ReadAsStreamAsync(context.RequestAborted),
                    cancellationToken: context.RequestAborted);
                if (doc.RootElement.TryGetProperty("valid", out var validProp) && validProp.GetBoolean())
                    authorized = true;
            }
        }
        catch
        {
            // Fall through to 401 if key verification service is unreachable.
        }
    }

    if (!authorized)
    {
        context.Response.StatusCode = StatusCodes.Status401Unauthorized;
        return;
    }

    context.Response.Headers["Cache-Control"] = "no-store";
    context.Response.Headers["X-Content-Type-Options"] = "nosniff";
    await next();
});

app.MapGet("/api/notes/deletions", () => Results.Ok(Array.Empty<DeletedNoteDto>()));

app.MapGet("/api/notes", async (CancellationToken ct) =>
{
    var notes = new List<NoteDto>();
    await using var db = new SqliteConnection(connectionString);
    await db.OpenAsync(ct);
    await using var cmd = db.CreateCommand();
    cmd.CommandText = """
        SELECT id, repo_path, title, body, created_utc, updated_utc
        FROM notes
        ORDER BY updated_utc DESC, id DESC
        """;
    await using var reader = await cmd.ExecuteReaderAsync(ct);
    while (await reader.ReadAsync(ct))
    {
        notes.Add(new NoteDto(
            reader.GetInt64(0),
            reader.IsDBNull(1) ? "" : reader.GetString(1),
            reader.GetString(2),
            reader.GetString(3),
            DateTimeOffset.Parse(reader.GetString(4)),
            DateTimeOffset.Parse(reader.GetString(5))));
    }
    return Results.Ok(notes);
});


app.MapPost("/api/notes/import", async (ImportNotesRequest request, CancellationToken ct) =>
{
    if (request.Notes is null || request.Notes.Count == 0)
        return Results.BadRequest(new { error = "The selected database contains no notes to import." });
    if (request.Notes.Count > 50000)
        return Results.BadRequest(new { error = "The selected database contains too many notes for one import." });
    if (request.Notes.Any(n => n.Id is null or <= 0) ||
        request.Notes.Select(n => n.Id).Distinct().Count() != request.Notes.Count)
        return Results.BadRequest(new { error = "The backup contains invalid or duplicate note IDs." });

    await using var db = new SqliteConnection(connectionString);
    await db.OpenAsync(ct);
    await using var tx = await db.BeginTransactionAsync(ct);
    foreach (var note in request.Notes)
    {
        var created = note.CreatedUtc?.ToUniversalTime() ?? DateTimeOffset.UtcNow;
        var updated = note.UpdatedUtc?.ToUniversalTime() ?? created;
        await using var cmd = db.CreateCommand();
        cmd.Transaction = (SqliteTransaction)tx;
        cmd.CommandText = """
            INSERT INTO notes(id, repo_path, title, body, created_utc, updated_utc)
            VALUES($id, $repo, $title, $body, $created, $updated)
            ON CONFLICT(id) DO UPDATE SET
                repo_path=excluded.repo_path,
                title=excluded.title,
                body=excluded.body,
                created_utc=excluded.created_utc,
                updated_utc=excluded.updated_utc;
            """;
        cmd.Parameters.AddWithValue("$id", note.Id!.Value);
        cmd.Parameters.AddWithValue("$repo", string.IsNullOrWhiteSpace(note.RepoPath) ? DBNull.Value : note.RepoPath);
        cmd.Parameters.AddWithValue("$title", note.Title ?? "");
        cmd.Parameters.AddWithValue("$body", note.Body ?? "");
        cmd.Parameters.AddWithValue("$created", created.ToString("O"));
        cmd.Parameters.AddWithValue("$updated", updated.ToString("O"));
        await cmd.ExecuteNonQueryAsync(ct);
    }
    await tx.CommitAsync(ct);
    return Results.Ok(new { imported = request.Notes.Count });
});

app.MapGet("/api/notes/{id:long}", async (long id, CancellationToken ct) =>
{
    await using var db = new SqliteConnection(connectionString);
    await db.OpenAsync(ct);
    await using var cmd = db.CreateCommand();
    cmd.CommandText = "SELECT id, repo_path, title, body, created_utc, updated_utc FROM notes WHERE id=$id";
    cmd.Parameters.AddWithValue("$id", id);
    await using var reader = await cmd.ExecuteReaderAsync(ct);
    if (!await reader.ReadAsync(ct)) return (IResult)Results.NotFound();
    return (IResult)Results.Ok(new NoteDto(
        reader.GetInt64(0),
        reader.IsDBNull(1) ? "" : reader.GetString(1),
        reader.GetString(2),
        reader.GetString(3),
        DateTimeOffset.Parse(reader.GetString(4)),
        DateTimeOffset.Parse(reader.GetString(5))));
});

app.MapPost("/api/notes", async (SaveNoteRequest request, CancellationToken ct) =>
{
    var trimmedTitle = (request.Title ?? "").Trim();
    if ((string.Equals(trimmedTitle, "New page", StringComparison.OrdinalIgnoreCase) ||
         string.Equals(trimmedTitle, "New note", StringComparison.OrdinalIgnoreCase)) &&
        string.IsNullOrWhiteSpace(request.Body))
    {
        return (IResult)Results.BadRequest(new { error = "Empty placeholder notes cannot be saved." });
    }

    var now = DateTimeOffset.UtcNow;
    var created = request.CreatedUtc?.ToUniversalTime() ?? now;
    var updated = request.UpdatedUtc?.ToUniversalTime() ?? now;
    await using var db = new SqliteConnection(connectionString);
    await db.OpenAsync(ct);
    await using var cmd = db.CreateCommand();
    cmd.CommandText = """
        INSERT INTO notes(id, repo_path, title, body, created_utc, updated_utc)
        VALUES($id, $repo, $title, $body, $created, $updated);
        SELECT last_insert_rowid();
        """;
    cmd.Parameters.AddWithValue("$id", request.Id is > 0 ? (object)request.Id.Value : DBNull.Value);
    cmd.Parameters.AddWithValue("$repo", string.IsNullOrWhiteSpace(request.RepoPath) ? DBNull.Value : request.RepoPath);
    cmd.Parameters.AddWithValue("$title", request.Title ?? "");
    cmd.Parameters.AddWithValue("$body", request.Body ?? "");
    cmd.Parameters.AddWithValue("$created", created.ToString("O"));
    cmd.Parameters.AddWithValue("$updated", updated.ToString("O"));
    var id = (long)(await cmd.ExecuteScalarAsync(ct))!;
    return Results.Created($"/api/notes/{id}", new NoteDto(
        id, request.RepoPath ?? "", request.Title ?? "", request.Body ?? "", created, updated));
});

app.MapPut("/api/notes/{id:long}", async (long id, SaveNoteRequest request, CancellationToken ct) =>
{
    if (request.ExpectedUpdatedUtc is null)
        return (IResult)Results.BadRequest(new { error = "expectedUpdatedUtc is required to prevent overwriting a newer edit." });

    var updated = DateTimeOffset.UtcNow;
    await using var db = new SqliteConnection(connectionString);
    await db.OpenAsync(ct);
    await using var cmd = db.CreateCommand();
    cmd.CommandText = """
        UPDATE notes
        SET repo_path=$repo, title=$title, body=$body, updated_utc=$updated
        WHERE id=$id AND julianday(updated_utc)=julianday($expected);
        """;
    cmd.Parameters.AddWithValue("$repo", string.IsNullOrWhiteSpace(request.RepoPath) ? DBNull.Value : request.RepoPath);
    cmd.Parameters.AddWithValue("$title", request.Title ?? "");
    cmd.Parameters.AddWithValue("$body", request.Body ?? "");
    cmd.Parameters.AddWithValue("$updated", updated.ToString("O"));
    cmd.Parameters.AddWithValue("$id", id);
    cmd.Parameters.AddWithValue("$expected", request.ExpectedUpdatedUtc.Value.ToUniversalTime().ToString("O"));
    var changed = await cmd.ExecuteNonQueryAsync(ct);
    if (changed == 0)
    {
        await using var exists = db.CreateCommand();
        exists.CommandText = "SELECT 1 FROM notes WHERE id=$id";
        exists.Parameters.AddWithValue("$id", id);
        return await exists.ExecuteScalarAsync(ct) is null
            ? (IResult)Results.NotFound()
            : (IResult)Results.Conflict(new { error = "This note was changed by another client. Reload it before saving." });
    }

    await using var createdCmd = db.CreateCommand();
    createdCmd.CommandText = "SELECT created_utc FROM notes WHERE id=$id";
    createdCmd.Parameters.AddWithValue("$id", id);
    var createdUtc = DateTimeOffset.Parse((string)(await createdCmd.ExecuteScalarAsync(ct))!);
    return (IResult)Results.Ok(new NoteDto(
        id, request.RepoPath ?? "", request.Title ?? "", request.Body ?? "", createdUtc, updated));
});

app.MapDelete("/api/notes/{id:long}", async (long id, DateTimeOffset expectedUpdatedUtc, CancellationToken ct) =>
{
    await using var db = new SqliteConnection(connectionString);
    await db.OpenAsync(ct);
    await using var tx = await db.BeginTransactionAsync(ct);
    await using var cmd = db.CreateCommand();
    cmd.Transaction = (SqliteTransaction)tx;
    cmd.CommandText = "DELETE FROM notes WHERE id=$id AND julianday(updated_utc)=julianday($expected)";
    cmd.Parameters.AddWithValue("$id", id);
    cmd.Parameters.AddWithValue("$expected", expectedUpdatedUtc.ToUniversalTime().ToString("O"));
    var deleted = await cmd.ExecuteNonQueryAsync(ct);
    if (deleted == 1)
    {
        await tx.CommitAsync(ct);
        return (IResult)Results.NoContent();
    }
    await tx.RollbackAsync(ct);
    await using var exists = db.CreateCommand();
    exists.CommandText = "SELECT 1 FROM notes WHERE id=$id";
    exists.Parameters.AddWithValue("$id", id);
    return await exists.ExecuteScalarAsync(ct) is null
        ? (IResult)Results.NotFound()
        : (IResult)Results.Conflict(new { error = "This note changed after it was loaded. Reload before deleting." });
});

app.MapGet("/api/repositories", async (CancellationToken ct) =>
{
    var repositories = new List<RepositoryDto>();
    await using var db = new SqliteConnection(connectionString);
    await db.OpenAsync(ct);
    await using var cmd = db.CreateCommand();
    cmd.CommandText = "SELECT path, name FROM repositories ORDER BY name COLLATE NOCASE, path COLLATE NOCASE;";
    await using var reader = await cmd.ExecuteReaderAsync(ct);
    while (await reader.ReadAsync(ct))
        repositories.Add(new RepositoryDto(reader.GetString(0), reader.GetString(1)));
    return Results.Ok(repositories);
});

app.MapPut("/api/repositories", async (RepositoryCatalogRequest request, CancellationToken ct) =>
{
    if (request.Repositories is null || request.Repositories.Count > 10000)
        return Results.BadRequest(new { error = "Invalid repository catalog." });

    var normalized = request.Repositories
        .Where(r => !string.IsNullOrWhiteSpace(r.Path))
        .Select(r => new RepositoryDto(r.Path.Trim(), string.IsNullOrWhiteSpace(r.Name)
            ? Path.GetFileName(r.Path.Trim().TrimEnd('/', '\\'))
            : r.Name.Trim()))
        .GroupBy(r => r.Path, StringComparer.OrdinalIgnoreCase)
        .Select(g => g.First())
        .ToList();

    await using var db = new SqliteConnection(connectionString);
    await db.OpenAsync(ct);
    await using var tx = await db.BeginTransactionAsync(ct);
    await using (var clear = db.CreateCommand())
    {
        clear.Transaction = (SqliteTransaction)tx;
        clear.CommandText = "DELETE FROM repositories;";
        await clear.ExecuteNonQueryAsync(ct);
    }
    foreach (var repo in normalized)
    {
        await using var cmd = db.CreateCommand();
        cmd.Transaction = (SqliteTransaction)tx;
        cmd.CommandText = "INSERT INTO repositories(path, name, updated_utc) VALUES($path, $name, $updated);";
        cmd.Parameters.AddWithValue("$path", repo.Path);
        cmd.Parameters.AddWithValue("$name", repo.Name);
        cmd.Parameters.AddWithValue("$updated", DateTimeOffset.UtcNow.ToString("O"));
        await cmd.ExecuteNonQueryAsync(ct);
    }
    await tx.CommitAsync(ct);
    return Results.Ok(new { count = normalized.Count });
});

app.MapPost("/api/images", async (UploadImageRequest request, CancellationToken ct) =>
{
    if (string.IsNullOrWhiteSpace(request.DataBase64))
        return Results.BadRequest(new { error = "Image data is required." });

    byte[] data;
    try
    {
        data = Convert.FromBase64String(request.DataBase64);
    }
    catch (FormatException)
    {
        return Results.BadRequest(new { error = "Invalid base64 image data." });
    }

    if (data.Length == 0 || data.Length > 15 * 1024 * 1024)
        return Results.BadRequest(new { error = "Image must be between 1 byte and 15 MB." });

    var mimeType = string.IsNullOrWhiteSpace(request.MimeType) ? "image/png" : request.MimeType.Trim();
    if (!mimeType.StartsWith("image/", StringComparison.OrdinalIgnoreCase))
        return Results.BadRequest(new { error = "Only image MIME types are allowed." });

    var id = string.IsNullOrWhiteSpace(request.Id) ? Guid.NewGuid().ToString("N") : request.Id.Trim();
    await using var db = new SqliteConnection(connectionString);
    await db.OpenAsync(ct);
    await using var cmd = db.CreateCommand();
    cmd.CommandText = """
        INSERT INTO images(id, mime_type, data, created_utc)
        VALUES($id, $mime, $data, $created)
        ON CONFLICT(id) DO NOTHING;
        """;
    cmd.Parameters.AddWithValue("$id", id);
    cmd.Parameters.AddWithValue("$mime", mimeType);
    cmd.Parameters.AddWithValue("$data", data);
    cmd.Parameters.AddWithValue("$created", DateTimeOffset.UtcNow.ToString("O"));
    await cmd.ExecuteNonQueryAsync(ct);
    return Results.Ok(new { id, mimeType });
});

app.MapGet("/api/images/{id}", async (string id, CancellationToken ct) =>
{
    await using var db = new SqliteConnection(connectionString);
    await db.OpenAsync(ct);
    await using var cmd = db.CreateCommand();
    cmd.CommandText = "SELECT mime_type, data FROM images WHERE id=$id";
    cmd.Parameters.AddWithValue("$id", id);
    await using var reader = await cmd.ExecuteReaderAsync(ct);
    if (!await reader.ReadAsync(ct)) return Results.NotFound();
    var mimeType = reader.GetString(0);
    var data = (byte[])reader[1];
    return Results.Bytes(data, mimeType);
});

app.Run();

static async Task InitializeDatabase(string connectionString)
{
    await using var db = new SqliteConnection(connectionString);
    await db.OpenAsync();
    await using var cmd = db.CreateCommand();
    cmd.CommandText = """
        PRAGMA journal_mode=WAL;
        PRAGMA synchronous=NORMAL;
        PRAGMA foreign_keys=ON;
        CREATE TABLE IF NOT EXISTS notes(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            repo_path TEXT NULL,
            title TEXT NOT NULL DEFAULT '',
            body TEXT NOT NULL DEFAULT '',
            created_utc TEXT NOT NULL,
            updated_utc TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS ix_notes_repo ON notes(repo_path);
        CREATE INDEX IF NOT EXISTS ix_notes_updated ON notes(updated_utc);
        DROP TABLE IF EXISTS deleted_notes;
        CREATE TABLE IF NOT EXISTS repositories(
            path TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            updated_utc TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS images(
            id TEXT PRIMARY KEY,
            mime_type TEXT NOT NULL,
            data BLOB NOT NULL,
            created_utc TEXT NOT NULL
        );
        """;
    await cmd.ExecuteNonQueryAsync();
}

record ImportNotesRequest(List<SaveNoteRequest> Notes);
record DeletedNoteDto(long Id, DateTimeOffset DeletedUtc);

record NoteDto(
    long Id,
    string RepoPath,
    string Title,
    string Body,
    DateTimeOffset CreatedUtc,
    DateTimeOffset UpdatedUtc);

record SaveNoteRequest(
    string? RepoPath,
    string? Title,
    string? Body,
    DateTimeOffset? CreatedUtc,
    DateTimeOffset? UpdatedUtc,
    DateTimeOffset? ExpectedUpdatedUtc,
    long? Id);

record RepositoryCatalogRequest(List<RepositoryDto> Repositories);
record RepositoryDto(string Path, string Name);
record UploadImageRequest(string? Id, string? MimeType, string? DataBase64);
