using System.Security.Cryptography;
using System.Text;
using Microsoft.Data.Sqlite;
using RepoNotes.Models;

namespace RepoNotes.Services;

/// <summary>Local SQLite cache and optional central xServer notes API client.</summary>
public sealed class Database
{
    private readonly SqliteConnection _c;
    private readonly object _lock = new();
    private readonly bool _remoteEnabled;
    private readonly NoteScope _scope;

    public const string WorkRepoPath = "Work Notebook";
    public const string VaultPersonalRepoPath = "Vault - Personal";
    public const string VaultWorkRepoPath = "Vault - Work";
    public const string VaultPersonalPrefix = VaultPersonalRepoPath + "::";
    public const string VaultWorkPrefix = VaultWorkRepoPath + "::";

    public static bool IsWorkRepoPath(string? repoPath) =>
        string.Equals(repoPath, WorkRepoPath, StringComparison.OrdinalIgnoreCase) ||
        string.Equals(repoPath, "work", StringComparison.OrdinalIgnoreCase);

    public static bool IsVaultPersonalRepoPath(string? repoPath) =>
        string.Equals(repoPath, VaultPersonalRepoPath, StringComparison.OrdinalIgnoreCase) ||
        (repoPath?.StartsWith(VaultPersonalPrefix, StringComparison.OrdinalIgnoreCase) ?? false);

    public static bool IsVaultWorkRepoPath(string? repoPath) =>
        string.Equals(repoPath, VaultWorkRepoPath, StringComparison.OrdinalIgnoreCase) ||
        (repoPath?.StartsWith(VaultWorkPrefix, StringComparison.OrdinalIgnoreCase) ?? false);

    public static bool IsSpecialScopeRepoPath(string? repoPath) =>
        IsWorkRepoPath(repoPath) || IsVaultPersonalRepoPath(repoPath) || IsVaultWorkRepoPath(repoPath);

    public static string UnwrapRepoPath(string? repoPath)
    {
        if (string.IsNullOrEmpty(repoPath)) return "";
        if (repoPath.StartsWith(VaultPersonalPrefix, StringComparison.OrdinalIgnoreCase))
            return repoPath[VaultPersonalPrefix.Length..];
        if (string.Equals(repoPath, VaultPersonalRepoPath, StringComparison.OrdinalIgnoreCase))
            return "";
        if (repoPath.StartsWith(VaultWorkPrefix, StringComparison.OrdinalIgnoreCase))
            return repoPath[VaultWorkPrefix.Length..];
        if (string.Equals(repoPath, VaultWorkRepoPath, StringComparison.OrdinalIgnoreCase))
            return WorkRepoPath;
        return repoPath;
    }

    public static string EncodeVaultRepoPath(NoteScope vaultScope, string? originalRepoPath)
    {
        var unwrapped = UnwrapRepoPath(originalRepoPath);
        return vaultScope switch
        {
            NoteScope.VaultPersonal => string.IsNullOrWhiteSpace(unwrapped) || IsSpecialScopeRepoPath(unwrapped)
                ? VaultPersonalRepoPath
                : VaultPersonalPrefix + unwrapped,
            NoteScope.VaultWork => string.IsNullOrWhiteSpace(unwrapped) || IsWorkRepoPath(unwrapped) || IsSpecialScopeRepoPath(unwrapped)
                ? VaultWorkRepoPath
                : VaultWorkPrefix + unwrapped,
            _ => DefaultRepoPathFor(vaultScope)
        };
    }

    public static string RestoreRepoPathFromVault(NoteScope targetScope, string? vaultRepoPath)
    {
        var unwrapped = UnwrapRepoPath(vaultRepoPath);
        return targetScope switch
        {
            NoteScope.Work => string.IsNullOrWhiteSpace(unwrapped) ? WorkRepoPath : unwrapped,
            NoteScope.Personal => IsSpecialScopeRepoPath(unwrapped) ? "" : unwrapped,
            _ => DefaultRepoPathFor(targetScope)
        };
    }

    public static string DefaultRepoPathFor(NoteScope scope) => scope switch
    {
        NoteScope.Work => WorkRepoPath,
        NoteScope.VaultPersonal => VaultPersonalRepoPath,
        NoteScope.VaultWork => VaultWorkRepoPath,
        _ => ""
    };

    public bool MatchesScope(string? repoPath) => _scope switch
    {
        NoteScope.Work => IsWorkRepoPath(repoPath),
        NoteScope.VaultPersonal => IsVaultPersonalRepoPath(repoPath),
        NoteScope.VaultWork => IsVaultWorkRepoPath(repoPath),
        _ => !IsSpecialScopeRepoPath(repoPath)
    };

    private void EnsureScopeRepoPath(NoteVM n)
    {
        switch (_scope)
        {
            case NoteScope.Work:
                if (string.IsNullOrEmpty(n.RepoPath)) n.SetRepoPathSilent(WorkRepoPath);
                break;
            case NoteScope.VaultPersonal:
                if (!IsVaultPersonalRepoPath(n.RepoPath))
                    n.SetRepoPathSilent(EncodeVaultRepoPath(NoteScope.VaultPersonal, n.RepoPath));
                break;
            case NoteScope.VaultWork:
                if (!IsVaultWorkRepoPath(n.RepoPath))
                    n.SetRepoPathSilent(EncodeVaultRepoPath(NoteScope.VaultWork, n.RepoPath));
                break;
        }
    }

    public static string DataDir { get; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "RepoNotes");

    public string LastRemoteError { get; private set; } = "";
    public bool IsRemoteConfigured => _remoteEnabled && GetSetting("notes_api_url").Trim().Length > 0;

    public Database(bool remoteEnabled = true, string databaseFileName = "notes.db", bool isWork = false, NoteScope? scope = null)
    {
        _remoteEnabled = remoteEnabled;
        _scope = scope ?? (isWork ? NoteScope.Work : NoteScope.Personal);
        Directory.CreateDirectory(DataDir);
        _c = new SqliteConnection($"Data Source={Path.Combine(DataDir, databaseFileName)};Pooling=False");
        _c.Open();
        Exec("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA foreign_keys=ON;");
        Exec("""
            CREATE TABLE IF NOT EXISTS notes(
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                repo_path   TEXT NULL,
                title       TEXT NOT NULL DEFAULT '',
                body        TEXT NOT NULL DEFAULT '',
                created_utc TEXT NOT NULL,
                updated_utc TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS ix_notes_repo ON notes(repo_path);
            CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS project_fingerprints(
                repo_path   TEXT PRIMARY KEY,
                fingerprint TEXT NOT NULL,
                updated_utc TEXT NOT NULL
            );
            """);
    }

    private void Exec(string sql)
    {
        lock (_lock)
        {
            using var cmd = _c.CreateCommand();
            cmd.CommandText = sql;
            cmd.ExecuteNonQuery();
        }
    }

    public List<NoteVM> LoadNotes()
    {
        var cached = LoadLocalNotes();
        try
        {
            using var remote = CreateRemoteClient();
            if (remote == null)
            {
                LastRemoteError = "";
                return cached;
            }

            var allServerNotes = remote.GetNotes();
            var deletedIds = remote.GetDeletedNoteIds();
            cached = cached.Where(n => !deletedIds.Contains(n.Id)).ToList();

            // Filter server notes by scope (Personal, Work, VaultPersonal, VaultWork)
            var serverNotes = allServerNotes.Where(n => MatchesScope(n.RepoPath)).ToList();

            var configuredUrl = GetSetting("notes_api_url").Trim();
            var firstConnection = !string.Equals(
                GetSetting("notes_api_initialized_for"), configuredUrl, StringComparison.OrdinalIgnoreCase);

            if (firstConnection && serverNotes.Count > 0 && cached.Count > 0)
            {
                var localById = cached.ToDictionary(n => n.Id);
                bool hasConflictingIds = serverNotes.Any(server =>
                    localById.TryGetValue(server.Id, out var local) &&
                    (local.Title != server.Title ||
                     local.Body != server.Body ||
                     local.RepoPath != (server.RepoPath ?? "")));

                if (hasConflictingIds)
                    throw new InvalidOperationException(
                        "This PC and xServer contain different notes with overlapping IDs. No data was changed. " +
                        "Back up the local database and resolve the ID conflicts before connecting.");
            }

            if (firstConnection && cached.Count > 0)
            {
                var serverIds = serverNotes.Select(n => n.Id).ToHashSet();
                foreach (var note in cached.Where(n => !serverIds.Contains(n.Id)))
                {
                    EnsureScopeRepoPath(note);
                    remote.Create(ToWrite(note, preserveId: true));
                }
                serverNotes = remote.GetNotes().Where(n => MatchesScope(n.RepoPath)).ToList();
            }
            else if (!firstConnection)
            {
                var byId = serverNotes.ToDictionary(n => n.Id);
                foreach (var local in cached)
                {
                    if (!byId.TryGetValue(local.Id, out var serverNote))
                        continue;

                    EnsureScopeRepoPath(local);

                    bool contentDiffers = local.Title != serverNote.Title ||
                                          local.Body != serverNote.Body ||
                                          local.RepoPath != serverNote.RepoPath;
                    if (contentDiffers && local.UpdatedUtc > serverNote.UpdatedUtc.UtcDateTime)
                        remote.Update(local.Id, ToWrite(local, serverNote.UpdatedUtc));
                }
                serverNotes = remote.GetNotes().Where(n => MatchesScope(n.RepoPath)).ToList();
            }

            var result = serverNotes
                .Select(n => NoteVM.FromDb(
                    n.Id, n.RepoPath ?? "", n.Title ?? "", n.Body ?? "",
                    n.CreatedUtc.UtcDateTime, n.UpdatedUtc.UtcDateTime))
                .ToList();

            ReplaceLocalNotes(result);
            SetSetting("notes_api_initialized_for", configuredUrl);
            LastRemoteError = "";
            return result;
        }
        catch (Exception ex)
        {
            LastRemoteError = ex.Message;
            return cached;
        }
    }

    public long Insert(NoteVM n)
    {
        EnsureScopeRepoPath(n);

        using var remote = CreateRemoteClient();
        if (remote != null)
        {
            var created = remote.Create(ToWrite(n));
            n.Id = created.Id;
            n.CreatedUtc = created.CreatedUtc.UtcDateTime;
            n.MarkServerSaved(created.UpdatedUtc.UtcDateTime);
            InsertLocalWithId(n);
            LastRemoteError = "";
            return n.Id;
        }

        lock (_lock)
        {
            using var cmd = _c.CreateCommand();
            cmd.CommandText = """
                INSERT INTO notes(repo_path, title, body, created_utc, updated_utc)
                VALUES($r, $t, $b, $c, $u); SELECT last_insert_rowid();
                """;
            Bind(cmd, n);
            return (long)cmd.ExecuteScalar()!;
        }
    }

    public void Update(NoteVM n)
    {
        EnsureScopeRepoPath(n);

        try
        {
            using var remote = CreateRemoteClient();
            if (remote != null)
            {
                var saved = remote.Update(n.Id, ToWrite(n, new DateTimeOffset(DateTime.SpecifyKind(n.ServerUpdatedUtc, DateTimeKind.Utc))));
                n.MarkServerSaved(saved.UpdatedUtc.UtcDateTime);
            }

            UpdateLocalCache(n);
            LastRemoteError = "";
        }
        catch (NoteConflictException)
        {
            throw;
        }
        catch (Exception ex)
        {
            try { UpdateLocalCache(n); } catch { }
            LastRemoteError = ex.Message;
            throw;
        }
    }

    private void UpdateLocalCache(NoteVM n)
    {
        lock (_lock)
        {
            using var cmd = _c.CreateCommand();
            cmd.CommandText = "UPDATE notes SET repo_path=$r, title=$t, body=$b, created_utc=$c, updated_utc=$u WHERE id=$id";
            Bind(cmd, n);
            cmd.Parameters.AddWithValue("$id", n.Id);
            cmd.ExecuteNonQuery();
        }
    }

    public void UpsertLocalCache(NoteVM n)
    {
        lock (_lock)
        {
            using var cmd = _c.CreateCommand();
            cmd.CommandText = """
                INSERT INTO notes(id, repo_path, title, body, created_utc, updated_utc)
                VALUES($id, $r, $t, $b, $c, $u)
                ON CONFLICT(id) DO UPDATE SET repo_path=$r, title=$t, body=$b, created_utc=$c, updated_utc=$u;
                """;
            cmd.Parameters.AddWithValue("$id", n.Id);
            Bind(cmd, n);
            cmd.ExecuteNonQuery();
        }
    }

    public long InsertLocalOnly(NoteVM n)
    {
        EnsureScopeRepoPath(n);
        lock (_lock)
        {
            using var cmd = _c.CreateCommand();
            cmd.CommandText = """
                INSERT INTO notes(repo_path, title, body, created_utc, updated_utc)
                VALUES($r, $t, $b, $c, $u); SELECT last_insert_rowid();
                """;
            Bind(cmd, n);
            return (long)cmd.ExecuteScalar()!;
        }
    }

    public void DeleteLocalOnly(long id)
    {
        lock (_lock)
        {
            using var cmd = _c.CreateCommand();
            cmd.CommandText = "DELETE FROM notes WHERE id=$id";
            cmd.Parameters.AddWithValue("$id", id);
            cmd.ExecuteNonQuery();
        }
    }

    public void Delete(long id, DateTime? expectedUpdatedUtc = null)
    {
        using var remote = CreateRemoteClient();
        if (remote != null)
        {
            if (expectedUpdatedUtc is null)
                throw new InvalidOperationException("The server version of this note is unknown. Refresh notes before deleting.");
            remote.Delete(id, expectedUpdatedUtc.Value);
            LastRemoteError = "";
        }

        lock (_lock)
        {
            using var cmd = _c.CreateCommand();
            cmd.CommandText = "DELETE FROM notes WHERE id=$id";
            cmd.Parameters.AddWithValue("$id", id);
            cmd.ExecuteNonQuery();
        }
    }

    private List<NoteVM> LoadLocalNotes()
    {
        var list = new List<NoteVM>();
        lock (_lock)
        {
            using var cmd = _c.CreateCommand();
            cmd.CommandText = "SELECT id, repo_path, title, body, created_utc, updated_utc FROM notes ORDER BY created_utc DESC";
            using var r = cmd.ExecuteReader();
            while (r.Read())
            {
                list.Add(NoteVM.FromDb(
                    r.GetInt64(0),
                    r.IsDBNull(1) ? "" : r.GetString(1),
                    r.GetString(2), r.GetString(3),
                    ParseUtc(r.GetString(4)), ParseUtc(r.GetString(5))));
            }
        }
        return list;
    }

    private void ReplaceLocalNotes(List<NoteVM> notes)
    {
        lock (_lock)
        {
            using var tx = _c.BeginTransaction();
            using (var clear = _c.CreateCommand())
            {
                clear.Transaction = tx;
                clear.CommandText = "DELETE FROM notes";
                clear.ExecuteNonQuery();
            }

            foreach (var note in notes)
            {
                using var cmd = _c.CreateCommand();
                cmd.Transaction = tx;
                cmd.CommandText = """
                    INSERT INTO notes(id, repo_path, title, body, created_utc, updated_utc)
                    VALUES($id, $r, $t, $b, $c, $u);
                    """;
                cmd.Parameters.AddWithValue("$id", note.Id);
                Bind(cmd, note);
                cmd.ExecuteNonQuery();
            }
            tx.Commit();
        }
    }

    private void InsertLocalWithId(NoteVM n)
    {
        lock (_lock)
        {
            using var cmd = _c.CreateCommand();
            cmd.CommandText = """
                INSERT INTO notes(id, repo_path, title, body, created_utc, updated_utc)
                VALUES($id, $r, $t, $b, $c, $u);
                """;
            cmd.Parameters.AddWithValue("$id", n.Id);
            Bind(cmd, n);
            cmd.ExecuteNonQuery();
        }
    }

    // ---- project fingerprint & rename handling ----

    public void SaveFingerprint(string repoPath, string fingerprint)
    {
        if (string.IsNullOrWhiteSpace(repoPath) || string.IsNullOrWhiteSpace(fingerprint)) return;
        lock (_lock)
        {
            using var cmd = _c.CreateCommand();
            cmd.CommandText = """
                INSERT INTO project_fingerprints(repo_path, fingerprint, updated_utc)
                VALUES($p, $f, $u)
                ON CONFLICT(repo_path) DO UPDATE SET fingerprint=$f, updated_utc=$u;
                """;
            cmd.Parameters.AddWithValue("$p", repoPath);
            cmd.Parameters.AddWithValue("$f", fingerprint);
            cmd.Parameters.AddWithValue("$u", DateTime.UtcNow.ToString("O"));
            cmd.ExecuteNonQuery();
        }
    }

    public Dictionary<string, string> GetAllFingerprints()
    {
        var dict = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        lock (_lock)
        {
            using var cmd = _c.CreateCommand();
            cmd.CommandText = "SELECT repo_path, fingerprint FROM project_fingerprints";
            using var r = cmd.ExecuteReader();
            while (r.Read())
            {
                var p = r.GetString(0);
                var f = r.GetString(1);
                if (!string.IsNullOrEmpty(p) && !string.IsNullOrEmpty(f))
                    dict[p] = f;
            }
        }
        return dict;
    }

    public bool MigrateProjectNotes(string oldRepoPath, string newRepoPath)
    {
        if (string.IsNullOrWhiteSpace(oldRepoPath) || string.IsNullOrWhiteSpace(newRepoPath)) return false;
        if (string.Equals(oldRepoPath, newRepoPath, StringComparison.OrdinalIgnoreCase)) return false;

        lock (_lock)
        {
            using var cmd = _c.CreateCommand();
            cmd.CommandText = "UPDATE notes SET repo_path=$new WHERE LOWER(repo_path)=LOWER($old)";
            cmd.Parameters.AddWithValue("$new", newRepoPath);
            cmd.Parameters.AddWithValue("$old", oldRepoPath);
            cmd.ExecuteNonQuery();

            using var delFp = _c.CreateCommand();
            delFp.CommandText = "DELETE FROM project_fingerprints WHERE LOWER(repo_path)=LOWER($old)";
            delFp.Parameters.AddWithValue("$old", oldRepoPath);
            delFp.ExecuteNonQuery();
        }

        // Also update remote server notes if available
        try
        {
            using var remote = CreateRemoteClient();
            if (remote != null)
            {
                var notes = remote.GetNotes();
                foreach (var note in notes.Where(n => string.Equals(n.RepoPath, oldRepoPath, StringComparison.OrdinalIgnoreCase)))
                {
                    remote.Update(note.Id, new ApiNoteWrite(
                        newRepoPath,
                        note.Title,
                        note.Body,
                        note.CreatedUtc,
                        DateTimeOffset.UtcNow,
                        note.UpdatedUtc
                    ));
                }
            }
        }
        catch { }

        return true;
    }

    /// <summary>Imports notes from a selected SQLite backup into xServer, then refreshes this PC's cache.</summary>
    public int ImportDatabaseBackup(string backupPath)
    {
        if (!File.Exists(backupPath))
            throw new FileNotFoundException("The selected backup database could not be found.", backupPath);

        var sourceBuilder = new SqliteConnectionStringBuilder
        {
            DataSource = Path.GetFullPath(backupPath),
            Mode = SqliteOpenMode.ReadOnly,
            Pooling = false,
            DefaultTimeout = 10
        };
        var notes = new List<ApiNoteWrite>();
        using (var source = new SqliteConnection(sourceBuilder.ToString()))
        {
            source.Open();
            using (var check = source.CreateCommand())
            {
                check.CommandText = "PRAGMA integrity_check;";
                var integrity = Convert.ToString(check.ExecuteScalar());
                if (!string.Equals(integrity, "ok", StringComparison.OrdinalIgnoreCase))
                    throw new InvalidDataException("The selected file is not a healthy SQLite database: " + integrity);
            }

            using var cmd = source.CreateCommand();
            cmd.CommandText = """
                SELECT id, repo_path, title, body, created_utc, updated_utc
                FROM notes ORDER BY id;
                """;
            using var reader = cmd.ExecuteReader();
            var ids = new HashSet<long>();
            while (reader.Read())
            {
                var id = reader.GetInt64(0);
                if (id <= 0 || !ids.Add(id))
                    throw new InvalidDataException("The backup contains an invalid or duplicate note ID.");
                var created = DateTimeOffset.Parse(reader.GetString(4), System.Globalization.CultureInfo.InvariantCulture);
                var updated = DateTimeOffset.Parse(reader.GetString(5), System.Globalization.CultureInfo.InvariantCulture);
                notes.Add(new ApiNoteWrite(
                    reader.IsDBNull(1) ? "" : reader.GetString(1),
                    reader.GetString(2),
                    reader.GetString(3),
                    created,
                    updated,
                    null,
                    id));
            }
        }

        if (notes.Count == 0)
            throw new InvalidDataException("The selected database contains no notes.");

        using var remote = CreateRemoteClient()
            ?? throw new InvalidOperationException("Configure the xServer notes API in Settings before importing a backup.");
        var imported = remote.Import(notes);
        var configuredUrl = GetSetting("notes_api_url").Trim();
        var serverNotes = remote.GetNotes();
        var refreshed = serverNotes.Select(n => NoteVM.FromDb(
            n.Id, n.RepoPath ?? "", n.Title ?? "", n.Body ?? "",
            n.CreatedUtc.UtcDateTime, n.UpdatedUtc.UtcDateTime)).ToList();
        ReplaceLocalNotes(refreshed);
        SetSetting("notes_api_initialized_for", configuredUrl);
        LastRemoteError = "";
        return imported;
    }

    public void PublishRepositories(IReadOnlyCollection<string> repositoryPaths)
    {
        using var remote = CreateRemoteClient();
        if (remote is null) return;
        remote.PublishRepositories(repositoryPaths);
        LastRemoteError = "";
    }

    private NotesApiClient? CreateRemoteClient()
    {
        if (!_remoteEnabled) return null;
        var url = GetSetting("notes_api_url").Trim();
        if (url.Length == 0) return null;
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
            throw new InvalidOperationException("The central notes API URL must be a valid HTTP or HTTPS URL.");

        var key = GetSecret("notes_api_key");
        if (key.Length == 0)
            throw new InvalidOperationException("The central notes API key is missing. Open Settings and enter it.");
        return new NotesApiClient(url, key);
    }

    private static ApiNoteWrite ToWrite(NoteVM n, DateTimeOffset? expected = null, bool preserveId = false) =>
        new(n.RepoPath, n.Title, n.Body,
            new DateTimeOffset(DateTime.SpecifyKind(n.CreatedUtc, DateTimeKind.Utc)),
            new DateTimeOffset(DateTime.SpecifyKind(n.UpdatedUtc, DateTimeKind.Utc)),
            expected,
            preserveId ? n.Id : null);

    private static void Bind(SqliteCommand cmd, NoteVM n)
    {
        cmd.Parameters.AddWithValue("$r", string.IsNullOrEmpty(n.RepoPath) ? DBNull.Value : n.RepoPath);
        cmd.Parameters.AddWithValue("$t", n.Title);
        cmd.Parameters.AddWithValue("$b", n.Body);
        cmd.Parameters.AddWithValue("$c", n.CreatedUtc.ToString("O"));
        cmd.Parameters.AddWithValue("$u", n.UpdatedUtc.ToString("O"));
    }

    private static DateTime ParseUtc(string s) =>
        DateTime.TryParse(s, null, System.Globalization.DateTimeStyles.RoundtripKind, out var d) ? d.ToUniversalTime() : DateTime.UtcNow;

    // ---- settings ----
    public string GetSetting(string key)
    {
        lock (_lock)
        {
            using var cmd = _c.CreateCommand();
            cmd.CommandText = "SELECT value FROM settings WHERE key=$k";
            cmd.Parameters.AddWithValue("$k", key);
            return cmd.ExecuteScalar() as string ?? "";
        }
    }

    public void SetSetting(string key, string value)
    {
        lock (_lock)
        {
            using var cmd = _c.CreateCommand();
            cmd.CommandText = "INSERT INTO settings(key,value) VALUES($k,$v) ON CONFLICT(key) DO UPDATE SET value=$v";
            cmd.Parameters.AddWithValue("$k", key);
            cmd.Parameters.AddWithValue("$v", value);
            cmd.ExecuteNonQuery();
        }
    }

    /// <summary>Secrets (API key) are encrypted with Windows DPAPI for the current user.</summary>
    public string GetSecret(string key)
    {
        var raw = GetSetting("secret:" + key);
        if (raw.Length == 0) return "";
        try
        {
            return Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(raw), null, DataProtectionScope.CurrentUser));
        }
        catch { return ""; }
    }

    public void SetSecret(string key, string value)
    {
        if (value.Length == 0) { SetSetting("secret:" + key, ""); return; }
        var enc = ProtectedData.Protect(Encoding.UTF8.GetBytes(value), null, DataProtectionScope.CurrentUser);
        SetSetting("secret:" + key, Convert.ToBase64String(enc));
    }
}
