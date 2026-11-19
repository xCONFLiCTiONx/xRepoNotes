# RepoNotes central API

This ASP.NET Core service owns the shared SQLite database. Windows and Android clients use this API; clients must never open the SQLite database file over SMB, SSHFS, or another network filesystem.

## API

- `GET /healthz` — unauthenticated liveness check; returns no configuration details.
- `GET /api/notes` — list active notes.
- `GET /api/notes/deletions` — list permanent deletion tombstones (ID and UTC deletion timestamp); clients must apply these before uploading local-only records.
- `GET /api/notes/{id}` — retrieve one note.
- `POST /api/notes` — create a note.
- `POST /api/notes/import` — transactionally import a selected desktop backup's notes, preserving IDs and timestamps; matching IDs are replaced and unrelated server notes remain.
- `PUT /api/notes/{id}` — update a note using `expectedUpdatedUtc` for optimistic concurrency.
- `DELETE /api/notes/{id}?expectedUpdatedUtc=...` — delete only if the note has not changed since it was loaded; records a permanent tombstone so stale clients cannot recreate it.

All `/api/*` routes require the `X-RepoNotes-Key` header. The key is read from `REPONOTES_API_KEY`; it is never stored in source control. Use a long random value and keep the environment file readable only by root and the service account.

A stale update or delete returns HTTP 409 instead of silently overwriting another device's changes. Deletion tombstones are retained in the central database and must not be discarded: a missing note ID is not a new note.

## xServer deployment

The AI inference gateway already uses port 11435. Keep this API on **11436**.

1. Publish for the xServer Linux runtime from a machine with the .NET 10 SDK:

   ```powershell
   dotnet publish .\Server\RepoNotes.Server.csproj -c Release -r linux-x64 --self-contained false -o .\publish-server
   ```

2. Copy the contents of `publish-server` to `/opt/xreponotes/server/` on xServer.

3. On xServer, create the service account and data/config directories:

   ```bash
   sudo useradd --system --home /var/lib/xreponotes --create-home --shell /usr/sbin/nologin xreponotes
   sudo install -d -o xreponotes -g xreponotes -m 0750 /var/lib/xreponotes
   sudo install -d -o root -g xreponotes -m 0750 /etc/xreponotes
   sudo install -d -o root -g root -m 0755 /opt/xreponotes/server
   ```

4. Create `/etc/xreponotes/server.env` with a newly generated API key:

   ```ini
   ASPNETCORE_URLS=http://0.0.0.0:11436
   REPONOTES_DATABASE=/var/lib/xreponotes/notes.db
   REPONOTES_API_KEY=replace-with-generated-secret
   ```

   Generate the value with `openssl rand -hex 32` and paste it into the environment file. Then lock it down:

   ```bash
   sudo chown root:xreponotes /etc/xreponotes/server.env
   sudo chmod 0640 /etc/xreponotes/server.env
   ```

5. Install the published service unit into `/etc/systemd/system/`, then run:

   ```bash
   sudo install -o root -g root -m 0644 /opt/xreponotes/server/xreponotes.service /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now xreponotes
   sudo systemctl status xreponotes --no-pager
   curl -fsS http://127.0.0.1:11436/healthz
   ```

6. Create the protected backup directory, install the backup systemd units, then enable and run the daily backup:

   ```bash
   sudo install -d -o xreponotes -g xreponotes -m 0700 /var/backups/xreponotes
   sudo chmod 0755 /opt/xreponotes/server/backup.py
   sudo install -o root -g root -m 0644 /opt/xreponotes/server/xreponotes-backup.service /etc/systemd/system/
   sudo install -o root -g root -m 0644 /opt/xreponotes/server/xreponotes-backup.timer /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now xreponotes-backup.timer
   sudo systemctl start xreponotes-backup.service
   sudo systemctl status xreponotes-backup.service --no-pager
   sudo ls -lh /var/backups/xreponotes
   ```

   The backup uses Python's SQLite online backup API, checks the backup with `PRAGMA integrity_check`, retains 14 days, and never copies the live database or WAL files directly. The timer runs daily at approximately 03:15 local server time. If you change the database path, update the `REPONOTES_DATABASE` setting in the backup service too.

7. For remote access from the native Android client, use your private VPN/tunnel route for your LAN subnet (`<your-lan-subnet>`); the app sends the API key directly to `http://<your-server-ip>:11436` (or your HTTPS endpoint). Do not put this API behind an interactive login page unless a supported native authentication flow is implemented. Do not publish port 11436 directly to the internet.

For direct LAN access, allow port 11436 only from the trusted LAN subnet in the host firewall:

```bash
sudo ufw allow from <your-lan-subnet> to any port 11436 proto tcp
sudo ufw status
```

Do not add a public WAN port-forward. Use a private VPN/tunnel route or HTTPS for remote network access; the API key is still required by both clients.

## Connect the Windows client

After the API is running:

1. Open RepoNotes on Windows and choose **Settings**.
2. Under **Central xServer database**, enter `http://<your-server-ip>:11436` (or your HTTPS URL) and the value of `REPONOTES_API_KEY`.
3. Click **Test database**, then **Save**.
4. The desktop app migrates local notes when the server is empty, keeps a local cache, and refreshes from xServer every 30 seconds while idle.

The Android app uses the same URL and key in its Settings screen. When away from the LAN, connect the device through the Zero Trust WARP profile and use the private route. The Android client does not currently implement an interactive Cloudflare Access browser login.

## Database migration

The service creates the same `notes` schema used by the existing Windows app. Do not point the Windows app at the server's SQLite file. On first connection, if the server is empty, Windows uploads the existing local notes while preserving their IDs and timestamps. If the server already has notes and the local cache is empty, Windows downloads the server copy. If both sides contain notes, non-overlapping IDs can be merged; if the same ID contains different content, sync stops without changing either copy so the conflict can be resolved deliberately. After initial setup, the desktop client reconciles local-only notes and newer local edits using the server's concurrency token. Keep a separate backup of the original `%LOCALAPPDATA%\\RepoNotes\\notes.db` until you have verified the note count and contents from both clients. Never copy the live SQLite file over a running server database.

## Current implementation status

- **Central API:** note CRUD backed by one xServer-owned SQLite database, with UTC optimistic concurrency checks and persistent deletion tombstones.
- **Windows client:** optional central API connection, local SQLite recovery cache, first-connection migration that preserves note IDs and timestamps, periodic server refresh/reconciliation, and deletion-aware sync that never recreates a server-missing cached ID after initial migration.
- **Android client:** native Jetpack Compose app with a closed-by-default left drawer containing exactly Notes, File editor, and Settings; note list/create/edit/delete; encrypted API-key storage using Android Keystore; and deletion-aware sync using server tombstones.
- **Android file editor:** opens files through Android's Storage Access Framework and accepts external `ACTION_VIEW`, `ACTION_SEND`, and `ACTION_SEND_MULTIPLE` intents. It detects text from file contents, rejects known database formats and binary data, and supports Save/Save As. Multiple files received through `ACTION_SEND_MULTIPLE` are queued and opened sequentially.
- **Not yet complete:** the existing xAssistant workspace editor (`~/xAssistant/web/server.py` and `web/static/index.html`) has not yet been ported. Its workspace browser, folder navigation, multi-file upload, download, and refresh workflows must be brought into the RepoNotes File editor without losing Android's external-file integration. The current Android editor is therefore a first slice, not a full replacement.
- **Backups:** a daily SQLite online backup service is included, with integrity verification and 14-day retention. Restore testing is still required before treating the server as the only authoritative copy. Keep a separate backup of the original Windows database until migration is verified.
