using System.Collections.Concurrent;
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
    private const int MaxServerBytes = 15 * 1024 * 1024;
    private const int ThumbnailDecodeWidth = 260;
    private static readonly string[] ImageExtensions = [".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp"];
    private static readonly object ConfigLock = new();
    private static readonly ConcurrentDictionary<string, BitmapSource> ThumbnailCache = new(StringComparer.Ordinal);
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
            if (Clipboard.ContainsFileDropList())
            {
                var files = Clipboard.GetFileDropList();
                foreach (string? file in files)
                {
                    if (!string.IsNullOrWhiteSpace(file) && IsSupportedImageFile(file) && File.Exists(file))
                    {
                        var saved = SaveImageFile(file);
                        if (saved != null) return saved;
                    }
                }
            }

            if (Clipboard.ContainsImage())
            {
                var source = Clipboard.GetImage();
                if (source != null) return SaveBitmapSource(source);
            }

            return null;
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
            var rawBytes = File.ReadAllBytes(filePath);
            if (rawBytes.Length == 0) return null;

            // Validate image is decodable.
            var decoded = DecodeImageBytes(rawBytes, decodePixelWidth: ThumbnailDecodeWidth);
            if (decoded == null) return null;

            if (rawBytes.Length <= MaxServerBytes)
            {
                var mime = DetectMimeType(rawBytes, filePath);
                var id = Guid.NewGuid().ToString("N");
                File.WriteAllBytes(ImagePath(id), rawBytes);
                File.WriteAllText(PendingPath(id), mime);
                ThumbnailCache[id] = decoded;
                return id;
            }

            var full = DecodeImageBytes(rawBytes);
            return full == null ? null : SaveBitmapSource(full);
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

    public static BitmapSource? TryGetCachedThumbnail(string id, int maxWidth = ThumbnailDecodeWidth)
    {
        if (string.IsNullOrWhiteSpace(id)) return null;
        if (ThumbnailCache.TryGetValue(id, out var cachedThumb)) return cachedThumb;

        var path = ImagePath(id);
        if (!File.Exists(path)) return null;
        try
        {
            var thumb = DecodeImageBytes(File.ReadAllBytes(path), decodePixelWidth: maxWidth);
            if (thumb != null) ThumbnailCache[id] = thumb;
            return thumb;
        }
        catch
        {
            return null;
        }
    }

    public static async Task<BitmapSource?> LoadThumbnailAsync(string id, int maxWidth = ThumbnailDecodeWidth)
    {
        if (string.IsNullOrWhiteSpace(id)) return null;

        var cached = TryGetCachedThumbnail(id, maxWidth);
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

                var thumb = DecodeImageBytes(bytes, decodePixelWidth: maxWidth);
                if (thumb == null)
                {
                    LastError = "Downloaded image data could not be decoded.";
                    return null;
                }

                File.WriteAllBytes(ImagePath(id), bytes);
                ThumbnailCache[id] = thumb;
                LastError = "";
                return thumb;
            }
            catch (Exception ex)
            {
                LastError = ex.Message;
                return null;
            }
        }).ConfigureAwait(false);
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

    public static async Task<(byte[] Bytes, string MimeType, string Extension)?> GetOriginalImageAsync(string id)
    {
        if (string.IsNullOrWhiteSpace(id)) return null;
        var path = ImagePath(id);
        byte[]? bytes = null;
        if (File.Exists(path))
        {
            try
            {
                bytes = await File.ReadAllBytesAsync(path).ConfigureAwait(false);
            }
            catch
            {
                bytes = null;
            }
        }

        if (bytes == null || bytes.Length == 0)
        {
            var (url, key) = GetConfig();
            if (string.IsNullOrWhiteSpace(url) || string.IsNullOrWhiteSpace(key))
            {
                LastError = "xServer URL or API key is not configured.";
                return null;
            }

            bytes = await Task.Run(() =>
            {
                try
                {
                    using var client = new NotesApiClient(url, key);
                    var downloaded = client.DownloadImage(id);
                    if (downloaded != null && downloaded.Length > 0)
                    {
                        File.WriteAllBytes(path, downloaded);
                        LastError = "";
                    }
                    return downloaded;
                }
                catch (Exception ex)
                {
                    LastError = ex.Message;
                    return null;
                }
            }).ConfigureAwait(false);
        }

        if (bytes == null || bytes.Length == 0) return null;
        var mime = DetectMimeType(bytes);
        var ext = ExtensionForMimeType(mime);
        return (bytes, mime, ext);
    }

    public static string DetectMimeType(byte[] bytes, string? filePath = null)
    {
        if (bytes.Length >= 8 && bytes[0] == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47)
            return "image/png";
        if (bytes.Length >= 3 && bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF)
            return "image/jpeg";
        if (bytes.Length >= 6 && bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46)
            return "image/gif";
        if (bytes.Length >= 12 && bytes[0] == 0x52 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x46 &&
            bytes[8] == 0x57 && bytes[9] == 0x45 && bytes[10] == 0x42 && bytes[11] == 0x50)
            return "image/webp";
        if (bytes.Length >= 2 && bytes[0] == 0x42 && bytes[1] == 0x4D)
            return "image/bmp";

        return Path.GetExtension(filePath ?? "").ToLowerInvariant() switch
        {
            ".jpg" or ".jpeg" => "image/jpeg",
            ".gif" => "image/gif",
            ".webp" => "image/webp",
            ".bmp" => "image/bmp",
            _ => "image/png"
        };
    }

    public static string ExtensionForMimeType(string mimeType) => mimeType.ToLowerInvariant() switch
    {
        "image/jpeg" or "image/jpg" => ".jpg",
        "image/gif" => ".gif",
        "image/webp" => ".webp",
        "image/bmp" => ".bmp",
        _ => ".png"
    };

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
                var pending = PendingPath(id);
                var mimeType = File.Exists(pending) ? File.ReadAllText(pending).Trim() : "";
                if (string.IsNullOrWhiteSpace(mimeType))
                    mimeType = DetectMimeType(bytes);

                using var client = new NotesApiClient(url, key);
                client.UploadImage(id, mimeType, bytes);
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
        byte[] encodedBytes = EncodePng(source);
        string mimeType = "image/png";

        if (encodedBytes.Length > MaxServerBytes)
        {
            var scaled = ScaleDownIfNeeded(source);
            encodedBytes = EncodePng(scaled);
            if (encodedBytes.Length > MaxServerBytes)
            {
                encodedBytes = EncodeJpeg(scaled, 92);
                mimeType = "image/jpeg";
            }
        }

        var id = Guid.NewGuid().ToString("N");
        File.WriteAllBytes(ImagePath(id), encodedBytes);
        File.WriteAllText(PendingPath(id), mimeType);
        var thumb = DecodeImageBytes(encodedBytes, decodePixelWidth: ThumbnailDecodeWidth);
        if (thumb != null) ThumbnailCache[id] = thumb;
        return id;
    }

    private static byte[] EncodePng(BitmapSource source)
    {
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(source));
        using var ms = new MemoryStream();
        encoder.Save(ms);
        return ms.ToArray();
    }

    private static byte[] EncodeJpeg(BitmapSource source, int quality)
    {
        var encoder = new JpegBitmapEncoder { QualityLevel = quality };
        encoder.Frames.Add(BitmapFrame.Create(source));
        using var ms = new MemoryStream();
        encoder.Save(ms);
        return ms.ToArray();
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

    private static BitmapImage? DecodeImageBytes(byte[] bytes, int? decodePixelWidth = null)
    {
        if (bytes.Length == 0) return null;
        try
        {
            using var ms = new MemoryStream(bytes);
            var bmp = new BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = BitmapCacheOption.OnLoad;
            if (decodePixelWidth is > 0)
                bmp.DecodePixelWidth = decodePixelWidth.Value;
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

