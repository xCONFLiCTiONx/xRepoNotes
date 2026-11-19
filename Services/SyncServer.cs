using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using RepoNotes.Models;

namespace RepoNotes.Services;

public sealed class NoteDto
{
    public long id { get; set; }
    public string repo_path { get; set; } = "";
    public string title { get; set; } = "";
    public string body { get; set; } = "";
    public string created_utc { get; set; } = "";
    public string updated_utc { get; set; } = "";
    public string? last_synced_body { get; set; }
    public bool is_dirty { get; set; }
}

public sealed class SyncServer
{
    private readonly Database _db;
    private HttpListener? _listener;
    private CancellationTokenSource? _cts;

    public SyncServer(Database db)
    {
        _db = db;
    }

    public void Start(int port = 8080)
    {
        _cts = new CancellationTokenSource();
        Task.Run(() => ListenLoop(port, _cts.Token));
    }

    public void Stop()
    {
        _cts?.Cancel();
        try
        {
            _listener?.Stop();
            _listener?.Close();
        }
        catch { }
    }

    private async Task ListenLoop(int port, CancellationToken ct)
    {
        _listener = new HttpListener();
        string[] prefixes = new[]
        {
            $"http://localhost:{port}/",
            $"http://127.0.0.1:{port}/",
            $"http://+:{port}/"
        };

        bool started = false;
        foreach (var prefix in prefixes)
        {
            try
            {
                _listener.Prefixes.Clear();
                _listener.Prefixes.Add(prefix);
                _listener.Start();
                started = true;
                break;
            }
            catch
            {
                // Fallback to next prefix if admin permission is missing for +:
            }
        }

        if (!started)
        {
            try
            {
                _listener.Prefixes.Clear();
                _listener.Prefixes.Add($"http://localhost:{port}/");
                _listener.Start();
                started = true;
            }
            catch { return; }
        }

        while (!ct.IsCancellationRequested && _listener.IsListening)
        {
            try
            {
                var context = await _listener.GetContextAsync();
                _ = Task.Run(() => ProcessRequestAsync(context));
            }
            catch
            {
                if (ct.IsCancellationRequested) break;
            }
        }
    }

    private async Task ProcessRequestAsync(HttpListenerContext context)
    {
        var req = context.Request;
        var resp = context.Response;

        // Enable CORS
        resp.AddHeader("Access-Control-Allow-Origin", "*");
        resp.AddHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        resp.AddHeader("Access-Control-Allow-Headers", "Content-Type");

        if (req.HttpMethod == "OPTIONS")
        {
            resp.StatusCode = 200;
            resp.Close();
            return;
        }

        string rawPath = req.Url?.AbsolutePath.ToLowerInvariant() ?? "";

        try
        {
            if (rawPath == "/ping" || rawPath == "/api/ping")
            {
                await SendJsonAsync(resp, new { status = "ok", app = "xRepoNotes" });
            }
            else if (rawPath == "/notes" || rawPath == "/api/notes")
            {
                if (req.HttpMethod == "GET")
                {
                    var notes = _db.LoadNotes().Select(ToDto).ToList();
                    await SendJsonAsync(resp, notes);
                }
                else if (req.HttpMethod == "POST")
                {
                    using var reader = new StreamReader(req.InputStream, Encoding.UTF8);
                    string bodyText = await reader.ReadToEndAsync();
                    var mobileNotes = JsonSerializer.Deserialize<List<NoteDto>>(bodyText) ?? new List<NoteDto>();
                    var merged = PerformSync(mobileNotes);
                    await SendJsonAsync(resp, merged);
                }
            }
            else if (rawPath == "/notes/sync" || rawPath == "/api/notes/sync")
            {
                using var reader = new StreamReader(req.InputStream, Encoding.UTF8);
                string bodyText = await reader.ReadToEndAsync();
                var mobileNotes = JsonSerializer.Deserialize<List<NoteDto>>(bodyText) ?? new List<NoteDto>();
                var merged = PerformSync(mobileNotes);
                await SendJsonAsync(resp, merged);
            }
            else
            {
                resp.StatusCode = 404;
                resp.Close();
            }
        }
        catch (Exception ex)
        {
            try
            {
                resp.StatusCode = 500;
                await SendJsonAsync(resp, new { error = ex.Message });
            }
            catch { }
        }
    }

    private List<NoteDto> PerformSync(List<NoteDto> mobileNotes)
    {
        var pcNotes = _db.LoadNotes();
        var pcMap = pcNotes.ToDictionary(n => n.Id);

        foreach (var m in mobileNotes)
        {
            if (m.id > 0 && pcMap.TryGetValue(m.id, out var pcNote))
            {
                // Note exists on both PC and Mobile
                if (m.is_dirty)
                {
                    string baseBody = m.last_synced_body ?? pcNote.Body;
                    string mergedBody = MergeContent(baseBody, pcNote.Body, m.body ?? "");

                    pcNote.Title = string.IsNullOrWhiteSpace(m.title) ? pcNote.Title : m.title;
                    pcNote.Body = mergedBody;
                    if (!string.IsNullOrEmpty(m.repo_path)) pcNote.RepoPath = m.repo_path;
                    _db.Update(pcNote);
                }
            }
            else if (m.id <= 0 || !pcMap.ContainsKey(m.id))
            {
                // New note from Mobile
                var newNote = NoteVM.FromDb(
                    0,
                    m.repo_path ?? "",
                    m.title ?? "Untitled Note",
                    m.body ?? "",
                    ParseUtc(m.created_utc),
                    DateTime.UtcNow,
                    "local:" + Guid.NewGuid().ToString("N")
                );
                long newId = _db.Insert(newNote);
                m.id = newId;
            }
        }

        return _db.LoadNotes().Select(ToDto).ToList();
    }

    public static string MergeContent(string baseText, string desktopText, string mobileText)
    {
        if (desktopText == mobileText) return mobileText;
        if (desktopText == baseText) return mobileText;
        if (mobileText == baseText) return desktopText;

        // Both desktop and mobile edited - 3-way line merge with [M] and [D] tags
        var baseLines = (baseText ?? "").Split('\n');
        var desktopLines = (desktopText ?? "").Split('\n');
        var mobileLines = (mobileText ?? "").Split('\n');

        int maxLines = Math.Max(desktopLines.Length, mobileLines.Length);
        var resultLines = new List<string>();

        for (int i = 0; i < maxLines; i++)
        {
            string? dLine = i < desktopLines.Length ? desktopLines[i] : null;
            string? mLine = i < mobileLines.Length ? mobileLines[i] : null;
            string? bLine = i < baseLines.Length ? baseLines[i] : null;

            if (dLine == mLine && dLine != null)
            {
                resultLines.Add(dLine);
            }
            else if (mLine != null && bLine != null && mLine == bLine && dLine != null)
            {
                resultLines.Add(dLine);
            }
            else if (dLine != null && bLine != null && dLine == bLine && mLine != null)
            {
                resultLines.Add(mLine);
            }
            else
            {
                // Conflict on line i
                if (mLine != null)
                {
                    resultLines.Add(mLine.StartsWith("[M]") ? mLine : $"[M] {mLine}");
                }
                if (dLine != null)
                {
                    resultLines.Add(dLine.StartsWith("[D]") ? dLine : $"[D] {dLine}");
                }
            }
        }

        return string.Join("\n", resultLines);
    }

    private static NoteDto ToDto(NoteVM n) => new()
    {
        id = n.Id,
        repo_path = n.RepoPath,
        title = n.Title,
        body = n.Body,
        created_utc = n.CreatedUtc.ToString("O"),
        updated_utc = n.UpdatedUtc.ToString("O")
    };

    private static DateTime ParseUtc(string s) =>
        DateTime.TryParse(s, null, System.Globalization.DateTimeStyles.RoundtripKind, out var d) ? d.ToUniversalTime() : DateTime.UtcNow;

    private static async Task SendJsonAsync(HttpListenerResponse resp, object data)
    {
        string json = JsonSerializer.Serialize(data);
        byte[] bytes = Encoding.UTF8.GetBytes(json);
        resp.ContentType = "application/json; charset=utf-8";
        resp.ContentLength64 = bytes.Length;
        await resp.OutputStream.WriteAsync(bytes, 0, bytes.Length);
        resp.OutputStream.Close();
    }
}
