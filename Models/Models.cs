using System.ComponentModel;
using System.IO;
using System.Runtime.CompilerServices;
using System.Windows.Media;

namespace RepoNotes.Models;

public enum NoteScope
{
    Personal,
    Work,
    VaultPersonal,
    VaultWork
}

public static class RepoColors
{
    private static readonly Dictionary<string, (Brush accent, Brush chip, Brush card)> Cache = new(StringComparer.OrdinalIgnoreCase);

    public static (Brush accent, Brush chip, Brush card) For(string repoPath)
    {
        if (Cache.TryGetValue(repoPath, out var c)) return c;

        Color accent;
        if (repoPath.Length == 0) accent = Color.FromRgb(0x8A, 0x94, 0xA6);
        else
        {
            uint h = 2166136261;
            foreach (var ch in Path.GetFileName(repoPath).ToLowerInvariant()) h = (h ^ ch) * 16777619;
            accent = FromHsl(h % 360, 0.60, 0.64);
        }
        var chip = Color.FromArgb(0x30, accent.R, accent.G, accent.B);
        // card = Bg2 (#1E232B) tinted with the accent
        byte Mix(byte bg, byte a) => (byte)(bg + (a - bg) * 0.10);
        var card = Color.FromRgb(Mix(0x1E, accent.R), Mix(0x23, accent.G), Mix(0x2B, accent.B));

        static Brush F(Color col) { var b = new SolidColorBrush(col); b.Freeze(); return b; }
        return Cache[repoPath] = (F(accent), F(chip), F(card));
    }

    private static Color FromHsl(double h, double s, double l)
    {
        double c = (1 - Math.Abs(2 * l - 1)) * s, x = c * (1 - Math.Abs(h / 60 % 2 - 1)), m = l - c / 2;
        (double r, double g, double b) = (int)(h / 60) switch
        {
            0 => (c, x, 0d), 1 => (x, c, 0d), 2 => (0d, c, x), 3 => (0d, x, c), 4 => (x, 0d, c), _ => (c, 0d, x)
        };
        return Color.FromRgb((byte)((r + m) * 255), (byte)((g + m) * 255), (byte)((b + m) * 255));
    }
}

public sealed class NoteVM : INotifyPropertyChanged
{
    private string _title = "", _body = "", _repo = "";
    private bool _busy, _canUndo, _isEditingInline;
    private string _busyText = "";
    private DateTime _updated;

    public long Id { get; set; }
    public string ClientId { get; set; } = Guid.NewGuid().ToString("N");
    public DateTime CreatedUtc { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedUtc { get => _updated; private set { _updated = value; Raise(); Raise(nameof(UpdatedText)); } }
    public DateTime ServerUpdatedUtc { get; private set; }
    public string LastSyncedBody { get; set; } = "";
    public string LastSyncedTitle { get; set; } = "";
    public string LastSyncedRepoPath { get; set; } = "";

    /// <summary>Raised when the user (or AI) changes persisted content.</summary>
    public event Action<NoteVM>? Edited;
    public event PropertyChangedEventHandler? PropertyChanged;

    public CancellationTokenSource? AiCts { get; set; }
    private string _undoBody = "";

    public static NoteVM FromDb(long id, string repo, string title, string body, DateTime created, DateTime updated, string clientId = "", DateTime? serverUpdatedUtc = null, string? lastSyncedBody = null, string? lastSyncedTitle = null, string? lastSyncedRepoPath = null) =>
        new() { Id = id, ClientId = string.IsNullOrWhiteSpace(clientId) ? "legacy:" + id : clientId, _repo = repo, _title = title, _body = body, CreatedUtc = created, _updated = updated, ServerUpdatedUtc = serverUpdatedUtc ?? updated, LastSyncedBody = lastSyncedBody ?? body, LastSyncedTitle = lastSyncedTitle ?? title, LastSyncedRepoPath = lastSyncedRepoPath ?? repo };

    public void MarkServerSaved(DateTime updatedUtc)
    {
        ServerUpdatedUtc = DateTime.SpecifyKind(updatedUtc, DateTimeKind.Utc);
        UpdatedUtc = ServerUpdatedUtc;
    }

    public void ApplyServerState(string repo, string title, string body, DateTime createdUtc, DateTime updatedUtc)
    {
        repo ??= "";
        title ??= "";
        body ??= "";

        if (!string.Equals(_repo, repo, StringComparison.Ordinal))
        {
            _repo = repo;
            Raise(nameof(RepoPath));
            Raise(nameof(RepoLabel));
            Raise(nameof(AccentBrush));
            Raise(nameof(ChipBrush));
            Raise(nameof(CardBrush));
            Raise(nameof(VaultActionText));
            Raise(nameof(VaultActionToolTip));
        }
        if (!string.Equals(_title, title, StringComparison.Ordinal))
        {
            _title = title;
            Raise(nameof(Title));
            Raise(nameof(HasTitle));
        }
        if (!string.Equals(_body, body, StringComparison.Ordinal))
        {
            _body = body;
            Raise(nameof(Body));
            Raise(nameof(HasBody));
            CanUndo = false;
        }
        CreatedUtc = createdUtc;
        MarkServerSaved(updatedUtc);
        LastSyncedRepoPath = repo;
        LastSyncedTitle = title;
        LastSyncedBody = body;
    }

    public void SetRepoPathSilent(string repo)
    {
        repo ??= "";
        if (string.Equals(_repo, repo, StringComparison.Ordinal)) return;
        _repo = repo;
        Raise(nameof(RepoPath));
        Raise(nameof(RepoLabel));
        Raise(nameof(AccentBrush));
        Raise(nameof(ChipBrush));
        Raise(nameof(CardBrush));
        Raise(nameof(VaultActionText));
        Raise(nameof(VaultActionToolTip));
    }

    public string Title
    {
        get => _title;
        set
        {
            if (_title != value)
            {
                _title = value;
                Raise();
                Raise(nameof(HasTitle));
                UserEdit();
            }
        }
    }

    public bool HasTitle => !string.IsNullOrWhiteSpace(_title);

    public string Body
    {
        get => _body;
        set
        {
            if (_body != value)
            {
                _body = value;
                Raise();
                Raise(nameof(HasBody));
                CanUndo = false;
                UserEdit();
            }
        }
    }

    public bool HasBody => !string.IsNullOrWhiteSpace(_body);

    public bool IsEditingInline
    {
        get => _isEditingInline;
        set
        {
            if (_isEditingInline != value)
            {
                _isEditingInline = value;
                Raise();
                Raise(nameof(QuickEditIcon));
                Raise(nameof(QuickEditToolTip));
            }
        }
    }

    public string QuickEditIcon => IsEditingInline ? "\uE73E" : "\uE70F";
    public string QuickEditToolTip => IsEditingInline ? "Done / Preview" : "Quick edit inline";

    public string RepoPath
    {
        get => _repo;
        set
        {
            value ??= "";
            if (_repo == value) return;
            _repo = value;
            Raise();
            Raise(nameof(RepoLabel)); Raise(nameof(AccentBrush)); Raise(nameof(ChipBrush)); Raise(nameof(CardBrush));
            UserEdit();
        }
    }

    private string EffectiveRepoPath => RepoNotes.Services.Database.UnwrapRepoPath(_repo);
    public string RepoLabel => EffectiveRepoPath.Length == 0 ? "General" : Path.GetFileName(EffectiveRepoPath.TrimEnd('\\', '/'));
    public bool IsInVault => RepoNotes.Services.Database.IsVaultPersonalRepoPath(_repo) || RepoNotes.Services.Database.IsVaultWorkRepoPath(_repo);
    public string VaultActionText => IsInVault ? "↩ Restore" : "🔒 Vault";
    public string VaultActionToolTip => IsInVault ? "Move note out of Vault" : "Send note to Vault";
    public Brush AccentBrush => RepoColors.For(EffectiveRepoPath).accent;
    public Brush ChipBrush => RepoColors.For(EffectiveRepoPath).chip;
    public Brush CardBrush => RepoColors.For(EffectiveRepoPath).card;
    public string UpdatedText => UpdatedUtc.ToLocalTime().ToString("MMM d, h:mm tt");

    public bool IsBusy { get => _busy; set { _busy = value; Raise(); } }
    public string BusyText { get => _busyText; set { _busyText = value; Raise(); } }
    public bool CanUndo { get => _canUndo; set { _canUndo = value; Raise(); } }

    public void ApplyAi(string markdown)
    {
        _undoBody = _body;
        _body = markdown;
        Raise(nameof(Body));
        Raise(nameof(HasBody));
        UserEdit();
        CanUndo = true;
    }

    public void UndoAi()
    {
        if (!CanUndo) return;
        _body = _undoBody;
        Raise(nameof(Body));
        Raise(nameof(HasBody));
        UserEdit();
        CanUndo = false;
    }

    private void UserEdit()
    {
        UpdatedUtc = DateTime.UtcNow;
        Edited?.Invoke(this);
    }

    private void Raise([CallerMemberName] string? n = null) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(n));
}

public sealed class RepoChoice
{
    public string Path { get; init; } = "";
    public string Name { get; init; } = "";
    public override string ToString() => Name;
}

public sealed class SidebarEntry : INotifyPropertyChanged
{
    private int _count;
    public string? Key { get; init; }          // null = all notes, "" = general, otherwise repo path
    public string Display { get; init; } = "";
    public bool IsRepo { get; init; }
    public bool Missing { get; init; }
    public Brush Dot { get; init; } = Brushes.Gray;
    public string Tip => Missing ? "Folder not found under the repos directory" : Key ?? "";
    public int Count { get => _count; set { _count = value; PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Count))); } }
    public event PropertyChangedEventHandler? PropertyChanged;
}

