package com.xconflictionx.xreponotes.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class NotesDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION
) {

    companion object {
        private const val DATABASE_NAME = "app_repo_notes.db"
        private const val DATABASE_VERSION = 2

        const val TABLE_NOTES = "notes"
        const val COLUMN_ID = "id"
        const val COLUMN_PC_ID = "pc_id"
        const val COLUMN_CLIENT_ID = "client_id"
        const val COLUMN_REPO_PATH = "repo_path"
        const val COLUMN_TITLE = "title"
        const val COLUMN_BODY = "body"
        const val COLUMN_CREATED_UTC = "created_utc"
        const val COLUMN_UPDATED_UTC = "updated_utc"
        const val COLUMN_LAST_SYNCED_BODY = "last_synced_body"
        const val COLUMN_IS_DIRTY = "is_dirty"
        const val COLUMN_IS_DELETED = "is_deleted"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableSql = """
            CREATE TABLE $TABLE_NOTES (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_PC_ID INTEGER NOT NULL DEFAULT 0,
                $COLUMN_CLIENT_ID TEXT NOT NULL DEFAULT '',
                $COLUMN_REPO_PATH TEXT NOT NULL DEFAULT '',
                $COLUMN_TITLE TEXT NOT NULL DEFAULT '',
                $COLUMN_BODY TEXT NOT NULL DEFAULT '',
                $COLUMN_CREATED_UTC TEXT NOT NULL,
                $COLUMN_UPDATED_UTC TEXT NOT NULL,
                $COLUMN_LAST_SYNCED_BODY TEXT NOT NULL DEFAULT '',
                $COLUMN_IS_DIRTY INTEGER NOT NULL DEFAULT 0,
                $COLUMN_IS_DELETED INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()
        db.execSQL(createTableSql)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_NOTES ADD COLUMN $COLUMN_CLIENT_ID TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE $TABLE_NOTES SET $COLUMN_CLIENT_ID = CASE WHEN $COLUMN_PC_ID > 0 THEN 'legacy:' || $COLUMN_PC_ID ELSE 'local:' || $COLUMN_ID END")
        }
    }

    fun getAllNotes(includeDeleted: Boolean = false): List<RepoNote> {
        val notesList = mutableListOf<RepoNote>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE_NOTES " + (if (includeDeleted) "" else "WHERE $COLUMN_IS_DELETED = 0 ") + "ORDER BY $COLUMN_UPDATED_UTC DESC",
            null
        )
        cursor.use { c ->
            val idxId = c.getColumnIndexOrThrow(COLUMN_ID)
            val idxPcId = c.getColumnIndexOrThrow(COLUMN_PC_ID)
            val idxClientId = c.getColumnIndexOrThrow(COLUMN_CLIENT_ID)
            val idxRepo = c.getColumnIndexOrThrow(COLUMN_REPO_PATH)
            val idxTitle = c.getColumnIndexOrThrow(COLUMN_TITLE)
            val idxBody = c.getColumnIndexOrThrow(COLUMN_BODY)
            val idxCreated = c.getColumnIndexOrThrow(COLUMN_CREATED_UTC)
            val idxUpdated = c.getColumnIndexOrThrow(COLUMN_UPDATED_UTC)
            val idxLastSynced = c.getColumnIndexOrThrow(COLUMN_LAST_SYNCED_BODY)
            val idxDirty = c.getColumnIndexOrThrow(COLUMN_IS_DIRTY)
            val idxDeleted = c.getColumnIndexOrThrow(COLUMN_IS_DELETED)

            while (c.moveToNext()) {
                notesList.add(
                    RepoNote(
                        id = c.getLong(idxId),
                        pcId = c.getLong(idxPcId),
                        clientId = c.getString(idxClientId),
                        repoPath = c.getString(idxRepo),
                        title = c.getString(idxTitle),
                        body = c.getString(idxBody),
                        createdUtc = c.getString(idxCreated),
                        updatedUtc = c.getString(idxUpdated),
                        lastSyncedBody = c.getString(idxLastSynced),
                        isDirty = c.getInt(idxDirty) == 1,
                        isDeleted = c.getInt(idxDeleted) == 1
                    )
                )
            }
        }
        return notesList
    }

    fun getNoteById(id: Long): RepoNote? {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE_NOTES WHERE $COLUMN_ID = ?",
            arrayOf(id.toString())
        )
        cursor.use { c ->
            if (c.moveToFirst()) {
                return RepoNote(
                    id = c.getLong(c.getColumnIndexOrThrow(COLUMN_ID)),
                    pcId = c.getLong(c.getColumnIndexOrThrow(COLUMN_PC_ID)),
                    clientId = c.getString(c.getColumnIndexOrThrow(COLUMN_CLIENT_ID)),
                    repoPath = c.getString(c.getColumnIndexOrThrow(COLUMN_REPO_PATH)),
                    title = c.getString(c.getColumnIndexOrThrow(COLUMN_TITLE)),
                    body = c.getString(c.getColumnIndexOrThrow(COLUMN_BODY)),
                    createdUtc = c.getString(c.getColumnIndexOrThrow(COLUMN_CREATED_UTC)),
                    updatedUtc = c.getString(c.getColumnIndexOrThrow(COLUMN_UPDATED_UTC)),
                    lastSyncedBody = c.getString(c.getColumnIndexOrThrow(COLUMN_LAST_SYNCED_BODY)),
                    isDirty = c.getInt(c.getColumnIndexOrThrow(COLUMN_IS_DIRTY)) == 1,
                    isDeleted = c.getInt(c.getColumnIndexOrThrow(COLUMN_IS_DELETED)) == 1
                )
            }
        }
        return null
    }

    fun insertNote(note: RepoNote): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_PC_ID, note.pcId)
            put(COLUMN_CLIENT_ID, note.clientId)
            put(COLUMN_REPO_PATH, note.repoPath)
            put(COLUMN_TITLE, note.title)
            put(COLUMN_BODY, note.body)
            put(COLUMN_CREATED_UTC, note.createdUtc)
            put(COLUMN_UPDATED_UTC, note.updatedUtc)
            put(COLUMN_LAST_SYNCED_BODY, note.lastSyncedBody)
            put(COLUMN_IS_DIRTY, if (note.isDirty) 1 else 0)
            put(COLUMN_IS_DELETED, if (note.isDeleted) 1 else 0)
        }
        return db.insert(TABLE_NOTES, null, values)
    }

    fun updateNote(note: RepoNote): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_PC_ID, note.pcId)
            put(COLUMN_CLIENT_ID, note.clientId)
            put(COLUMN_REPO_PATH, note.repoPath)
            put(COLUMN_TITLE, note.title)
            put(COLUMN_BODY, note.body)
            put(COLUMN_UPDATED_UTC, note.updatedUtc)
            put(COLUMN_LAST_SYNCED_BODY, note.lastSyncedBody)
            put(COLUMN_IS_DIRTY, if (note.isDirty) 1 else 0)
            put(COLUMN_IS_DELETED, if (note.isDeleted) 1 else 0)
        }
        val rows = db.update(TABLE_NOTES, values, "$COLUMN_ID = ?", arrayOf(note.id.toString()))
        return rows > 0
    }

    fun deleteNote(id: Long): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_IS_DELETED, 1)
        }
        val rows = db.update(TABLE_NOTES, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
        return rows > 0
    }

    fun purgeNote(id: Long): Boolean {
        val rows = writableDatabase.delete(TABLE_NOTES, "$COLUMN_ID = ?", arrayOf(id.toString()))
        return rows > 0
    }

    fun saveOrUpdateFromSync(syncedNotes: List<RepoNote>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (sn in syncedNotes) {
                // Check if local note exists with this pcId
                val cursor = db.rawQuery(
                    "SELECT $COLUMN_ID FROM $TABLE_NOTES WHERE $COLUMN_PC_ID = ? AND $COLUMN_PC_ID > 0",
                    arrayOf(sn.pcId.toString())
                )
                var localId = -1L
                cursor.use { c ->
                    if (c.moveToFirst()) localId = c.getLong(0)
                }
                if (localId < 0L && sn.pcId > 0L) {
                    val pendingCursor = db.rawQuery(
                        "SELECT $COLUMN_ID FROM $TABLE_NOTES WHERE $COLUMN_PC_ID = 0 AND $COLUMN_REPO_PATH = ? AND $COLUMN_TITLE = ? ORDER BY $COLUMN_ID LIMIT 1",
                        arrayOf(sn.repoPath, sn.title)
                    )
                    pendingCursor.use { c -> if (c.moveToFirst()) localId = c.getLong(0) }
                }

                val values = ContentValues().apply {
                    put(COLUMN_PC_ID, sn.pcId)
                    put(COLUMN_CLIENT_ID, sn.clientId)
                    put(COLUMN_REPO_PATH, sn.repoPath)
                    put(COLUMN_TITLE, sn.title)
                    put(COLUMN_BODY, sn.body)
                    put(COLUMN_CREATED_UTC, sn.createdUtc)
                    put(COLUMN_UPDATED_UTC, sn.updatedUtc)
                    put(COLUMN_LAST_SYNCED_BODY, sn.body)
                    put(COLUMN_IS_DIRTY, 0)
                    put(COLUMN_IS_DELETED, if (sn.isDeleted) 1 else 0)
                }

                if (localId > 0) {
                    db.update(TABLE_NOTES, values, "$COLUMN_ID = ?", arrayOf(localId.toString()))
                } else {
                    db.insert(TABLE_NOTES, null, values)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
