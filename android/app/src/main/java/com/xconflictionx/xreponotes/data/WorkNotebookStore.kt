package com.xconflictionx.xreponotes.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Work notes live in their own SQLite database and sync with xServer under the "Work Notebook" repo path tag.
 */
class WorkNotebookStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "work_notebook.db",
    null,
    1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE work_notebook (id INTEGER PRIMARY KEY CHECK(id = 1), payload TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun load(legacyNotebook: Notebook? = null): Notebook {
        val db = readableDatabase
        db.rawQuery("SELECT payload FROM work_notebook WHERE id = 1", null).use { cursor ->
            if (cursor.moveToFirst()) {
                return try {
                    JSONObject(cursor.getString(0)).toNotebook().asSingleSection()
                } catch (_: Exception) {
                    Notebook(title = "Work Notebook")
                }
            }
        }
        return (legacyNotebook?.asSingleSection() ?: Notebook(title = "Work Notebook")).also(::save)
    }

    fun save(notebook: Notebook) {
        val values = ContentValues().apply {
            put("id", 1)
            put("payload", notebook.asSingleSection().toJson().toString())
        }
        writableDatabase.insertWithOnConflict(
            "work_notebook",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun Notebook.asSingleSection(): Notebook {
        val pages = sections.flatMap { it.pages }.distinctBy { it.id }.ifEmpty { listOf(NotePage()) }
        return copy(
            title = "Work Notebook",
            sections = listOf(
                NoteSection(
                    id = sections.firstOrNull()?.id ?: "work-general",
                    title = "General",
                    repoPath = "Work Notebook",
                    pages = pages
                )
            )
        )
    }

    private fun Notebook.toJson() = JSONObject().apply {
        put("id", id)
        put("title", "Work Notebook")
        put("sections", JSONArray().apply { sections.forEach { put(it.toJson()) } })
    }

    private fun NoteSection.toJson() = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("repoPath", "Work Notebook")
        put("pages", JSONArray().apply { pages.forEach { put(it.toJson()) } })
    }

    private fun NotePage.toJson() = JSONObject().apply {
        put("id", id)
        put("clientId", clientId.ifBlank { "work:$id" })
        put("title", title)
        put("content", content)
        put("updatedAt", updatedAt)
        put("localDbId", localDbId)
        put("pcId", pcId)
        put("lastSyncedContent", lastSyncedContent)
        put("isDirty", isDirty)
    }

    private fun JSONObject.toNotebook(): Notebook {
        val sectionArray = optJSONArray("sections") ?: JSONArray()
        return Notebook(
            id = optString("id").ifBlank { UUID.randomUUID().toString() },
            title = "Work Notebook",
            sections = (0 until sectionArray.length()).map { sectionArray.getJSONObject(it).toSection() }
                .ifEmpty { listOf(NoteSection(title = "General", repoPath = "Work Notebook")) }
        )
    }

    private fun JSONObject.toSection(): NoteSection {
        val pageArray = optJSONArray("pages") ?: JSONArray()
        return NoteSection(
            id = optString("id").ifBlank { UUID.randomUUID().toString() },
            title = optString("title", "General"),
            repoPath = "Work Notebook",
            pages = (0 until pageArray.length()).map { pageArray.getJSONObject(it).toPage() }
                .ifEmpty { listOf(NotePage()) }
        )
    }

    private fun JSONObject.toPage() = NotePage(
        id = optString("id").ifBlank { UUID.randomUUID().toString() },
        clientId = optString("clientId").ifBlank { "work:" + optString("id") },
        title = optString("title", "New note"),
        content = optString("content", ""),
        updatedAt = optLong("updatedAt", System.currentTimeMillis()),
        localDbId = optLong("localDbId", 0L),
        pcId = optLong("pcId", 0L),
        lastSyncedContent = optString("lastSyncedContent", ""),
        isDirty = optBoolean("isDirty", true)
    )
}
