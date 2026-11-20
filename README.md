# RepoNotes

A dark-themed notes system with a Windows desktop client, native Android client, and a shared xServer database for organizing, editing, and formatting Markdown notes tied directly to your Git repositories.

---

## Key Features

### 1. Zero-Flash Pure Dark Theme
- **No White Flash on Launch**: Uses Windows Desktop Window Manager (DWM) cloaking (`DwmSetWindowAttribute`) to keep the window invisible to the compositor until the dark visual tree has fully painted.
- **True Dark Title Bar & Controls**: Enforces native Windows immersive dark mode captions (`DWMWA_USE_IMMERSIVE_DARK_MODE`, `DWMWA_CAPTION_COLOR`). All WPF system controls, context menus, text inputs, scrollbars, dialogs, and combo boxes are themed in dark slate colors (`#0F1115` / `#161A20` / `#1E232B`).
- **Display & Taskbar Aware**: Automatically calculates window dimensions from `SystemParameters.WorkArea` so the window never overflows a 1920×1080 display or collides with the Windows 11 taskbar. Remembers your resized dimensions across launches.

### 2. Automatic Git Repository Discovery
- **Configurable Root Directory**: Point the application to your central repos folder (e.g., `C:\Repos` or `D:\Code`).
- **`.git` Detection**: Filters out ordinary folders and lists **only** projects tracked by Git (detects both standard `.git` directories and worktree/submodule `.git` files).
- **Non-Blocking Background Scanning**: Repos are scanned asynchronously with an indeterminate progress indicator in the sidebar so the UI never freezes.
- **Missing Repo Resilience**: If a repository directory is renamed or moved, notes associated with it are preserved and clearly marked as `(not found)`.

### 3. Personal and Work Notebooks
- **Personal Notebook**: Synced with the central xServer notes database and organized by Git project.
- **Work Notebook**: A separate local-only notebook stored in `%LOCALAPPDATA%\RepoNotes\work_notebook.db`. Work notes never enter the xServer database or automatic/manual sync.
- Switch between Personal Notebook and Work Notebook from the main toolbar. Work mode hides project navigation and project assignment.

### 4. Dual Note Views
- **Sticky Notes Dashboard**:
  - Grid view of sticky cards displaying project badges, title, content preview, and last-updated timestamps.
  - **Inline Editing**: Edit titles and markdown bodies directly inside the sticky card without leaving the dashboard.
- **Large Focused Editor**:
  - Full-screen split-view editor featuring a monospace markdown input area on the left and a live-rendered FlowDocument preview on the right.
  - Switch repos on the fly or assign notes to "General".
  - Press <kbd>Esc</kbd> to return to the dashboard.

### 5. AI Markdown Formatter
- **Single-Click Cleanup**: Cleans up messy raw notes into proper Markdown with structured bullet points, numbered lists, and fenced code blocks.
- **Local & Cloud AI Support (Configurable in Settings)**:
  - **Shared xServer gateway**: Set the AI Server URL to `http://<your-server-ip>:11435/v1` (or your HTTPS gateway URL), fetch the available profiles, and select `xRepoNotes`. This routes formatting requests through the shared `qwen3.5:4b-q4_K_M` model; it does not load a separate model.
  - **Other local servers**: Compatible with Ollama and OpenAI-compatible local endpoints.
  - **Cloud Models (Google Gemini)**: Leave the URL blank to use Gemini cloud with an API key.
  - **Zero Hardcoded Endpoints/Models**: All endpoints and model names are user-configurable in the Settings UI.
- **Formatting Output Validation**: The response is parsed and checked for a usable Markdown result before it is applied.
- **Reversible & Cancellable**:
  - Live progress overlay with a **Cancel** button while waiting for the AI response.
  - **Undo AI** button allows reverting immediately back to your original pre-formatted draft.
- **Secure Key Storage**: API keys are encrypted at rest using Windows DPAPI (`ProtectedData.Protect` under `DataProtectionScope.CurrentUser`).

### 6. SQLite Database Storage
- The Windows client keeps a local SQLite cache and settings at `%LOCALAPPDATA%\RepoNotes\notes.db`. The isolated Work Notebook is stored separately at `%LOCALAPPDATA%\RepoNotes\work_notebook.db` and is never synchronized.
- When the central xServer API is configured, xServer owns the shared notes database; Windows and Android clients communicate through the API rather than opening a network-shared SQLite file.
- SQLite uses **WAL mode** (Write-Ahead Logging). Windows notes are auto-saved in the background with a 500ms debounce.
- The server includes a daily online backup service with integrity verification and 14-day retention.

---

## Central xServer database API

The `Server/` project provides the central notes API for Windows and Android clients. It owns the SQLite database on xServer; clients communicate over HTTP and never access the database file directly. Configure the Notes API URL and key in Windows Settings; the desktop app keeps a local cache, migrates existing notes on first connection, and refreshes from the server while idle. The Android app connects to the same API. The API uses port `11436`, separate from the shared AI inference gateway on `11435`, and requires an `X-RepoNotes-Key` header. See [Server/README.md](Server/README.md) for deployment, Cloudflare Tunnel, authentication, and migration instructions.

## Native Android app

The native Android project is in [`android/`](android/README.md). It provides the Notes, File editor, and Settings drawer destinations, connects to the central API, encrypts the API key with Android Keystore, and registers Android file-open/share intents. Open the `android/` folder in Android Studio or run `.\android\.build.ps1` to build it.

**File Editor parity note:** Android currently supports opening and saving text files from Android storage and external apps. The existing xAssistant workspace editor (`~/xAssistant/web/server.py` and `web/static/index.html`) still needs to be ported so folder browsing, multi-file upload, download, and refresh are preserved.

## Prerequisites

- **Operating System**: Windows 10 (1809+) or Windows 11
- **Runtime / SDK**: [.NET 10 SDK](https://dotnet.microsoft.com/download/dotnet/10.0) (or runtime)

---

## Quick Start & Building

### Using the Build Script (`.build.ps1`)

The repository includes a PowerShell build script [`.build.ps1`](file:///e:/xRepoNotes/.build.ps1) with full automation:

```powershell
# Build in Release mode (default)
.\.build.ps1

# Build and launch immediately
.\.build.ps1 -Run

# Clean previous build artifacts and compile in Debug mode
.\.build.ps1 -Clean -Configuration Debug

# Publish self-contained distribution package to .\publish
.\.build.ps1 -Publish
```

### Using the .NET CLI

```powershell
# Restore and build
dotnet build -c Release

# Run the app
dotnet run -c Release

# Publish
dotnet publish -c Release -o ./publish
```

---

## Configuration & Usage

1. **Set your Repos Directory**:
   - Click the **Settings (gear icon)** button in the top-right toolbar.
   - Click **Browse…** and select the parent folder containing all your Git repositories.
   - Click **Save**. The sidebar will automatically populate with all detected Git projects.
2. **Configure AI Note Formatting**:
   - Open **Settings** (⚙).
   - **Shared xServer gateway**:
     - **AI Server URL**: Enter `http://<your-server-ip>:11435/v1` (or your HTTPS gateway URL).
     - Click **Fetch models** and select **`xRepoNotes`**.
     - Leave **API Key** blank unless authentication has explicitly been configured on the gateway.
   - **Other local AI servers**: Enter that server's URL and select one of its available model IDs.
   - **Cloud AI (Google Gemini)**:
     - **AI Server URL**: Leave blank.
     - **AI Model Name**: Enter `gemini-2.5-flash` or leave blank for default.
     - **API Key**: Enter your Google Gemini API Key.
3. **Taking Notes**:
   - Click **＋ New note** or press <kbd>Ctrl</kbd> + <kbd>N</kbd>.
   - If a repository is selected in the sidebar, the note automatically tags that repository; otherwise it creates a "General" note.
   - Type or paste your notes into the card.
   - Click **✦ Format** to let the AI organize the content into clean Markdown.
   - Click the **Expand (⤢)** button to enter the large split-pane editor.

---

## Keyboard Shortcuts

| Shortcut | Action |
| :--- | :--- |
| <kbd>Ctrl</kbd> + <kbd>N</kbd> | Create a new note |
| <kbd>Esc</kbd> | Close the expanded editor and return to the dashboard |
| <kbd>Ctrl</kbd> + <kbd>F</kbd> | Focus the search bar |

---

## Project Structure

```
E:\xRepoNotes\
├── android/                  # Native Android client
├── Server/                   # Central xServer API and SQLite backup service
├── .build.ps1                # PowerShell build, run, and publish script
├── README.md                 # Project documentation and guide
├── RepoNotes.csproj          # .NET 10 WPF project definition
├── App.xaml / App.xaml.cs    # Application entry point, DB task initialization, unhandled error handler
├── MainWindow.xaml (.cs)     # Dashboard, sticky cards, search/filter, and full editor
├── SettingsWindow.xaml (.cs) # Directory configuration, AI server URL, model & key settings
├── Theme.xaml                # Complete dark palette, control templates, system color overrides
├── DarkDialog.cs             # Themed modal dialogs (replaces standard white MessageBox)
├── Models/
│   └── Models.cs             # NoteVM, RepoColors (hash-based accents), SidebarEntry, RepoChoice
└── Services/
    ├── AiFormatter.cs        # Ollama / OpenAI / Gemini client and Markdown response parser
    ├── Database.cs           # SQLite WAL persistence with DPAPI secret encryption
    ├── MarkdownRenderer.cs   # Dependency-free Markdown to FlowDocument parser/renderer
    ├── Native.cs             # DWM cloaking and dark mode titlebar P/Invoke hooks
    └── RepoScanner.cs        # Non-blocking recursive Git repository scanner
```
