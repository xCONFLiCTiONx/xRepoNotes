#!/usr/bin/env python3
"""Create a consistent SQLite backup while the RepoNotes API is running."""

from datetime import datetime, timedelta, timezone
import os
from pathlib import Path
import sqlite3

database = Path(os.environ.get("REPONOTES_DATABASE", "/var/lib/xreponotes/notes.db"))
backup_dir = Path(os.environ.get("REPONOTES_BACKUP_DIR", "/var/backups/xreponotes"))
retention_days = max(1, int(os.environ.get("REPONOTES_BACKUP_RETENTION_DAYS", "14")))

if not database.is_file():
    raise SystemExit(f"Database does not exist: {database}")

backup_dir.mkdir(parents=True, exist_ok=True)
stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
destination = backup_dir / f"notes-{stamp}.sqlite3"
temporary = backup_dir / f".{destination.name}.tmp"

try:
    # sqlite3.Connection.backup() reads a transactionally consistent snapshot,
    # including committed data in the WAL; never copy the live DB file directly.
    source = sqlite3.connect(str(database), timeout=30)
    target = sqlite3.connect(str(temporary), timeout=30)
    try:
        source.backup(target, pages=256, sleep=0.1)
        check = target.execute("PRAGMA integrity_check").fetchone()
        if check is None or check[0] != "ok":
            raise RuntimeError(f"Backup integrity check failed: {check!r}")
    finally:
        target.close()
        source.close()

    os.chmod(temporary, 0o600)
    os.replace(temporary, destination)
except Exception:
    temporary.unlink(missing_ok=True)
    raise

cutoff = datetime.now(timezone.utc) - timedelta(days=retention_days)
for old_backup in backup_dir.glob("notes-*.sqlite3"):
    if old_backup == destination:
        continue
    modified = datetime.fromtimestamp(old_backup.stat().st_mtime, timezone.utc)
    if modified < cutoff:
        old_backup.unlink()

print(f"Backup complete: {destination} ({destination.stat().st_size} bytes)")
