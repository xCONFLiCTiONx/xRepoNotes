using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Win32;
using RepoNotes.Services;

namespace RepoNotes;

public partial class SettingsWindow : Window
{
    private readonly Database _db;
    private bool _clearKey;
    private bool _isKeyModified;
    private bool _suppressKeyEvents;
    private string _savedKey = "";
    private bool _clearNotesApiKey;

    private enum KeyStatus { NotSet, Saved, UnsavedChanges, Cleared }

    public SettingsWindow(Database db)
    {
        InitializeComponent();
        Native.Attach(this, cloakUntilRendered: false);
        _db = db;

        RootBox.Text = db.GetSetting("root");
        NotesApiUrlBox.Text = db.GetSetting("notes_api_url");
        NotesApiKeyBox.Password = db.GetSecret("notes_api_key");
        UrlBox.Text = db.GetSetting("ai_url");

        var model = db.GetSetting("ai_model");
        if (string.IsNullOrWhiteSpace(model)) model = db.GetSetting("model");
        ModelBox.Text = model;


        _suppressKeyEvents = true;
        _savedKey = db.GetSecret("ai_key");
        if (_savedKey.Length == 0) _savedKey = db.GetSecret("gemini");
        if (_savedKey.Length > 0)
        {
            KeyBox.Password = _savedKey;
            KeyTextBox.Text = _savedKey;
            SetKeyBadge(KeyStatus.Saved);
        }
        else
        {
            SetKeyBadge(KeyStatus.NotSet);
        }
        _suppressKeyEvents = false;

        SourceInitialized += (_, _) => FitToVisibleWorkArea();
        Loaded += async (_, _) =>
        {
            FitToVisibleWorkArea();
            await AutoFetchModelsAsync();
        };
    }

    private void FitToVisibleWorkArea()
    {
        var area = SystemParameters.WorkArea;
        if (area.Width <= 0 || area.Height <= 0) return;

        // Keep the settings window within the usable desktop (excluding taskbar)
        // even when the owner is maximized or positioned near a screen edge.
        Width = Math.Min(920, Math.Max(MinWidth, area.Width * 0.90));
        Height = Math.Min(700, Math.Max(MinHeight, area.Height * 0.90));
        if (Width > area.Width) Width = area.Width;
        if (Height > area.Height) Height = area.Height;
        MinWidth = Math.Min(680, Width);
        MinHeight = Math.Min(520, Height);

        double left = area.Left + (area.Width - Width) / 2;
        double top = area.Top + (area.Height - Height) / 2;

        if (Owner is { IsVisible: true, WindowState: not WindowState.Maximized } owner)
        {
            left = owner.Left + (owner.ActualWidth - Width) / 2;
            top = owner.Top + (owner.ActualHeight - Height) / 2;
        }

        Left = Math.Max(area.Left, Math.Min(left, area.Right - Width));
        Top = Math.Max(area.Top, Math.Min(top, area.Bottom - Height));
    }

    private void SetKeyBadge(KeyStatus status)
    {
        switch (status)
        {
            case KeyStatus.Saved:
                KeyStatusBadge.Text = "● SAVED";
                KeyStatusBadge.Foreground = new SolidColorBrush(Color.FromRgb(0x4E, 0xCA, 0x81));
                KeyStatusBadgeBorder.Background = new SolidColorBrush(Color.FromArgb(0x33, 0x4E, 0xCA, 0x81));
                KeyHint.Text = "Your API key is saved and stored encrypted. Leave as is, or paste a new one to replace it.";
                break;
            case KeyStatus.UnsavedChanges:
                KeyStatusBadge.Text = "● UNSAVED";
                KeyStatusBadge.Foreground = new SolidColorBrush(Color.FromRgb(0xE3, 0xB3, 0x41));
                KeyStatusBadgeBorder.Background = new SolidColorBrush(Color.FromArgb(0x33, 0xE3, 0xB3, 0x41));
                KeyHint.Text = "Click Save to apply your new API key.";
                break;
            case KeyStatus.Cleared:
                KeyStatusBadge.Text = "● CLEARED";
                KeyStatusBadge.Foreground = new SolidColorBrush(Color.FromRgb(0xE5, 0x53, 0x4B));
                KeyStatusBadgeBorder.Background = new SolidColorBrush(Color.FromArgb(0x33, 0xE5, 0x53, 0x4B));
                KeyHint.Text = "The saved key will be removed when you click Save.";
                break;
            default:
                KeyStatusBadge.Text = "● NOT SET";
                KeyStatusBadge.Foreground = new SolidColorBrush(Color.FromRgb(0x8B, 0x94, 0x9E));
                KeyStatusBadgeBorder.Background = new SolidColorBrush(Color.FromArgb(0x22, 0x8B, 0x94, 0x9E));
                KeyHint.Text = "Required for cloud services (Gemini) or authenticated servers (xAssistant). Stored encrypted.";
                break;
        }
    }

    private void KeyBox_PasswordChanged(object sender, RoutedEventArgs e)
    {
        if (_suppressKeyEvents) return;
        _isKeyModified = true;
        _clearKey = false;
        if (KeyTextBox.Visibility != Visibility.Visible)
        {
            _suppressKeyEvents = true;
            KeyTextBox.Text = KeyBox.Password;
            _suppressKeyEvents = false;
        }
        SetKeyBadge(string.IsNullOrWhiteSpace(KeyBox.Password) ? KeyStatus.NotSet : KeyStatus.UnsavedChanges);
    }

    private void KeyTextBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (_suppressKeyEvents) return;
        _isKeyModified = true;
        _clearKey = false;
        if (KeyBox.Visibility != Visibility.Visible)
        {
            _suppressKeyEvents = true;
            KeyBox.Password = KeyTextBox.Text;
            _suppressKeyEvents = false;
        }
        SetKeyBadge(string.IsNullOrWhiteSpace(KeyTextBox.Text) ? KeyStatus.NotSet : KeyStatus.UnsavedChanges);
    }

    private void ToggleKey_Click(object sender, RoutedEventArgs e)
    {
        if (KeyTextBox.Visibility == Visibility.Visible)
        {
            _suppressKeyEvents = true;
            KeyBox.Password = KeyTextBox.Text;
            _suppressKeyEvents = false;
            KeyTextBox.Visibility = Visibility.Collapsed;
            KeyBox.Visibility = Visibility.Visible;
            ToggleKeyBtn.Content = "👁 Show";
            KeyBox.Focus();
        }
        else
        {
            _suppressKeyEvents = true;
            KeyTextBox.Text = KeyBox.Password;
            _suppressKeyEvents = false;
            KeyBox.Visibility = Visibility.Collapsed;
            KeyTextBox.Visibility = Visibility.Visible;
            ToggleKeyBtn.Content = "🙈 Hide";
            KeyTextBox.Focus();
        }
    }

    private void ModelBox_SelectionChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (ModelBox.SelectedItem is string selected && !string.IsNullOrWhiteSpace(selected))
        {
            ModelBox.Text = selected;
        }
    }

    private string GetEffectiveApiKey()
    {
        var current = (KeyTextBox.Visibility == Visibility.Visible ? KeyTextBox.Text : KeyBox.Password).Trim();
        return !string.IsNullOrWhiteSpace(current) ? current : _savedKey;
    }

    private async Task AutoFetchModelsAsync()
    {
        var url = UrlBox.Text.Trim();
        var key = GetEffectiveApiKey();
        if (string.IsNullOrWhiteSpace(url) && string.IsNullOrWhiteSpace(key)) return;

        try
        {
            var models = await AiFormatter.FetchModelsAsync(url, key);
            if (models.Count > 0)
            {
                var current = (ModelBox.SelectedItem as string ?? ModelBox.Text).Trim();
                ModelBox.ItemsSource = models;
                var match = models.FirstOrDefault(m => string.Equals(m, current, StringComparison.OrdinalIgnoreCase));
                if (match != null)
                {
                    ModelBox.SelectedItem = match;
                    ModelBox.Text = match;
                }
                else if (!string.IsNullOrWhiteSpace(current))
                {
                    ModelBox.Text = current;
                }
                else
                {
                    ModelBox.SelectedIndex = 0;
                }
            }
        }
        catch { }
    }

    private async void FetchModels_Click(object sender, RoutedEventArgs e)
    {
        FetchModelsBtn.IsEnabled = false;
        FetchModelsBtn.Content = "Fetching…";
        StatusText.Visibility = Visibility.Collapsed;
        try
        {
            var url = UrlBox.Text.Trim();
            var key = GetEffectiveApiKey();
            var models = await AiFormatter.FetchModelsAsync(url, key);

            if (models.Count > 0)
            {
                var current = (ModelBox.SelectedItem as string ?? ModelBox.Text).Trim();
                ModelBox.ItemsSource = models;
                var match = models.FirstOrDefault(m => string.Equals(m, current, StringComparison.OrdinalIgnoreCase));
                if (match != null)
                {
                    ModelBox.SelectedItem = match;
                    ModelBox.Text = match;
                }
                else
                {
                    ModelBox.SelectedIndex = 0;
                }
                DarkDialog.Show(this, "Models Found", $"Successfully retrieved {models.Count} model(s) from the server:\n\n" + string.Join("\n", models.Select(m => $" • {m}")));
            }
            else
            {
                DarkDialog.Show(this, "No Models Found", "Could not retrieve models list from the server. Check your Server URL or API key.");
            }
        }
        catch (Exception ex)
        {
            DarkDialog.Show(this, "Error Fetching Models", ex.Message);
        }
        finally
        {
            FetchModelsBtn.IsEnabled = true;
            FetchModelsBtn.Content = "🔍 Fetch models";
        }
    }

    private async void TestConnection_Click(object sender, RoutedEventArgs e)
    {
        TestConnBtn.IsEnabled = false;
        TestConnBtn.Content = "Testing…";
        try
        {
            var url = UrlBox.Text.Trim();
            var key = GetEffectiveApiKey();
            var model = (ModelBox.SelectedItem as string ?? ModelBox.Text).Trim();

            var (success, message, models) = await AiFormatter.TestConnectionAsync(url, key, model);

            if (models.Count > 0)
            {
                var current = (ModelBox.SelectedItem as string ?? ModelBox.Text).Trim();
                ModelBox.ItemsSource = models;
                var match = models.FirstOrDefault(m => string.Equals(m, current, StringComparison.OrdinalIgnoreCase));
                if (match != null)
                {
                    ModelBox.SelectedItem = match;
                    ModelBox.Text = match;
                }
                else if (!string.IsNullOrWhiteSpace(current))
                {
                    ModelBox.Text = current;
                }
                else
                {
                    ModelBox.SelectedIndex = 0;
                }
            }

            DarkDialog.Show(this, success ? "Connection Successful" : "Connection Failed", message);
        }
        catch (Exception ex)
        {
            DarkDialog.Show(this, "Test Connection Error", ex.Message);
        }
        finally
        {
            TestConnBtn.IsEnabled = true;
            TestConnBtn.Content = "⚡ Test connection";
        }
    }

    private void ClearNotesApiKey_Click(object sender, RoutedEventArgs e)
    {
        NotesApiKeyBox.Clear();
        _clearNotesApiKey = true;
    }

    private void TestNotesApi_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var url = NotesApiUrlBox.Text.Trim();
            var key = NotesApiKeyBox.Password.Trim();
            if (url.Length == 0 || key.Length == 0)
            {
                DarkDialog.Show(this, "Database settings missing", "Enter the Notes API URL and API key first.");
                return;
            }

            using var client = new NotesApiClient(url, key);
            var notes = client.GetNotes();
            DarkDialog.Show(this, "Database connection successful", $"Connected to the central database. {notes.Count} notes found.");
        }
        catch (Exception ex)
        {
            DarkDialog.Show(this, "Database connection failed", ex.Message);
        }
    }

    private async void ImportBackup_Click(object sender, RoutedEventArgs e)
    {
        var picker = new OpenFileDialog
        {
            Title = "Select your xRepoNotes database backup",
            Filter = "SQLite database (notes.db)|notes.db|SQLite database files (*.db;*.sqlite;*.sqlite3)|*.db;*.sqlite;*.sqlite3|All files (*.*)|*.*",
            CheckFileExists = true,
            Multiselect = false
        };
        if (picker.ShowDialog(this) != true) return;

        if (!DarkDialog.Show(this, "Import database backup?",
            "Notes from the selected backup will be imported into the existing xServer database.\n\n" +
            "Matching note IDs on xServer will be replaced with the backup versions. Other xServer notes will remain. " +
            "Your selected file and the xServer database file will not be overwritten.",
            "Import", "Cancel"))
            return;

        var button = sender as Button;
        if (button != null)
        {
            button.IsEnabled = false;
            button.Content = "Importing…";
        }
        try
        {
            var count = await Task.Run(() => _db.ImportDatabaseBackup(picker.FileName));
            DarkDialog.Show(this, "Import complete", $"Imported {count:N0} notes into xServer.");
            DialogResult = true;
        }
        catch (Exception ex)
        {
            DarkDialog.Show(this, "Database import failed", ex.Message);
        }
        finally
        {
            if (button != null)
            {
                button.IsEnabled = true;
                button.Content = "Import local notes…";
            }
        }
    }

    private void Browse_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFolderDialog
        {
            Title = "Select your main repos directory",
            InitialDirectory = RootBox.Text
        };
        if (dlg.ShowDialog(this) == true)
        {
            RootBox.Text = dlg.FolderName;
        }
    }

    private void ClearKey_Click(object sender, RoutedEventArgs e)
    {
        _clearKey = true;
        _isKeyModified = true;
        _savedKey = "";
        _suppressKeyEvents = true;
        KeyBox.Clear();
        KeyTextBox.Clear();
        _suppressKeyEvents = false;
        SetKeyBadge(KeyStatus.Cleared);
    }

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        var root = RootBox.Text.Trim();
        if (root.Length > 0 && !System.IO.Directory.Exists(root))
        {
            DarkDialog.Show(this, "Folder not found", "That repos directory doesn't exist on disk.");
            return;
        }

        var chosenModel = (ModelBox.SelectedItem as string ?? ModelBox.Text).Trim();

        _db.SetSetting("root", root);
        var notesApiUrl = NotesApiUrlBox.Text.Trim();
        if (!string.Equals(_db.GetSetting("notes_api_url").Trim(), notesApiUrl, StringComparison.OrdinalIgnoreCase))
            _db.SetSetting("notes_api_initialized_for", "");
        _db.SetSetting("notes_api_url", notesApiUrl);
        if (_clearNotesApiKey && string.IsNullOrWhiteSpace(NotesApiKeyBox.Password))
            _db.SetSecret("notes_api_key", "");
        else if (!string.IsNullOrWhiteSpace(NotesApiKeyBox.Password))
            _db.SetSecret("notes_api_key", NotesApiKeyBox.Password.Trim());

        _db.SetSetting("ai_url", UrlBox.Text.Trim());
        _db.SetSetting("ai_model", chosenModel);
        _db.SetSetting("model", chosenModel);

        if (_clearKey)
        {
            _db.SetSecret("ai_key", "");
            _db.SetSecret("gemini", "");
        }
        else if (_isKeyModified)
        {
            var keyToSave = (KeyTextBox.Visibility == Visibility.Visible ? KeyTextBox.Text : KeyBox.Password).Trim();
            _db.SetSecret("ai_key", keyToSave);
            _db.SetSecret("gemini", keyToSave);
        }
        else if (_savedKey.Length > 0)
        {
            _db.SetSecret("ai_key", _savedKey);
            _db.SetSecret("gemini", _savedKey);
        }

        DialogResult = true;
    }
}
