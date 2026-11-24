using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Reflection;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using RepoNotes.Models;
using RepoNotes.Services;

namespace RepoNotes;

public partial class MainWindow : Window
{
    private Database _db = null!;
    private Database _workDb = null!;
    private Database _vaultPersonalDb = null!;
    private Database _vaultWorkDb = null!;
    private readonly ObservableCollection<NoteVM> _notes = [];
    private readonly ObservableCollection<NoteVM> _workNotes = [];
    private readonly ObservableCollection<NoteVM> _vaultPersonalNotes = [];
    private readonly ObservableCollection<NoteVM> _vaultWorkNotes = [];
    private readonly ObservableCollection<SidebarEntry> _entries = [];
    private readonly ObservableCollection<RepoChoice> _choices = [];
    private readonly ListCollectionView _view;
    private readonly ListCollectionView _workView;
    private readonly ListCollectionView _vaultPersonalView;
    private readonly ListCollectionView _vaultWorkView;
    private readonly ListCollectionView _entryView;
    private NoteScope _activeScope = NoteScope.Personal;
    private NoteScope _lastNonVaultScope = NoteScope.Personal;
    private bool _isVaultMode => _activeScope is NoteScope.VaultPersonal or NoteScope.VaultWork;
    private bool _isWorkMode => _activeScope == NoteScope.Work;
    private ObservableCollection<NoteVM> ActiveNotes => NotesForScope(_activeScope);
    private ListCollectionView ActiveView => ViewForScope(_activeScope);
    private Database ActiveDatabase => DatabaseForScope(_activeScope);
    private readonly HashSet<NoteVM> _dirty = [];
    private readonly DispatcherTimer _saveTimer = new() { Interval = TimeSpan.FromMilliseconds(500) };
    private readonly DispatcherTimer _previewTimer = new() { Interval = TimeSpan.FromMilliseconds(180) };
    private readonly DispatcherTimer _syncTimer = new() { Interval = TimeSpan.FromSeconds(5) };
    private readonly DispatcherTimer _cardDragScrollTimer = new() { Interval = TimeSpan.FromMilliseconds(35) };
    private FlowDocumentScrollViewer? _dragCardViewer;
    private bool _isSelectingCardText;
    private bool _syncing;
    private bool _syncErrorShown;
    private bool _hasSyncConflict;

    private string? _selKey;          // null = all, "" = general, else repo path
    private bool _suspendSelection;
    private NoteVM? _open;
    private List<string> _repos = [];

    public MainWindow() : this(new Database()) { }

    public MainWindow(Database db)
    {
        InitializeComponent();
        Native.Attach(this, cloakUntilRendered: true);

        _db = db;
        _workDb = new Database(remoteEnabled: true, databaseFileName: "work_notebook.db", scope: NoteScope.Work);
        _vaultPersonalDb = new Database(remoteEnabled: true, databaseFileName: "vault_personal.db", scope: NoteScope.VaultPersonal);
        _vaultWorkDb = new Database(remoteEnabled: true, databaseFileName: "vault_work.db", scope: NoteScope.VaultWork);
        SyncRemoteDbSettings();

        // Pre-clamp and restore window size, position, and maximized state BEFORE the window is displayed
        InitWindowBoundsPreLoad();
        RestoreWindowBounds();

        PreloadScope(_db, _notes, NoteScope.Personal);
        PreloadScope(_workDb, _workNotes, NoteScope.Work);
        PreloadScope(_vaultPersonalDb, _vaultPersonalNotes, NoteScope.VaultPersonal);
        PreloadScope(_vaultWorkDb, _vaultWorkNotes, NoteScope.VaultWork);

        _view = CreateNoteView(_notes, NoteFilter);
        _workView = CreateNoteView(_workNotes, ScopedNoteFilter);
        _vaultPersonalView = CreateNoteView(_vaultPersonalNotes, ScopedNoteFilter);
        _vaultWorkView = CreateNoteView(_vaultWorkNotes, ScopedNoteFilter);
        NotesList.ItemsSource = _view;

        _entryView = new ListCollectionView(_entries) { Filter = EntryFilter };
        RepoList.ItemsSource = _entryView;
        RepoCombo.ItemsSource = _choices;

        // Populate base sidebar entries ("All notes", "General") and counts immediately
        RebuildEntries();

        _saveTimer.Tick += (_, _) => FlushSaves();
        _previewTimer.Tick += (_, _) => { _previewTimer.Stop(); UpdatePreview(); };
        _syncTimer.Tick += async (_, _) => await RefreshFromServerAsync();
        _cardDragScrollTimer.Tick += CardDragScrollTimer_Tick;
        Loaded += async (_, _) =>
        {
            if (!string.IsNullOrWhiteSpace(_db.LastRemoteError))
                DarkDialog.Show(this, "Central database unavailable", _db.LastRemoteError);
            await ReloadReposAsync();
            _syncTimer.Start();
        };
        Closing += (_, e) =>
        {
            _syncTimer.Stop();
            FlushSaves();
            if (_dirty.Count > 0)
            {
                bool discard = DarkDialog.Show(
                    this,
                    "Unsaved changes",
                    "Some edits could not reach xServer. Discard those pending edits and close?",
                    "Discard",
                    "Keep editing");
                if (!discard)
                {
                    e.Cancel = true;
                    return;
                }
                _dirty.Clear();
            }
            SaveWindowBounds();
        };
    }

    private ObservableCollection<NoteVM> NotesForScope(NoteScope scope) => scope switch
    {
        NoteScope.Work => _workNotes,
        NoteScope.VaultPersonal => _vaultPersonalNotes,
        NoteScope.VaultWork => _vaultWorkNotes,
        _ => _notes
    };

    private ListCollectionView ViewForScope(NoteScope scope) => scope switch
    {
        NoteScope.Work => _workView,
        NoteScope.VaultPersonal => _vaultPersonalView,
        NoteScope.VaultWork => _vaultWorkView,
        _ => _view
    };

    private Database DatabaseForScope(NoteScope scope) => scope switch
    {
        NoteScope.Work => _workDb,
        NoteScope.VaultPersonal => _vaultPersonalDb,
        NoteScope.VaultWork => _vaultWorkDb,
        _ => _db
    };

    private NoteScope ScopeForNote(NoteVM n)
    {
        if (_workNotes.Contains(n)) return NoteScope.Work;
        if (_vaultPersonalNotes.Contains(n)) return NoteScope.VaultPersonal;
        if (_vaultWorkNotes.Contains(n)) return NoteScope.VaultWork;
        if (_notes.Contains(n)) return NoteScope.Personal;
        return _activeScope;
    }

    private void PreloadScope(Database db, ObservableCollection<NoteVM> target, NoteScope scope)
    {
        try
        {
            var defaultRepo = Database.DefaultRepoPathFor(scope);
            foreach (var n in db.LoadNotes())
            {
                if (scope != NoteScope.Personal && string.IsNullOrEmpty(n.RepoPath))
                    n.SetRepoPathSilent(defaultRepo);
                n.Edited += OnEdited;
                target.Add(n);
            }
        }
        catch { }
    }

    private static ListCollectionView CreateNoteView(ObservableCollection<NoteVM> source, Predicate<object> filter)
    {
        var view = new ListCollectionView(source) { Filter = filter };
        view.SortDescriptions.Add(new SortDescription(nameof(NoteVM.CreatedUtc), ListSortDirection.Descending));
        return view;
    }

    private void SyncRemoteDbSettings()
    {
        var url = _db.GetSetting("notes_api_url");
        var key = _db.GetSecret("notes_api_key");
        foreach (var remoteDb in new[] { _workDb, _vaultPersonalDb, _vaultWorkDb })
        {
            remoteDb.SetSetting("notes_api_url", url);
            remoteDb.SetSecret("notes_api_key", key);
        }
        NoteImageStore.Configure(url, key);
    }

    private async Task RefreshFromServerAsync()
    {
        SyncRemoteDbSettings();
        var activeScope = _activeScope;
        var activeDb = DatabaseForScope(activeScope);
        var activeNotes = NotesForScope(activeScope);
        if (!activeDb.IsRemoteConfigured || _syncing || _dirty.Count > 0 || _open != null || Keyboard.FocusedElement is TextBox || _isSelectingCardText)
            return;

        _syncing = true;
        try
        {
            await NoteImageStore.UploadPendingImagesAsync();
            var refreshed = await Task.Run(() => activeDb.LoadNotes());
            if (_dirty.Count > 0 || _open != null || Keyboard.FocusedElement is TextBox || _isSelectingCardText || _activeScope != activeScope)
                return;

            if (!string.IsNullOrWhiteSpace(activeDb.LastRemoteError))
            {
                if (!_syncErrorShown)
                {
                    _syncErrorShown = true;
                    DarkDialog.Show(this, "Central database connection lost", activeDb.LastRemoteError);
                }
                return;
            }

            _syncErrorShown = false;
            MergeServerNotes(activeNotes, refreshed, activeScope);
        }
        catch (Exception ex)
        {
            if (!_syncErrorShown)
            {
                _syncErrorShown = true;
                DarkDialog.Show(this, "Notes sync failed", ex.Message);
            }
        }
        finally
        {
            _syncing = false;
        }
    }

    private void MergeServerNotes(ObservableCollection<NoteVM> target, List<NoteVM> refreshed, NoteScope scope)
    {
        var defaultRepo = Database.DefaultRepoPathFor(scope);
        foreach (var note in refreshed)
        {
            if (scope != NoteScope.Personal && string.IsNullOrEmpty(note.RepoPath))
                note.SetRepoPathSilent(defaultRepo);
        }

        var refreshedById = refreshed.ToDictionary(n => n.Id);
        bool structureChanged = false;

        for (int i = target.Count - 1; i >= 0; i--)
        {
            var existing = target[i];
            if (existing.Id > 0 && !refreshedById.ContainsKey(existing.Id))
            {
                existing.Edited -= OnEdited;
                target.RemoveAt(i);
                structureChanged = true;
            }
        }

        var existingById = target.Where(n => n.Id > 0).ToDictionary(n => n.Id);
        foreach (var serverNote in refreshed)
        {
            if (existingById.TryGetValue(serverNote.Id, out var existing))
            {
                if (existing.RepoPath != serverNote.RepoPath || existing.CreatedUtc != serverNote.CreatedUtc)
                    structureChanged = true;

                existing.ApplyServerState(
                    serverNote.RepoPath,
                    serverNote.Title,
                    serverNote.Body,
                    serverNote.CreatedUtc,
                    serverNote.UpdatedUtc);
            }
            else
            {
                serverNote.Edited += OnEdited;
                target.Add(serverNote);
                structureChanged = true;
            }
        }

        if (structureChanged)
        {
            double savedOffset = DashScroll.VerticalOffset;
            if (scope == NoteScope.Personal) Recount();
            RefreshView();
            DashScroll.ScrollToVerticalOffset(savedOffset);
        }
    }

    private void InitWindowBoundsPreLoad()
    {
        try
        {
            var wa = SystemParameters.WorkArea;
            if (wa.Width > 0 && wa.Height > 0)
            {
                Width = Math.Min(1020, Math.Max(MinWidth, wa.Width * 0.75));
                Height = Math.Min(620, Math.Max(MinHeight, wa.Height * 0.78));
            }
        }
        catch { }
    }

    private void RestoreWindowBounds()
    {
        if (_db == null) return;
        try
        {
            var vLeft = SystemParameters.VirtualScreenLeft;
            var vTop = SystemParameters.VirtualScreenTop;
            var vWidth = SystemParameters.VirtualScreenWidth;
            var vHeight = SystemParameters.VirtualScreenHeight;

            if (double.TryParse(_db.GetSetting("win_width"), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var sw) && sw >= MinWidth && sw <= vWidth)
                Width = sw;
            if (double.TryParse(_db.GetSetting("win_height"), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var sh) && sh >= MinHeight && sh <= vHeight)
                Height = sh;
            if (double.TryParse(_db.GetSetting("win_left"), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var sl) &&
                double.TryParse(_db.GetSetting("win_top"), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var st))
            {
                if (sl >= vLeft - 20 && sl + Width <= vLeft + vWidth + 20 &&
                    st >= vTop - 20 && st + Height <= vTop + vHeight + 20)
                {
                    Left = sl;
                    Top = st;
                }
            }

            var maxStr = _db.GetSetting("win_maximized");
            if (maxStr == "1" || string.Equals(maxStr, "true", StringComparison.OrdinalIgnoreCase))
            {
                WindowState = WindowState.Maximized;
            }
        }
        catch { }
    }

    private void SaveWindowBounds()
    {
        if (_db == null) return;
        try
        {
            bool isMax = WindowState == WindowState.Maximized;
            _db.SetSetting("win_maximized", isMax ? "1" : "0");

            Rect bounds = (WindowState == WindowState.Normal) ? new Rect(Left, Top, Width, Height) : RestoreBounds;
            if (bounds.Width >= MinWidth && bounds.Height >= MinHeight)
            {
                _db.SetSetting("win_width", bounds.Width.ToString(System.Globalization.CultureInfo.InvariantCulture));
                _db.SetSetting("win_height", bounds.Height.ToString(System.Globalization.CultureInfo.InvariantCulture));
                _db.SetSetting("win_left", bounds.Left.ToString(System.Globalization.CultureInfo.InvariantCulture));
                _db.SetSetting("win_top", bounds.Top.ToString(System.Globalization.CultureInfo.InvariantCulture));
            }
        }
        catch { }
    }

    // ---------- startup ----------
    private async Task ReloadReposAsync()
    {
        var root = _db.GetSetting("root");
        if (root.Length == 0 || !Directory.Exists(root))
        {
            NoRootText.Text = root.Length == 0
                ? "Select your main repos directory in Settings to list git projects."
                : "The repos directory no longer exists. Choose it again in Settings.";
            NoRootHint.Visibility = Visibility.Visible;
            _repos = [];
        }
        else
        {
            NoRootHint.Visibility = Visibility.Collapsed;
            ScanBar.Visibility = Visibility.Visible;
            RebuildEntries(); // show notes immediately, repos fill in when the scan is done
            try
            {
                _repos = await Task.Run(() => RepoScanner.Scan(root));
                DetectAndMigrateRenamedProjects();
            }
            finally { ScanBar.Visibility = Visibility.Collapsed; }
        }
        RebuildEntries();

        // Publish the desktop's authoritative local Git repository list for mobile clients.
        if (_db.IsRemoteConfigured)
        {
            try
            {
                await Task.Run(() => _db.PublishRepositories(_repos));
            }
            catch (Exception ex)
            {
                DarkDialog.Show(this, "Could not publish project list", ex.Message);
            }
        }
    }

    private void DetectAndMigrateRenamedProjects()
    {
        var knownFingerprints = _db.GetAllFingerprints();
        var migratedProjects = new List<(string OldName, string NewName)>();

        foreach (var r in _repos)
        {
            var currentFp = ProjectFingerprint.Compute(r);
            if (string.IsNullOrEmpty(currentFp)) continue;

            if (knownFingerprints.TryGetValue(r, out var storedFp))
            {
                if (storedFp != currentFp)
                    _db.SaveFingerprint(r, currentFp);
                continue;
            }

            string? matchedOldPath = null;
            foreach (var (oldPath, oldFp) in knownFingerprints)
            {
                if (!Directory.Exists(oldPath) && string.Equals(oldFp, currentFp, StringComparison.OrdinalIgnoreCase))
                {
                    matchedOldPath = oldPath;
                    break;
                }
            }

            if (matchedOldPath == null)
            {
                var missingNotePaths = _notes.Select(n => n.RepoPath)
                    .Where(p => p.Length > 0 && !Directory.Exists(p))
                    .Distinct(StringComparer.OrdinalIgnoreCase);

                foreach (var missingPath in missingNotePaths)
                {
                    var missingFp = ProjectFingerprint.Compute(missingPath);
                    if (!string.IsNullOrEmpty(missingFp) && string.Equals(missingFp, currentFp, StringComparison.OrdinalIgnoreCase))
                    {
                        matchedOldPath = missingPath;
                        break;
                    }
                }
            }

            if (matchedOldPath != null)
            {
                bool migrated = _db.MigrateProjectNotes(matchedOldPath, r);
                if (migrated)
                {
                    foreach (var note in _notes.Where(n => string.Equals(n.RepoPath, matchedOldPath, StringComparison.OrdinalIgnoreCase)))
                    {
                        note.RepoPath = r;
                    }
                    migratedProjects.Add((Path.GetFileName(matchedOldPath.TrimEnd('\\', '/')), Path.GetFileName(r.TrimEnd('\\', '/'))));
                }
            }

            _db.SaveFingerprint(r, currentFp);
        }

        if (migratedProjects.Count > 0)
        {
            var msg = string.Join("\n", migratedProjects.Select(m => $" • '{m.OldName}' → '{m.NewName}'"));
            DarkDialog.Show(this, "Renamed projects detected", $"The following renamed projects were detected and their notes were automatically migrated:\n\n{msg}");
        }
    }

    private void RebuildEntries()
    {
        var prev = _selKey;
        _suspendSelection = true;
        _entries.Clear();
        _choices.Clear();

        _entries.Add(new SidebarEntry { Key = null, Display = "All notes", Dot = RepoColors.For("").accent });
        _entries.Add(new SidebarEntry { Key = "", Display = "General", Dot = RepoColors.For("").accent });
        _choices.Add(new RepoChoice { Path = "", Name = "General (no project)" });

        foreach (var r in _repos)
        {
            var name = Path.GetFileName(r);
            _entries.Add(new SidebarEntry { Key = r, Display = name, IsRepo = true, Dot = RepoColors.For(r).accent });
            _choices.Add(new RepoChoice { Path = r, Name = name });
        }

        var known = new HashSet<string>(_repos, StringComparer.OrdinalIgnoreCase);
        foreach (var o in _notes.Select(n => n.RepoPath).Where(p => p.Length > 0 && !known.Contains(p) && !Database.IsSpecialScopeRepoPath(p)).Distinct(StringComparer.OrdinalIgnoreCase))
        {
            var name = Path.GetFileName(o.TrimEnd('\\', '/'));
            _entries.Add(new SidebarEntry { Key = o, Display = name, IsRepo = true, Missing = true, Dot = RepoColors.For(o).accent });
            _choices.Add(new RepoChoice { Path = o, Name = name + " (not found)" });
        }

        Recount();
        _suspendSelection = false;
        RepoList.SelectedItem = _entries.FirstOrDefault(e => e.Key == prev) ?? _entries[0];
        RefreshView();
    }

    private void Recount()
    {
        foreach (var e in _entries)
            e.Count = e.Key == null ? _notes.Count : _notes.Count(n => string.Equals(n.RepoPath, e.Key, StringComparison.OrdinalIgnoreCase));
    }

    // ---------- sidebar selection ----------
    private void RepoList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suspendSelection) return;
        if (_open != null) CloseEditor();

        if (RepoList.SelectedItem is SidebarEntry entry)
            _selKey = entry.Key;

        ViewTitle.Text = _activeScope switch
        {
            NoteScope.Work => "Work Notebook",
            NoteScope.VaultPersonal => "Vault — Personal",
            NoteScope.VaultWork => "Vault — Work",
            _ => (RepoList.SelectedItem as SidebarEntry)?.Display ?? "All notes"
        };
        RefreshView();
    }

    private void SearchBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        bool hasText = !string.IsNullOrEmpty(SearchBox.Text);
        if (SearchHint != null) SearchHint.Visibility = hasText ? Visibility.Collapsed : Visibility.Visible;
        if (ClearSearchButton != null) ClearSearchButton.Visibility = hasText ? Visibility.Visible : Visibility.Collapsed;
        RefreshView();
    }

    private void SearchBox_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape && !string.IsNullOrEmpty(SearchBox.Text))
        {
            SearchBox.Text = "";
            e.Handled = true;
        }
    }

    private void ClearSearch_Click(object sender, RoutedEventArgs e)
    {
        SearchBox.Text = "";
        SearchBox.Focus();
    }

    private void RefreshView()
    {
        ActiveView.Refresh();
    }

    private bool NoteFilter(object o)
    {
        var n = (NoteVM)o;
        var q = SearchBox.Text.Trim();

        // If sidebar selection is specific repo / general
        if (_selKey != null && !string.Equals(n.RepoPath, _selKey, StringComparison.OrdinalIgnoreCase))
            return false;

        return q.Length == 0
            || n.Title.Contains(q, StringComparison.OrdinalIgnoreCase)
            || n.Body.Contains(q, StringComparison.OrdinalIgnoreCase)
            || n.RepoLabel.Contains(q, StringComparison.OrdinalIgnoreCase);
    }

    private bool ScopedNoteFilter(object o)
    {
        var n = (NoteVM)o;
        var q = SearchBox.Text.Trim();
        return q.Length == 0
            || n.Title.Contains(q, StringComparison.OrdinalIgnoreCase)
            || n.Body.Contains(q, StringComparison.OrdinalIgnoreCase)
            || n.RepoLabel.Contains(q, StringComparison.OrdinalIgnoreCase);
    }

    private bool EntryFilter(object o)
    {
        var e = (SidebarEntry)o;
        var q = RepoFilter?.Text.Trim() ?? "";
        return !e.IsRepo || q.Length == 0 || e.Display.Contains(q, StringComparison.OrdinalIgnoreCase);
    }

    private void RepoFilter_TextChanged(object sender, TextChangedEventArgs e)
    {
        _entryView.Refresh();
    }

    // ---------- persistence ----------
    private void OnEdited(NoteVM n)
    {
        _dirty.Add(n);
        _saveTimer.Stop();
        _saveTimer.Start();
    }

    private void FlushSaves()
    {
        _saveTimer.Stop();
        if (_db == null) return;
        bool savedAny = false;
        try
        {
            foreach (var n in _dirty.ToList())
            {
                var db = DatabaseForScope(ScopeForNote(n));
                if (n.Id <= 0)
                {
                    if (string.IsNullOrWhiteSpace(n.Title) && string.IsNullOrWhiteSpace(n.Body))
                        continue;
                    n.Id = db.Insert(n);
                    savedAny = true;
                    continue;
                }
                db.Update(n);
                savedAny = true;
            }
            _dirty.Clear();
            _hasSyncConflict = false;
            Recount();
            if (savedAny && ActiveDatabase.IsRemoteConfigured)
                _ = RefreshFromServerAsync();
        }
        catch (Exception ex)
        {
            _hasSyncConflict = ex is NoteConflictException;
            DarkDialog.Show(this, "Could not save note", ex.Message);
        }
    }

    // ---------- notes ----------
    private async void ManualSync_Click(object sender, RoutedEventArgs e)
    {
        if (_syncing) return;
        SyncRemoteDbSettings();
        var activeDb = ActiveDatabase;
        if (!activeDb.IsRemoteConfigured)
        {
            DarkDialog.Show(this, "xServer is not configured", "Open Settings, enter the xServer Notes API URL and key, and test the connection first.");
            return;
        }

        FlushSaves();
        if (_dirty.Count > 0)
        {
            DarkDialog.Show(this, "Sync unavailable", "Resolve the pending note save before syncing.");
            return;
        }

        if (_open != null) CloseEditor();
        Keyboard.ClearFocus();
        SyncButton.IsEnabled = false;
        SyncButton.Content = "Syncing…";
        try
        {
            await RefreshFromServerAsync();
        }
        finally
        {
            SyncButton.Content = "⟳  Sync";
            SyncButton.IsEnabled = true;
        }
    }

    private void PersonalMode_Click(object sender, RoutedEventArgs e) => SwitchScope(NoteScope.Personal);

    private void WorkMode_Click(object sender, RoutedEventArgs e) => SwitchScope(NoteScope.Work);

    private void VaultMode_Click(object sender, RoutedEventArgs e)
    {
        if (_isVaultMode) return;
        _lastNonVaultScope = _activeScope;
        SwitchScope(_activeScope == NoteScope.Work ? NoteScope.VaultWork : NoteScope.VaultPersonal);
    }

    private void VaultPersonalMode_Click(object sender, RoutedEventArgs e) => SwitchScope(NoteScope.VaultPersonal);

    private void VaultWorkMode_Click(object sender, RoutedEventArgs e) => SwitchScope(NoteScope.VaultWork);

    private void SwitchScope(NoteScope targetScope)
    {
        if (_activeScope == targetScope) return;
        if (_open != null) CloseEditor();
        FlushSaves();
        if (_dirty.Count > 0)
        {
            DarkDialog.Show(this, "Switch unavailable", "Resolve the pending note save before switching views.");
            return;
        }

        StopCardDragScroll();
        _activeScope = targetScope;
        if (!_isVaultMode) _lastNonVaultScope = targetScope;

        SearchBox.Clear();
        _selKey = null;
        ApplyActiveScopeUi();

        if (_activeScope == NoteScope.Personal)
            RebuildEntries();

        RefreshView();
        DashScroll.ScrollToTop();
    }

    private void ApplyActiveScopeUi()
    {
        ProjectsSection.Visibility = _activeScope == NoteScope.Personal ? Visibility.Visible : Visibility.Collapsed;
        UpdateSidebarNavigationUi();

        if (_isVaultMode)
        {
            VaultModeSwitch.Visibility = Visibility.Visible;

            bool isVaultWork = _activeScope == NoteScope.VaultWork;
            VaultPersonalButton.Background = isVaultWork ? Brushes.Transparent : (Brush)FindResource("AccentSoft");
            VaultPersonalButton.Foreground = isVaultWork ? (Brush)FindResource("TextDim") : (Brush)FindResource("Accent");
            VaultPersonalButton.FontWeight = isVaultWork ? FontWeights.Normal : FontWeights.SemiBold;

            VaultWorkButton.Background = isVaultWork ? (Brush)FindResource("AccentSoft") : Brushes.Transparent;
            VaultWorkButton.Foreground = isVaultWork ? (Brush)FindResource("Accent") : (Brush)FindResource("TextDim");
            VaultWorkButton.FontWeight = isVaultWork ? FontWeights.SemiBold : FontWeights.Normal;
        }
        else
        {
            VaultModeSwitch.Visibility = Visibility.Collapsed;
        }

        ViewTitle.Text = _activeScope switch
        {
            NoteScope.Work => "Work Notebook",
            NoteScope.VaultPersonal => "Vault — Personal",
            NoteScope.VaultWork => "Vault — Work",
            _ => (RepoList.SelectedItem as SidebarEntry)?.Display ?? "All notes"
        };

        NotesList.ItemsSource = ActiveView;
    }

    private void UpdateSidebarNavigationUi()
    {
        SetSidebarButtonActive(PersonalNotebookButton, _activeScope == NoteScope.Personal);
        SetSidebarButtonActive(WorkNotebookButton, _activeScope == NoteScope.Work);
        SetSidebarButtonActive(VaultButton, _isVaultMode);
    }

    private void SetSidebarButtonActive(Button button, bool active)
    {
        if (active)
        {
            button.Background = (Brush)FindResource("AccentSoft");
            button.Foreground = (Brush)FindResource("Accent");
            button.BorderBrush = (Brush)FindResource("Accent");
        }
        else
        {
            button.ClearValue(BackgroundProperty);
            button.ClearValue(ForegroundProperty);
            button.ClearValue(BorderBrushProperty);
        }
    }

    private void NewNote_Click(object sender, RoutedEventArgs e)
    {
        if (!string.IsNullOrEmpty(SearchBox.Text))
        {
            SearchBox.Text = "";
        }
        NewNote();
    }

    private void NewNote()
    {
        if (_db == null) return;
        if (_open != null) CloseEditor();
        var defaultRepo = _activeScope == NoteScope.Personal ? (_selKey ?? "") : Database.DefaultRepoPathFor(_activeScope);
        var n = new NoteVM { RepoPath = defaultRepo, IsEditingInline = true };
        n.Edited += OnEdited;
        ActiveNotes.Add(n);
        if (_activeScope == NoteScope.Personal) Recount();
        RefreshView();
        DashScroll.ScrollToTop();
        Dispatcher.BeginInvoke(DispatcherPriority.Loaded, () =>
        {
            if (NotesList.ItemContainerGenerator.ContainerFromItem(n) is ContentPresenter cp)
            {
                cp.ApplyTemplate();
                (cp.ContentTemplate.FindName("InlineBodyBox", cp) as TextBox)?.Focus();
            }
        });
    }

    private void DashScroll_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        UpdateNotesGridWidth();
    }

    private void UpdateNotesGridWidth()
    {
        const double cardWidthWithMargin = 312.0; // 300 width + 12 right margin
        double available = DashScroll.ActualWidth;
        if (available <= 0) return;

        double contentWidth = available - 24.0;
        if (DashScroll.ComputedVerticalScrollBarVisibility == Visibility.Visible)
        {
            contentWidth -= SystemParameters.VerticalScrollBarWidth;
        }

        int cols = (int)Math.Floor(contentWidth / cardWidthWithMargin);
        if (cols < 1) cols = 1;

        NotesList.Width = cols * cardWidthWithMargin;
    }

    private static NoteVM? NoteOf(object sender) => (sender as FrameworkElement)?.DataContext as NoteVM;

    private async void CopyNote_Click(object sender, RoutedEventArgs e)
    {
        if (NoteOf(sender) is not { } n) return;

        string textToCopy = "";
        if (n.IsEditingInline)
        {
            textToCopy = n.Body ?? "";
        }
        else
        {
            if (sender is FrameworkElement fe && FindTemplateElement<FlowDocumentScrollViewer>(fe, "CardViewer") is { Document: { } cardDoc })
            {
                var tr = new System.Windows.Documents.TextRange(cardDoc.ContentStart, cardDoc.ContentEnd);
                textToCopy = tr.Text.TrimEnd('\r', '\n');
            }
            else
            {
                var fallbackDoc = MarkdownRenderer.Render(n.Body ?? "");
                var tr = new System.Windows.Documents.TextRange(fallbackDoc.ContentStart, fallbackDoc.ContentEnd);
                textToCopy = tr.Text.TrimEnd('\r', '\n');
            }
        }

        if (string.IsNullOrEmpty(textToCopy) && !string.IsNullOrEmpty(n.Title))
            textToCopy = n.Title;

        await PerformCopyFeedback(sender as Button, textToCopy, "Copied displayed text!");
    }

    private async void CopyNote_MouseRightButtonUp(object sender, MouseButtonEventArgs e)
    {
        e.Handled = true;
        if (NoteOf(sender) is not { } n) return;

        var textToCopy = n.Body ?? "";
        if (string.IsNullOrEmpty(textToCopy) && !string.IsNullOrEmpty(n.Title))
            textToCopy = n.Title;

        await PerformCopyFeedback(sender as Button, textToCopy, "Copied raw markdown!");
    }

    private static async Task PerformCopyFeedback(Button? btn, string textToCopy, string feedbackTip)
    {
        try
        {
            Clipboard.SetDataObject(textToCopy, true);
            if (btn != null)
            {
                var oldTip = btn.ToolTip;
                btn.ToolTip = feedbackTip;
                await Task.Delay(1500);
                btn.ToolTip = oldTip;
            }
        }
        catch { }
    }

    private void ToggleQuickEdit_Click(object sender, RoutedEventArgs e)
    {
        if (NoteOf(sender) is { } n) n.IsEditingInline = !n.IsEditingInline;
    }

    private void EmptyNote_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (NoteOf(sender) is { } n)
        {
            n.IsEditingInline = true;
            e.Handled = true;
        }
    }

    private void InlineBodyBox_IsVisibleChanged(object sender, DependencyPropertyChangedEventArgs e)
    {
        if (sender is TextBox tb && tb.IsVisible)
        {
            tb.Focus();
            tb.CaretIndex = tb.Text.Length;
        }
    }

    private void InlineBodyBox_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape || (e.Key == Key.Enter && (Keyboard.Modifiers & ModifierKeys.Control) != 0))
        {
            if (NoteOf(sender) is { } n)
            {
                n.IsEditingInline = false;
                e.Handled = true;
            }
        }
    }

    private static T? FindTemplateElement<T>(FrameworkElement element, string name) where T : FrameworkElement
    {
        DependencyObject? current = element;
        while (current != null && current is not ContentPresenter)
        {
            current = System.Windows.Media.VisualTreeHelper.GetParent(current);
        }
        if (current is ContentPresenter cp && cp.ContentTemplate?.FindName(name, cp) is T found)
        {
            return found;
        }
        return null;
    }

    private void Expand_Click(object sender, RoutedEventArgs e)
    {
        if (NoteOf(sender) is { } n) OpenEditor(n);
    }

    private void Undo_Click(object sender, RoutedEventArgs e) => NoteOf(sender)?.UndoAi();

    private void CancelAi_Click(object sender, RoutedEventArgs e) => NoteOf(sender)?.AiCts?.Cancel();

    private void Delete_Click(object sender, RoutedEventArgs e)
    {
        if (NoteOf(sender) is not { } n) return;
        bool hasContent = n.Title.Length > 0 || n.Body.Trim().Length > 0;
        if (hasContent && !DarkDialog.Show(this, "Delete this note?",
                $"“{(n.Title.Length > 0 ? n.Title : "Untitled")}” will be permanently deleted.", "Delete", "Cancel"))
            return;
        if (_open == n) CloseEditor();
        n.AiCts?.Cancel();
        _dirty.Remove(n);
        try
        {
            if (n.Id > 0)
                ActiveDatabase.Delete(n.Id, n.ServerUpdatedUtc);
            ActiveNotes.Remove(n);
            if (_activeScope == NoteScope.Personal) Recount();
            RefreshView();
            if (n.Id > 0 && ActiveDatabase.IsRemoteConfigured)
                _ = RefreshFromServerAsync();
        }
        catch (Exception ex)
        {
            DarkDialog.Show(this, "Could not delete note", ex.Message);
        }
    }

    // ---------- large editor ----------
    private void OpenEditor(NoteVM n)
    {
        n.IsEditingInline = false;
        _open = n;
        EditorRoot.DataContext = n;
        RepoCombo.Visibility = _activeScope == NoteScope.Personal ? Visibility.Visible : Visibility.Collapsed;
        CloseEditorButton.Content = _activeScope switch
        {
            NoteScope.Work => "←  Work Notebook",
            NoteScope.VaultPersonal or NoteScope.VaultWork => "←  Vault",
            _ => "←  Dashboard"
        };
        MoveVaultButton.Content = _isVaultMode
            ? (_activeScope == NoteScope.VaultWork ? "↩  Move to Work" : "↩  Move to Dashboard")
            : "🔒  Move to Vault";
        n.PropertyChanged += OnOpenNoteChanged;
        DashboardRoot.Visibility = Visibility.Collapsed;
        EditorRoot.Visibility = Visibility.Visible;
        UpdatePreview();
        EditorBox.Focus();
        EditorBox.CaretIndex = EditorBox.Text.Length;
    }

    private void CloseEditor()
    {
        if (_open == null) return;
        _open.PropertyChanged -= OnOpenNoteChanged;
        _open = null;
        EditorRoot.DataContext = null;
        RepoCombo.Visibility = Visibility.Visible;
        EditorRoot.Visibility = Visibility.Collapsed;
        DashboardRoot.Visibility = Visibility.Visible;
        FlushSaves();
        RefreshView();
    }

    private void CloseEditor_Click(object sender, RoutedEventArgs e) => CloseEditor();

    private void OnOpenNoteChanged(object? s, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(NoteVM.Body)) { _previewTimer.Stop(); _previewTimer.Start(); }
    }

    private void EditorBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (_open != null)
        {
            _previewTimer.Stop();
            _previewTimer.Start();
        }
    }

    private void NoteTextBox_CanPaste(object sender, CanExecuteRoutedEventArgs e)
    {
        if (sender is TextBox tb && !tb.IsReadOnly)
        {
            bool canPaste = false;
            try
            {
                canPaste = NoteImageStore.HasClipboardImage() || Clipboard.ContainsText();
            }
            catch
            {
                // Clipboard may be temporarily locked by another process.
            }
            e.CanExecute = canPaste;
            e.Handled = true;
        }
    }

    private void NoteTextBox_Paste(object sender, ExecutedRoutedEventArgs e)
    {
        if (sender is not TextBox tb || tb.IsReadOnly) return;

        if (NoteImageStore.HasClipboardImage() && PasteImageIntoTextBox(tb))
        {
            e.Handled = true;
            return;
        }

        try
        {
            if (Clipboard.ContainsText())
            {
                tb.Paste();
                e.Handled = true;
            }
        }
        catch
        {
            // Clipboard may be temporarily locked by another process.
        }
    }

    private bool PasteImageIntoTextBox(TextBox tb)
    {
        var imageId = NoteImageStore.SaveClipboardImage();
        if (string.IsNullOrEmpty(imageId)) return false;

        int selStart = tb.SelectionStart;
        int selLength = tb.SelectionLength;
        string text = tb.Text ?? "";
        string prefix = selStart > 0 && text[selStart - 1] != '\n' ? "\n" : "";
        string markdown = $"{prefix}![image](xreponotes-image://{imageId})\n";

        tb.Text = string.Concat(text.AsSpan(0, selStart), markdown, text.AsSpan(selStart + selLength));
        tb.CaretIndex = selStart + markdown.Length;

        _ = NoteImageStore.UploadImageAsync(imageId);
        return true;
    }

    private void UpdatePreview()
    {
        var text = _open != null ? EditorBox.Text : "";
        Preview.Document = MarkdownRenderer.Render(text);
    }

    private void Window_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape && _open != null && !RepoCombo.IsDropDownOpen) { CloseEditor(); e.Handled = true; }
        else if (e.Key == Key.N && Keyboard.Modifiers == ModifierKeys.Control) { NewNote(); e.Handled = true; }
    }

    // ---------- settings ----------
    private async void Settings_Click(object sender, RoutedEventArgs e)
    {
        if (_db == null) return;
        FlushSaves();

        var w = new SettingsWindow(_db) { Owner = this };
        if (w.ShowDialog() == true)
        {
            SyncRemoteDbSettings();
            await ReloadReposAsync();
            if (_open == null && _dirty.Count > 0 && !_hasSyncConflict)
            {
                var refreshed = await Task.Run(() => ActiveDatabase.LoadNotes());
                if (string.IsNullOrWhiteSpace(ActiveDatabase.LastRemoteError))
                {
                    _dirty.Clear();
                    MergeServerNotes(ActiveNotes, refreshed, _activeScope);
                }
                else
                {
                    DarkDialog.Show(this, "Notes still need syncing", ActiveDatabase.LastRemoteError);
                }
            }
            else if (_open == null && _dirty.Count == 0)
            {
                await RefreshFromServerAsync();
            }
        }
    }

    // ---------- card drag-selection scroll & vault move ----------
    private static readonly PropertyInfo? SelectionAnchorProp =
        typeof(TextSelection).GetProperty("AnchorPosition", BindingFlags.Instance | BindingFlags.NonPublic);
    private static readonly PropertyInfo? SelectionTextViewProp =
        typeof(TextSelection).GetProperty("TextView", BindingFlags.Instance | BindingFlags.NonPublic);
    private static readonly Type? TextViewInterfaceType =
        typeof(TextSelection).Assembly.GetType("System.Windows.Documents.ITextView");
    private static readonly PropertyInfo? TextViewRenderScopeProp =
        TextViewInterfaceType?.GetProperty("RenderScope");
    private static readonly MethodInfo? TextViewGetPosMethod =
        TextViewInterfaceType?.GetMethod("GetTextPositionFromPoint");

    private void CardViewer_PreviewMouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ChangedButton != MouseButton.Left) return;
        if (e.OriginalSource is DependencyObject src && FindVisualParent<System.Windows.Controls.Primitives.ScrollBar>(src) != null)
            return;

        _dragCardViewer = sender as FlowDocumentScrollViewer;
        _isSelectingCardText = _dragCardViewer != null;
        _cardDragScrollTimer.Stop();
    }

    private void CardViewer_PreviewMouseMove(object sender, MouseEventArgs e)
    {
        if (!_isSelectingCardText || _dragCardViewer != sender) return;
        if (e.LeftButton != MouseButtonState.Pressed)
        {
            StopCardDragScroll();
            return;
        }

        var pos = e.GetPosition(_dragCardViewer);
        if (pos.Y < 8 || pos.Y > _dragCardViewer.ActualHeight - 8)
        {
            if (!_cardDragScrollTimer.IsEnabled)
                _cardDragScrollTimer.Start();
        }
        else
        {
            _cardDragScrollTimer.Stop();
        }
    }

    private void CardViewer_PreviewMouseLeftButtonUp(object sender, MouseButtonEventArgs e) => StopCardDragScroll();

    private void CardViewer_LostMouseCapture(object sender, MouseEventArgs e)
    {
        if (Mouse.LeftButton != MouseButtonState.Pressed)
            StopCardDragScroll();
    }

    private void CardViewer_RequestBringIntoView(object sender, RequestBringIntoViewEventArgs e)
    {
        e.Handled = true;
    }

    private void StopCardDragScroll()
    {
        _cardDragScrollTimer.Stop();
        _isSelectingCardText = false;
        _dragCardViewer = null;
    }

    private void CardDragScrollTimer_Tick(object? sender, EventArgs e)
    {
        if (_dragCardViewer == null || Mouse.LeftButton != MouseButtonState.Pressed)
        {
            StopCardDragScroll();
            return;
        }

        var sv = FindVisualChild<ScrollViewer>(_dragCardViewer);
        if (sv == null || sv.ScrollableHeight <= 0)
        {
            _cardDragScrollTimer.Stop();
            return;
        }

        var pos = Mouse.GetPosition(_dragCardViewer);
        double delta;
        if (pos.Y < 8)
            delta = -Math.Clamp((8 - pos.Y) * 0.45, 6, 28);
        else if (pos.Y > _dragCardViewer.ActualHeight - 8)
            delta = Math.Clamp((pos.Y - (_dragCardViewer.ActualHeight - 8)) * 0.45, 6, 28);
        else
        {
            _cardDragScrollTimer.Stop();
            return;
        }

        double newOffset = Math.Clamp(sv.VerticalOffset + delta, 0, sv.ScrollableHeight);
        if (Math.Abs(newOffset - sv.VerticalOffset) > 0.1)
        {
            sv.ScrollToVerticalOffset(newOffset);
            sv.UpdateLayout();
        }
        ExtendSelectionToMouse(_dragCardViewer, sv, pos.Y < 8);
    }

    private static void ExtendSelectionToMouse(FlowDocumentScrollViewer viewer, ScrollViewer sv, bool draggingUp)
    {
        try
        {
            var sel = viewer.Selection;
            var anchor = (SelectionAnchorProp?.GetValue(sel) as TextPointer) ?? sel.Start;
            var textView = SelectionTextViewProp?.GetValue(sel);
            if (textView == null || anchor == null || TextViewRenderScopeProp == null || TextViewGetPosMethod == null)
                return;

            if (TextViewRenderScopeProp.GetValue(textView) is not UIElement renderScope)
                return;

            var pt = Mouse.GetPosition(renderScope);
            pt.X = Math.Clamp(pt.X, 0, Math.Max(0, renderScope.RenderSize.Width - 1));
            double minY = sv.VerticalOffset + 2;
            double maxY = Math.Max(minY, sv.VerticalOffset + sv.ViewportHeight - 2);
            pt.Y = draggingUp
                ? Math.Clamp(pt.Y, 0, minY)
                : Math.Clamp(pt.Y, minY, Math.Min(maxY, Math.Max(minY, renderScope.RenderSize.Height - 1)));

            if (TextViewGetPosMethod.Invoke(textView, [pt, true]) is TextPointer target)
                sel.Select(anchor, target);
        }
        catch { }
    }

    private static T? FindVisualParent<T>(DependencyObject? child) where T : DependencyObject
    {
        while (child != null)
        {
            if (child is T match) return match;
            if (child is not Visual and not System.Windows.Media.Media3D.Visual3D) return null;
            child = VisualTreeHelper.GetParent(child);
        }
        return null;
    }

    private static T? FindVisualChild<T>(DependencyObject parent) where T : DependencyObject
    {
        int count = VisualTreeHelper.GetChildrenCount(parent);
        for (int i = 0; i < count; i++)
        {
            var child = VisualTreeHelper.GetChild(parent, i);
            if (child is T match) return match;
            var nested = FindVisualChild<T>(child);
            if (nested != null) return nested;
        }
        return null;
    }

    private void CardViewer_ContextMenuOpening(object sender, ContextMenuEventArgs e)
    {
        if (sender is FlowDocumentScrollViewer viewer && viewer.ContextMenu != null)
        {
            var moveItem = viewer.ContextMenu.Items.OfType<MenuItem>().LastOrDefault();
            if (moveItem != null)
            {
                moveItem.Header = _activeScope switch
                {
                    NoteScope.VaultPersonal => "Move to Dashboard",
                    NoteScope.VaultWork => "Move to Work Notebook",
                    _ => "Move to Vault"
                };
            }
        }
    }

    private void CardMoveVault_Click(object sender, RoutedEventArgs e)
    {
        var note = NoteOf(sender) ?? (sender is MenuItem { Parent: ContextMenu { PlacementTarget: FrameworkElement target } } ? NoteOf(target) : null);
        if (note == null) return;
        MoveNoteBetweenScopes(note);
    }

    private void MoveVault_Click(object sender, RoutedEventArgs e)
    {
        if (_open == null) return;
        var note = _open;
        CloseEditor();
        MoveNoteBetweenScopes(note);
    }

    private void MoveNoteBetweenScopes(NoteVM note)
    {
        note.IsEditingInline = false;
        var fromScope = ScopeForNote(note);
        var toScope = fromScope switch
        {
            NoteScope.Personal => NoteScope.VaultPersonal,
            NoteScope.Work => NoteScope.VaultWork,
            NoteScope.VaultPersonal => NoteScope.Personal,
            NoteScope.VaultWork => NoteScope.Work,
            _ => NoteScope.VaultPersonal
        };

        FlushSaves();
        if (_dirty.Count > 0)
        {
            DarkDialog.Show(this, "Move unavailable", "Resolve the pending note save before moving this note.");
            return;
        }

        var sourceDb = DatabaseForScope(fromScope);
        var targetDb = DatabaseForScope(toScope);
        var sourceNotes = NotesForScope(fromScope);
        var targetNotes = NotesForScope(toScope);
        var targetRepoPath = toScope switch
        {
            NoteScope.VaultPersonal or NoteScope.VaultWork => Database.EncodeVaultRepoPath(toScope, note.RepoPath),
            NoteScope.Personal or NoteScope.Work => Database.RestoreRepoPathFromVault(toScope, note.RepoPath),
            _ => Database.DefaultRepoPathFor(toScope)
        };

        var previousRepoPath = note.RepoPath;
        note.SetRepoPathSilent(targetRepoPath);
        try
        {
            if (note.Id > 0)
            {
                if (targetDb.IsRemoteConfigured)
                {
                    targetDb.Update(note);
                    targetDb.UpsertLocalCache(note);
                    sourceDb.DeleteLocalOnly(note.Id);
                }
                else
                {
                    long oldId = note.Id;
                    note.Id = targetDb.InsertLocalOnly(note);
                    sourceDb.DeleteLocalOnly(oldId);
                }
            }

            sourceNotes.Remove(note);
            targetNotes.Add(note);
            _dirty.Remove(note);
            if (toScope == NoteScope.Personal)
                RebuildEntries();
            else if (fromScope == NoteScope.Personal)
                Recount();
            RefreshView();
        }
        catch (Exception ex)
        {
            note.SetRepoPathSilent(previousRepoPath);
            DarkDialog.Show(this, "Could not move note", ex.Message);
        }
    }

    // ---------- AI ----------
    private async void Ai_Click(object sender, RoutedEventArgs e)
    {
        if (NoteOf(sender) is not { } n || n.IsBusy) return;

        if (string.IsNullOrWhiteSpace(n.Body))
        {
            DarkDialog.Show(this, "Nothing to format", "Add some text to the note first.");
            return;
        }

        var url = _db.GetSetting("ai_url").Trim();
        var model = _db.GetSetting("ai_model").Trim();
        if (model.Length == 0) model = _db.GetSetting("model").Trim();

        var key = _db.GetSecret("ai_key");
        if (key.Length == 0) key = _db.GetSecret("gemini");
        if (key.Length == 0) key = Environment.GetEnvironmentVariable("GEMINI_API_KEY") ?? "";

        if (string.IsNullOrWhiteSpace(url) && string.IsNullOrWhiteSpace(key))
        {
            if (DarkDialog.Show(this, "AI Configuration Needed",
                "To format notes with AI, please configure your AI server URL or API key in Settings.",
                "Open settings", "Cancel"))
            {
                Settings_Click(this, new RoutedEventArgs());
            }
            return;
        }

        if (!string.IsNullOrWhiteSpace(url) && string.IsNullOrWhiteSpace(model))
        {
            if (DarkDialog.Show(this, "AI Model Name Needed",
                "Please specify the AI model name in Settings (e.g. fast-agent:3b).",
                "Open settings", "Cancel"))
            {
                Settings_Click(this, new RoutedEventArgs());
            }
            return;
        }

        var sent = n.Body;
        n.AiCts = new CancellationTokenSource();
        n.BusyText = "Formatting with AI…";
        n.IsBusy = true;
        try
        {
            var md = await AiFormatter.FormatAsync(url, key, model, sent, n.AiCts.Token);
            if (n.Body != sent) throw new AiException("The note changed while the AI was working, so the result was discarded.");
            n.ApplyAi(md);
        }
        catch (OperationCanceledException) { }
        catch (AiException ex) { DarkDialog.Show(this, "AI formatting didn't apply", ex.Message); }
        catch (Exception ex) { DarkDialog.Show(this, "AI formatting failed", ex.Message); }
        finally
        {
            n.IsBusy = false;
            n.AiCts?.Dispose();
            n.AiCts = null;
        }
    }
}
