using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

namespace RepoNotes.Services;

public static class ProjectFingerprint
{
    [DllImport("kernel32.dll", CharSet = CharSet.Auto, SetLastError = true)]
    private static extern bool GetFileInformationByHandle(IntPtr hFile, out BY_HANDLE_FILE_INFORMATION lpFileInformation);

    [DllImport("kernel32.dll", CharSet = CharSet.Auto, SetLastError = true)]
    private static extern IntPtr CreateFile(
        string lpFileName,
        uint dwDesiredAccess,
        uint dwShareMode,
        IntPtr lpSecurityAttributes,
        uint dwCreationDisposition,
        uint dwFlagsAndAttributes,
        IntPtr hTemplateFile);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr hObject);

    private const uint FILE_READ_ATTRIBUTES = 0x0080;
    private const uint FILE_SHARE_READ = 0x00000001;
    private const uint FILE_SHARE_WRITE = 0x00000002;
    private const uint OPEN_EXISTING = 3;
    private const uint FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
    private static readonly IntPtr INVALID_HANDLE_VALUE = new IntPtr(-1);

    [StructLayout(LayoutKind.Sequential)]
    private struct BY_HANDLE_FILE_INFORMATION
    {
        public uint dwFileAttributes;
        public System.Runtime.InteropServices.ComTypes.FILETIME ftCreationTime;
        public System.Runtime.InteropServices.ComTypes.FILETIME ftLastAccessTime;
        public System.Runtime.InteropServices.ComTypes.FILETIME ftLastWriteTime;
        public uint dwVolumeSerialNumber;
        public uint nFileSizeHigh;
        public uint nFileSizeLow;
        public uint nNumberOfLinks;
        public uint nFileIndexHigh;
        public uint nFileIndexLow;
    }

    /// <summary>
    /// Generates a fingerprint for a directory using Git initial commit SHA,
    /// Git remote origin URL, and Win32 directory FileIndex + VolumeSerialNumber.
    /// This fingerprint persists across folder renames without modifying the project folder.
    /// </summary>
    public static string Compute(string dirPath)
    {
        if (string.IsNullOrWhiteSpace(dirPath) || !Directory.Exists(dirPath))
            return "";

        var sb = new StringBuilder();

        // 1. Git Initial Commit SHA & Origin URL
        var gitDir = Path.Combine(dirPath, ".git");
        if (Directory.Exists(gitDir) || File.Exists(gitDir))
        {
            var initialCommit = ReadGitInitialCommit(gitDir);
            if (!string.IsNullOrEmpty(initialCommit))
                sb.Append("GITINIT:").Append(initialCommit).Append('|');

            var remoteUrl = ReadGitRemoteUrl(gitDir);
            if (!string.IsNullOrEmpty(remoteUrl))
                sb.Append("GITREMOTE:").Append(remoteUrl).Append('|');
        }

        // 2. Win32 Directory Handle Volume Serial & File Index
        var (volSerial, fileIndex) = GetDirectoryIndexInfo(dirPath);
        if (volSerial != 0 || fileIndex != 0)
        {
            sb.Append("FS:").Append(volSerial).Append(':').Append(fileIndex).Append('|');
        }

        // 3. Creation Time
        try
        {
            var creationTicks = Directory.GetCreationTimeUtc(dirPath).Ticks;
            sb.Append("CT:").Append(creationTicks);
        }
        catch { }

        return sb.ToString().TrimEnd('|');
    }

    private static string ReadGitInitialCommit(string gitPath)
    {
        try
        {
            string logPath = Directory.Exists(gitPath)
                ? Path.Combine(gitPath, "logs", "HEAD")
                : Path.Combine(gitPath, "logs", "HEAD"); // worktree fallback handled gracefully

            if (File.Exists(logPath))
            {
                using var reader = new StreamReader(logPath, Encoding.UTF8);
                string? firstLine = reader.ReadLine();
                if (!string.IsNullOrWhiteSpace(firstLine))
                {
                    var parts = firstLine.Split(' ', StringSplitOptions.RemoveEmptyEntries);
                    if (parts.Length >= 2)
                    {
                        // Second token is the commit SHA created on repository initialization / first commit
                        return parts[1];
                    }
                }
            }
        }
        catch { }
        return "";
    }

    private static string ReadGitRemoteUrl(string gitPath)
    {
        try
        {
            string configPath = Directory.Exists(gitPath)
                ? Path.Combine(gitPath, "config")
                : Path.Combine(gitPath, "config");

            if (File.Exists(configPath))
            {
                bool inRemoteOrigin = false;
                foreach (var line in File.ReadLines(configPath))
                {
                    var trimmed = line.Trim();
                    if (trimmed.Equals("[remote \"origin\"]", StringComparison.OrdinalIgnoreCase))
                    {
                        inRemoteOrigin = true;
                        continue;
                    }
                    if (trimmed.StartsWith("[") && trimmed.EndsWith("]"))
                    {
                        inRemoteOrigin = false;
                        continue;
                    }
                    if (inRemoteOrigin && trimmed.StartsWith("url =", StringComparison.OrdinalIgnoreCase))
                    {
                        return trimmed.Substring(5).Trim();
                    }
                }
            }
        }
        catch { }
        return "";
    }

    private static (uint volumeSerial, ulong fileIndex) GetDirectoryIndexInfo(string dirPath)
    {
        try
        {
            IntPtr handle = CreateFile(
                dirPath,
                FILE_READ_ATTRIBUTES,
                FILE_SHARE_READ | FILE_SHARE_WRITE,
                IntPtr.Zero,
                OPEN_EXISTING,
                FILE_FLAG_BACKUP_SEMANTICS,
                IntPtr.Zero);

            if (handle == INVALID_HANDLE_VALUE)
                return (0, 0);

            try
            {
                if (GetFileInformationByHandle(handle, out var info))
                {
                    ulong index = ((ulong)info.nFileIndexHigh << 32) | info.nFileIndexLow;
                    return (info.dwVolumeSerialNumber, index);
                }
            }
            finally
            {
                CloseHandle(handle);
            }
        }
        catch { }
        return (0, 0);
    }
}
