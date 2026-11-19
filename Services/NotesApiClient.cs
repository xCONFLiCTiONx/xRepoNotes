using System.Net;
using System.Net.Http;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace RepoNotes.Services;

internal sealed class NotesApiClient : IDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private readonly HttpClient _http;

    public NotesApiClient(string baseUrl, string apiKey)
    {
        _http = new HttpClient { BaseAddress = new Uri(baseUrl.TrimEnd('/') + "/"), Timeout = TimeSpan.FromSeconds(20) };
        _http.DefaultRequestHeaders.Add("X-RepoNotes-Key", apiKey);
        _http.DefaultRequestHeaders.Accept.ParseAdd("application/json");
    }

    public List<ApiNote> GetNotes() =>
        _http.GetFromJsonAsync<List<ApiNote>>("api/notes", JsonOptions).GetAwaiter().GetResult() ?? [];

    public HashSet<long> GetDeletedNoteIds() =>
        (_http.GetFromJsonAsync<List<DeletedApiNote>>("api/notes/deletions", JsonOptions).GetAwaiter().GetResult() ?? [])
            .Select(n => n.Id).ToHashSet();

    public ApiNote Create(ApiNoteWrite note)
    {
        using var response = _http.PostAsJsonAsync("api/notes", note, JsonOptions).GetAwaiter().GetResult();
        EnsureSuccess(response);
        return response.Content.ReadFromJsonAsync<ApiNote>(JsonOptions).GetAwaiter().GetResult()
            ?? throw new InvalidOperationException("The notes API returned an empty create response.");
    }

    public ApiNote Update(long id, ApiNoteWrite note)
    {
        using var response = _http.PutAsJsonAsync($"api/notes/{id}", note, JsonOptions).GetAwaiter().GetResult();
        EnsureSuccess(response);
        return response.Content.ReadFromJsonAsync<ApiNote>(JsonOptions).GetAwaiter().GetResult()
            ?? throw new InvalidOperationException("The notes API returned an empty update response.");
    }

    public void PublishRepositories(IReadOnlyCollection<string> paths)
    {
        var repositories = paths
            .Where(path => !string.IsNullOrWhiteSpace(path))
            .Select(path => new
            {
                Path = path,
                Name = System.IO.Path.GetFileName(path.TrimEnd('\\', '/'))
            })
            .ToList();
        using var response = _http.PutAsJsonAsync("api/repositories", new { Repositories = repositories }, JsonOptions).GetAwaiter().GetResult();
        EnsureSuccess(response);
    }

    public int Import(IReadOnlyCollection<ApiNoteWrite> notes)
    {
        using var response = _http.PostAsJsonAsync("api/notes/import", new { Notes = notes }, JsonOptions).GetAwaiter().GetResult();
        EnsureSuccess(response);
        using var document = JsonDocument.Parse(response.Content.ReadAsStringAsync().GetAwaiter().GetResult());
        return document.RootElement.GetProperty("imported").GetInt32();
    }

    public void Delete(long id, DateTime expectedUpdatedUtc)
    {
        var expected = Uri.EscapeDataString(new DateTimeOffset(DateTime.SpecifyKind(expectedUpdatedUtc, DateTimeKind.Utc)).ToString("O"));
        using var response = _http.DeleteAsync($"api/notes/{id}?expectedUpdatedUtc={expected}").GetAwaiter().GetResult();
        EnsureSuccess(response);
    }

    public void UploadImage(string id, string mimeType, byte[] data)
    {
        var payload = new
        {
            Id = id,
            MimeType = mimeType,
            DataBase64 = Convert.ToBase64String(data)
        };
        using var response = _http.PostAsJsonAsync("api/images", payload, JsonOptions).GetAwaiter().GetResult();
        EnsureSuccess(response);
    }

    public byte[]? DownloadImage(string id)
    {
        using var response = _http.GetAsync($"api/images/{Uri.EscapeDataString(id)}").GetAwaiter().GetResult();
        if (response.StatusCode == HttpStatusCode.NotFound) return null;
        EnsureSuccess(response);
        return response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult();
    }

    public void TestConnection() => _ = GetNotes();

    public void Dispose() => _http.Dispose();

    private static void EnsureSuccess(HttpResponseMessage response)
    {
        if (response.IsSuccessStatusCode) return;
        var detail = response.Content.ReadAsStringAsync().GetAwaiter().GetResult();
        if (response.StatusCode == HttpStatusCode.Conflict)
            throw new NoteConflictException("This note was changed on another device. Reload the server version before saving again.");
        throw new InvalidOperationException($"Notes API returned HTTP {(int)response.StatusCode}: {detail}");
    }
}

internal sealed record ApiNote(
    long Id,
    string RepoPath,
    string Title,
    string Body,
    DateTimeOffset CreatedUtc,
    DateTimeOffset UpdatedUtc);

internal sealed record DeletedApiNote(long Id, DateTimeOffset DeletedUtc);

internal sealed record ApiNoteWrite(
    string RepoPath,
    string Title,
    string Body,
    DateTimeOffset? CreatedUtc = null,
    DateTimeOffset? UpdatedUtc = null,
    DateTimeOffset? ExpectedUpdatedUtc = null,
    long? Id = null);

internal sealed class NoteConflictException : Exception
{
    public NoteConflictException(string message) : base(message) { }
}
