package com.xconflictionx.xreponotes.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Vault notes live in their own SQLite database and sync with xServer under
 * "Vault - Personal" and "Vault - Work" repo path tags.
 */
class VaultNotebookStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "vault_notebook.db",
    null,
    1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE vault_notebook (id INTEGER PRIMARY KEY CHECK(id = 1), payload TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun load(legacySections: List<NoteSection> = emptyList()): Notebook {
        val db = readableDatabase
        db.rawQuery("SELECT payload FROM vault_notebook WHERE id = 1", null).use { cursor ->
            if (cursor.moveToFirst()) {
                return try {
                    JSONObject(cursor.getString(0)).toNotebook().asVaultSections()
                } catch (_: Exception) {
                    defaultVaultNotebook()
                }
            }
        }
        val initial = if (legacySections.isNotEmpty()) {
            Notebook(title = VAULT_TITLE, sections = legacySections).asVaultSections()
        } else {
            defaultVaultNotebook()
        }
        return initial.also(::save)
    }

    fun save(notebook: Notebook) {
        val values = ContentValues().apply {
            put("id", 1)
            put("payload", notebook.asVaultSections().toJson().toString())
        }
        writableDatabase.insertWithOnConflict(
            "vault_notebook",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun defaultVaultNotebook(): Notebook = Notebook(
        title = VAULT_TITLE,
        sections = listOf(
            NoteSection(id = SECTION_PERSONAL_ID, title = "Personal", repoPath = VAULT_PERSONAL_REPO_PATH),
            NoteSection(id = SECTION_WORK_ID, title = "Work", repoPath = VAULT_WORK_REPO_PATH)
        )
    )

    private fun Notebook.asVaultSections(): Notebook {
        val personalPages = sections
            .filter { !isVaultWorkRepoPath(it.repoPath) && it.id != SECTION_WORK_ID }
            .flatMap { it.pages }
            .distinctBy { it.id }
            .ifEmpty { listOf(NotePage()) }
        val workPages = sections
            .filter { isVaultWorkRepoPath(it.repoPath) || it.id == SECTION_WORK_ID }
            .flatMap { it.pages }
            .distinctBy { it.id }
            .ifEmpty { listOf(NotePage()) }
        return copy(
            title = VAULT_TITLE,
            sections = listOf(
                NoteSection(
                    id = SECTION_PERSONAL_ID,
                    title = "Personal",
                    repoPath = VAULT_PERSONAL_REPO_PATH,
                    pages = personalPages
                ),
                NoteSection(
                    id = SECTION_WORK_ID,
                    title = "Work",
                    repoPath = VAULT_WORK_REPO_PATH,
                    pages = workPages
                )
            )
        )
    }

    private fun Notebook.toJson() = JSONObject().apply {
        put("id", id)
        put("title", VAULT_TITLE)
        put("sections", JSONArray().apply { sections.forEach { put(it.toJson()) } })
    }

    private fun NoteSection.toJson() = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("repoPath", repoPath)
        put("pages", JSONArray().apply { pages.forEach { put(it.toJson()) } })
    }

    private fun NotePage.toJson() = JSONObject().apply {
        put("id", id)
        put("clientId", clientId.ifBlank { "vault:$id" })
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
            title = VAULT_TITLE,
            sections = (0 until sectionArray.length()).map { sectionArray.getJSONObject(it).toSection() }
                .ifEmpty { defaultVaultNotebook().sections }
        )
    }

    private fun JSONObject.toSection(): NoteSection {
        val pageArray = optJSONArray("pages") ?: JSONArray()
        val id = optString("id").ifBlank { UUID.randomUUID().toString() }
        val repoPath = optString("repoPath", VAULT_PERSONAL_REPO_PATH)
        val isWork = isVaultWorkRepoPath(repoPath) || id == SECTION_WORK_ID
        return NoteSection(
            id = if (isWork) SECTION_WORK_ID else SECTION_PERSONAL_ID,
            title = if (isWork) "Work" else "Personal",
            repoPath = if (isWork) VAULT_WORK_REPO_PATH else VAULT_PERSONAL_REPO_PATH,
            pages = (0 until pageArray.length()).map { pageArray.getJSONObject(it).toPage() }
                .ifEmpty { listOf(NotePage()) }
        )
    }

    private fun JSONObject.toPage() = NotePage(
        id = optString("id").ifBlank { UUID.randomUUID().toString() },
        clientId = optString("clientId").ifBlank { "vault:" + optString("id") },
        title = optString("title", "New note"),
        content = optString("content", ""),
        updatedAt = optLong("updatedAt", System.currentTimeMillis()),
        localDbId = optLong("localDbId", 0L),
        pcId = optLong("pcId", 0L),
        lastSyncedContent = optString("lastSyncedContent", ""),
        isDirty = optBoolean("isDirty", true)
    )

    companion object {
        const val VAULT_TITLE = "Vault"
        const val SECTION_PERSONAL_ID = "vault-personal"
        const val SECTION_WORK_ID = "vault-work"
    }
}

