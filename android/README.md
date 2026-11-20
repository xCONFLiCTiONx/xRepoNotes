# RepoNotes Android

This is the native Android client for the central RepoNotes API. Open the `android/` directory in Android Studio and let Gradle sync, or run `.\.build.ps1` from `android/` to build (and install/launch if a device is connected over ADB).

## Screens

The left drawer is closed by default and contains exactly three destinations:

1. Notes
2. File editor
3. Settings

Notes are read and written through the xServer API. The API key is encrypted using an AES-GCM key held by Android Keystore.

## File editor and Android integration

The app registers for `ACTION_VIEW`, `ACTION_SEND`, and `ACTION_SEND_MULTIPLE` with a broad MIME filter so Android can offer RepoNotes for files whose MIME type or extension is unknown. The editor then inspects the actual file contents rather than trusting the extension. Multiple shared files are opened sequentially from the in-app queue.

- Text files are accepted regardless of filename extension.
- UTF-8, UTF-8 BOM, UTF-16 LE/BE BOM, and Windows-1252 text are recognized.
- SQLite/database extensions and SQLite signatures are rejected.
- Binary content is rejected using NUL-byte and control-character checks.
- Files selected through Android's Storage Access Framework can be edited and saved when the provider grants write access; otherwise use **Save As**.

The current editor has a 20 MB safety limit. This is an initial mobile implementation; additional editing behavior from the existing File Editor still needs to be reconciled once its source is available.

## Connection

Set the API base URL and API key in Settings (for example, `http://<your-server-ip>:11436` for direct local LAN access, or an HTTPS hostname for remote access). When away, connect via your private VPN/tunnel route or an HTTPS endpoint.

The API key is stored encrypted at rest with Android Keystore. Do not commit API keys or place them in Gradle files.
