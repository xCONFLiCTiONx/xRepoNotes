package com.xconflictionx.xreponotes.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class NotePage(
    val id: String = UUID.randomUUID().toString(),
    val clientId: String = "local:$id",
    val title: String = "New page",
    val content: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val localDbId: Long = 0L,
    val pcId: Long = 0L,
    val lastSyncedContent: String = "",
    val isDirty: Boolean = true
)

data class NoteSection(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "General",
    val repoPath: String = "",
    val pages: List<NotePage> = listOf(NotePage())
)

data class Notebook(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Personal Notebook",
    val sections: List<NoteSection> = listOf(NoteSection())
)

class NotebookStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("xreponotes_notebooks", Context.MODE_PRIVATE)
    private val legacyPrefs = appContext.getSharedPreferences("xtexteditor_notebooks", Context.MODE_PRIVATE)

    fun load(): List<Notebook> {
        val raw = prefs.getString(KEY_NOTEBOOKS, null) ?: legacyPrefs.getString(KEY_NOTEBOOKS, null)
        if (raw == null) {
            val imported = importLegacyRepoNotes()
            save(imported)
            return imported
        }
        return try {
            val array = JSONArray(raw)
            val notebooks = (0 until array.length()).map { array.getJSONObject(it).toNotebook() }
                .map { notebook -> notebook.copy(sections = notebook.sections.map { section -> section.copy(title = projectLabel(section.title)) }) }
            val personal = notebooks.filterNot {
                it.title.equals("Work Notebook", true) || it.title.equals(VaultNotebookStore.VAULT_TITLE, true)
            }
            val work = notebooks.filter { it.title.equals("Work Notebook", true) }
            val oldSections = personal.flatMap { it.sections }
                .filterNot { isVaultRepoPath(it.repoPath) || isVaultRepoPath(it.title) }
            val generalSections = oldSections.filter {
                it.repoPath.isBlank() || it.title.equals("General", true)
            }
            val projectSections = oldSections
                .filterNot { it.repoPath.isBlank() || it.title.equals("General", true) }
                .groupBy { it.repoPath.ifBlank { it.title }.trim().lowercase() }
                .values
                .map { group -> group.first().copy(pages = group.flatMap { it.pages }) }
            val generalSection = if (generalSections.isNotEmpty()) {
                generalSections.first().copy(
                    id = "general",
                    title = "General",
                    repoPath = "",
                    pages = generalSections.flatMap { it.pages }
                )
            } else {
                NoteSection(id = "general", title = "General", repoPath = "")
            }
            val personalNotebook = Notebook(
                id = personal.firstOrNull()?.id ?: UUID.randomUUID().toString(),
                title = "Personal Notebook",
                sections = listOf(generalSection) + projectSections
            )
            listOf(personalNotebook) + work
        } catch (_: Exception) {
            listOf(Notebook())
        }
    }

    private fun importLegacyRepoNotes(): List<Notebook> {
        val legacyNotes = try {
            NotesDatabaseHelper(appContext).getAllNotes()
                .filterNot { isWorkRepoPath(it.repoPath) || isVaultRepoPath(it.repoPath) }
        } catch (_: Exception) {
            emptyList()
        }
        if (legacyNotes.isEmpty()) return listOf(Notebook())

        val sections = legacyNotes.groupBy { projectLabel(it.repoPath) }
            .map { (sectionName, notes) ->
                NoteSection(
                    title = sectionName,
                    repoPath = notes.firstOrNull()?.repoPath.orEmpty(),
                    pages = notes.map { note ->
                        NotePage(title = note.displayTitle, content = note.body, updatedAt = System.currentTimeMillis(), localDbId = note.id, pcId = note.pcId, clientId = note.clientId, lastSyncedContent = note.lastSyncedBody, isDirty = note.isDirty)
                    }
                )
            }
        val withGeneral = if (sections.any { it.repoPath.isBlank() || it.title.equals("General", true) }) {
            sections
        } else {
            listOf(NoteSection(id = "general", title = "General", repoPath = "")) + sections
        }
        return listOf(Notebook(title = "Personal Notebook", sections = withGeneral))
    }

    fun save(notebooks: List<Notebook>) {
        val data = JSONArray().apply {
            notebooks.forEach { put(it.toJson()) }
        }.toString()
        prefs.edit().putString(KEY_NOTEBOOKS, data).apply()
        legacyPrefs.edit().putString(KEY_NOTEBOOKS, data).apply()
    }

    private fun Notebook.toJson() = JSONObject().apply {
        put("id", id)
        put("title", title)
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
        put("clientId", clientId)
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
            title = optString("title", "Notebook"),
            sections = (0 until sectionArray.length()).map { sectionArray.getJSONObject(it).toSection() }.ifEmpty { listOf(NoteSection()) }
        )
    }

    private fun JSONObject.toSection(): NoteSection {
        val pageArray = optJSONArray("pages") ?: JSONArray()
        return NoteSection(
            id = optString("id").ifBlank { UUID.randomUUID().toString() },
            title = optString("title", "General"),
            repoPath = optString("repoPath", ""),
            pages = (0 until pageArray.length()).map { pageArray.getJSONObject(it).toPage() }.ifEmpty { listOf(NotePage()) }
        )
    }

    private fun JSONObject.toPage() = NotePage(
        id = optString("id").ifBlank { UUID.randomUUID().toString() },
        clientId = optString("clientId").ifBlank { "local:" + optString("id") },
        title = optString("title", "Page"),
        content = optString("content", ""),
        updatedAt = optLong("updatedAt", System.currentTimeMillis()),
        localDbId = optLong("localDbId", 0L),
        pcId = optLong("pcId", 0L),
        lastSyncedContent = optString("lastSyncedContent", ""),
        isDirty = optBoolean("isDirty", true)
    )

    companion object {
        private const val KEY_NOTEBOOKS = "notebooks_v1"
    }
}
