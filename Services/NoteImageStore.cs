using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace RepoNotes.Services;

/// <summary>
/// Local cache and remote sync store for note images referenced via
/// ![alt](xreponotes-image://&lt;id&gt;).
/// </summary>
public static class NoteImageStore
{
    private const int MaxDimension = 2048;
    private static readonly string[] ImageExtensions = [".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp"];
    private static readonly object ConfigLock = new();
    private static string _serverUrl = "";
    private static string _apiKey = "";
    public static string LastError { get; private set; } = "";

    private static string ImageDir
    {
        get
        {
            var dir = Path.Combine(Database.DataDir, "note_images");
            Directory.CreateDirectory(dir);
            return dir;
        }
    }

    private static string ImagePath(string id) => Path.Combine(ImageDir, $"{id}.png");
    private static string PendingPath(string id) => Path.Combine(ImageDir, $"{id}.pending");

    public static void Configure(string? serverUrl, string? apiKey)
    {
        lock (ConfigLock)
        {
            _serverUrl = (serverUrl ?? "").Trim().TrimEnd('/');
            _apiKey = (apiKey ?? "").Trim();
        }
    }

    private static (string url, string key) GetConfig()
    {
        lock (ConfigLock)
        {
            return (_serverUrl, _apiKey);
        }
    }

    public static bool HasClipboardImage()
    {
        try
        {
            if (Clipboard.ContainsImage()) return true;
            if (Clipboard.ContainsFileDropList())
            {
                var files = Clipboard.GetFileDropList();
                foreach (string? file in files)
                {
                    if (!string.IsNullOrWhiteSpace(file) && IsSupportedImageFile(file))
                        return true;
                }
            }
        }
        catch
        {
            // Clipboard may be temporarily locked by another process.
        }
        return false;
    }

    public static string? SaveClipboardImage()
    {
        try
        {
            BitmapSource? source = null;
            if (Clipboard.ContainsImage())
            {
                source = Clipboard.GetImage();
            }
            else if (Clipboard.ContainsFileDropList())
            {
                var files = Clipboard.GetFileDropList();
                foreach (string? file in files)
                {
                    if (!string.IsNullOrWhiteSpace(file) && IsSupportedImageFile(file) && File.Exists(file))
                    {
                        source = DecodeImageBytes(File.ReadAllBytes(file));
                        if (source != null) break;
                    }
                }
            }

            if (source == null) return null;
            return SaveBitmapSource(source);
        }
        catch
        {
            return null;
        }
    }

    public static string? SaveImageFile(string filePath)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(filePath) || !File.Exists(filePath)) return null;
            var source = DecodeImageBytes(File.ReadAllBytes(filePath));
            return source == null ? null : SaveBitmapSource(source);
        }
        catch
        {
            return null;
        }
    }

    public static BitmapSource? TryGetCachedImage(string id)
    {
        if (string.IsNullOrWhiteSpace(id)) return null;
        var path = ImagePath(id);
        if (!File.Exists(path)) return null;
        try
        {
            return DecodeImageBytes(File.ReadAllBytes(path));
        }
        catch
        {
            return null;
        }
    }

    public static async Task<BitmapSource?> LoadImageAsync(string id)
    {
        if (string.IsNullOrWhiteSpace(id)) return null;

        var cached = TryGetCachedImage(id);
        if (cached != null) return cached;

        var (url, key) = GetConfig();
        if (string.IsNullOrWhiteSpace(url) || string.IsNullOrWhiteSpace(key))
        {
            LastError = "xServer URL or API key is not configured.";
            return null;
        }

        return await Task.Run(() =>
        {
            try
            {
                using var client = new NotesApiClient(url, key);
                var bytes = client.DownloadImage(id);
                if (bytes == null || bytes.Length == 0)
                {
                    LastError = "Image not found on server (HTTP 404). Make sure the server is updated to include /api/images and the mobile app uploaded the image.";
                    return null;
                }

                var decoded = DecodeImageBytes(bytes);
                if (decoded == null)
                {
                    LastError = "Downloaded image data could not be decoded.";
                    return null;
                }

                File.WriteAllBytes(ImagePath(id), bytes);
                LastError = "";
                return decoded;
            }
            catch (Exception ex)
            {
                LastError = ex.Message;
                return null;
            }
        }).ConfigureAwait(false);
    }

    public static async Task<bool> UploadImageAsync(string id)
    {
        if (string.IsNullOrWhiteSpace(id)) return false;
        var (url, key) = GetConfig();
        if (string.IsNullOrWhiteSpace(url) || string.IsNullOrWhiteSpace(key))
            return false;

        var path = ImagePath(id);
        if (!File.Exists(path)) return false;

        return await Task.Run(() =>
        {
            try
            {
                var bytes = File.ReadAllBytes(path);
                if (bytes.Length == 0) return false;
                using var client = new NotesApiClient(url, key);
                client.UploadImage(id, "image/png", bytes);
                var pending = PendingPath(id);
                if (File.Exists(pending)) File.Delete(pending);
                return true;
            }
            catch
            {
                return false;
            }
        }).ConfigureAwait(false);
    }

    public static async Task UploadPendingImagesAsync()
    {
        var (url, key) = GetConfig();
        if (string.IsNullOrWhiteSpace(url) || string.IsNullOrWhiteSpace(key))
            return;

        string[] pendingFiles;
        try
        {
            pendingFiles = Directory.GetFiles(ImageDir, "*.pending");
        }
        catch
        {
            return;
        }

        foreach (var marker in pendingFiles)
        {
            var id = Path.GetFileNameWithoutExtension(marker);
            if (!string.IsNullOrWhiteSpace(id))
                await UploadImageAsync(id).ConfigureAwait(false);
        }
    }

    private static bool IsSupportedImageFile(string path)
    {
        var ext = Path.GetExtension(path);
        return ImageExtensions.Any(e => e.Equals(ext, StringComparison.OrdinalIgnoreCase));
    }

    private static string SaveBitmapSource(BitmapSource source)
    {
        var scaled = ScaleDownIfNeeded(source);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(scaled));

        using var ms = new MemoryStream();
        encoder.Save(ms);

        var id = Guid.NewGuid().ToString("N");
        File.WriteAllBytes(ImagePath(id), ms.ToArray());
        File.WriteAllText(PendingPath(id), "image/png");
        return id;
    }

    private static BitmapSource ScaleDownIfNeeded(BitmapSource source)
    {
        int longest = Math.Max(source.PixelWidth, source.PixelHeight);
        if (longest <= MaxDimension || longest <= 0) return source;
        double scale = (double)MaxDimension / longest;
        var tb = new TransformedBitmap(source, new ScaleTransform(scale, scale));
        tb.Freeze();
        return tb;
    }

    private static BitmapImage? DecodeImageBytes(byte[] bytes)
    {
        if (bytes.Length == 0) return null;
        try
        {
            using var ms = new MemoryStream(bytes);
            var bmp = new BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = BitmapCacheOption.OnLoad;
            bmp.StreamSource = ms;
            bmp.EndInit();
            bmp.Freeze();
            return bmp;
        }
        catch
        {
            return null;
        }
    }
}

