# xRepoNotes

> xRepoNotes Mobile — Android companion app for xRepoNotes

## Table of Contents
- [Features](#features)
- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Notes Synchronization](#notes-synchronization)
- [License](#license)

## Features
- 🚀 Fast, lightweight Jetpack Compose editor and Markdown notebook
- ⚙️ Personal Notebook and Work Notebook dual views
- 🔄 Seamless sync with xServer and desktop xRepoNotes companion

## Notes Synchronization

- Personal Notebook and Work Notebook notes are saved locally in SQLite before any network request.
- Each page has a stable client ID so a retry after a lost response does not create duplicate server notes.
- Failed syncs remain in the local SQLite database and are retried when connectivity returns.
- WorkManager schedules a connected-network sync every 15 minutes, an immediate sync on app startup, and exponential-backoff retries after failures. WorkManager persists scheduled work across device reboots.
- Pending deletions remain as local tombstones until the server confirms them. Conflicting remote edits are retained for review rather than silently deleted.

## Prerequisites
- Android Studio / Android SDK (Min SDK 24, Target SDK 37)

## Installation
```bash
# Clone repository
git clone <repository-url>
cd xRepoNotes-Mobile
```

## License
This project is licensed under the terms specified in the [LICENSE](LICENSE) file.
