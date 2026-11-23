package com.xconflictionx.xreponotes.util

import android.content.Context
import com.xconflictionx.xreponotes.data.NotePage
import com.xconflictionx.xreponotes.data.NoteSection
import com.xconflictionx.xreponotes.data.Notebook
import com.xconflictionx.xreponotes.data.NotebookStore
import com.xconflictionx.xreponotes.data.VaultNotebookStore
import com.xconflictionx.xreponotes.data.WorkNotebookStore
import com.xconflictionx.xreponotes.data.NotesDatabaseHelper
import com.xconflictionx.xreponotes.data.RepoNote
import com.xconflictionx.xreponotes.data.VAULT_PERSONAL_REPO_PATH
import com.xconflictionx.xreponotes.data.VAULT_WORK_REPO_PATH
import com.xconflictionx.xreponotes.data.WORK_NOTEBOOK_REPO_PATH
import com.xconflictionx.xreponotes.data.isVaultPersonalRepoPath
import com.xconflictionx.xreponotes.data.isVaultRepoPath
import com.xconflictionx.xreponotes.data.isVaultWorkRepoPath
import com.xconflictionx.xreponotes.data.isWorkRepoPath
import com.xconflictionx.xreponotes.data.projectLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Keeps the Android Personal Notebook and Work Notebook synced over the shared xRepoNotes records.
 */
object NotebookSyncEngine {
    private val syncMutex = Mutex()

    /**
     * True while the user is actively creating or editing a note in the UI.
     * Background and auto-syncs defer until editing finishes so in-progress
     * typing is never interrupted or overwritten.
     */
    @Volatile
    var isEditingNote: Boolean = false

    fun stablePageId(clientId: String, pcId: Long = 0L): String {
        val stableId = if (clientId.isNotBlank()) clientId else "server:$pcId"
        return "note-" + UUID.nameUUIDFromBytes(stableId.toByteArray(Charsets.UTF_8)).toString()
    }

    private fun notebooksFingerprint(notebooks: List<Notebook>): String = buildString {
        for (nb in notebooks) {
            append(nb.title).append('|')
            for (sec in nb.sections) {
                append(sec.title).append(':').append(sec.repoPath).append('[')
                for (p in sec.pages) {
                    append(p.id).append('#')
                        .append(p.clientId).append('#')
                        .append(p.pcId).append('#')
                        .append(p.title).append('#')
                        .append(p.content).append('#')
                        .append(p.isDirty).append(';')
                }
                append(']')
            }
            append('\n')
        }
    }

    suspend fun sync(
        context: Context,
        serverUrl: String,
        apiKey: String,
        force: Boolean = false
    ): SyncResult = syncMutex.withLock {
        if (!force && isEditingNote) return@withLock SyncResult.Deferred
        withContext(Dispatchers.IO) {
            val store = NotebookStore(context)
            val savedNotebooks = store.load()
            val legacyWorkNotebook = savedNotebooks.firstOrNull { it.title.equals("Work Notebook", true) }
            val workStore = WorkNotebookStore(context)
            val workNotebook = try {
                workStore.load(legacyWorkNotebook)
            } finally {
                workStore.close()
            }
            val vaultStore = VaultNotebookStore(context)
            val vaultNotebook = try {
                vaultStore.load()
            } finally {
                vaultStore.close()
            }
            val personalNotebooks = savedNotebooks.filterNot {
                it.title.equals("Work Notebook", true) || it.title.equals(VaultNotebookStore.VAULT_TITLE, true)
            }
            val personal = personalNotebooks.firstOrNull() ?: Notebook(title = "Personal Notebook")
            val beforeFingerprint = notebooksFingerprint(listOf(personal, workNotebook, vaultNotebook))
            val database = NotesDatabaseHelper(context)
            val catalog = try {
                NotesSyncEngine.getRepositories(serverUrl, apiKey, context)
            } catch (_: Exception) {
                emptyList()
            }
            val catalogByName = catalog.associateBy { it.name.trim().lowercase() }
            val catalogByPath = catalog.associateBy { it.path.trim().lowercase() }

            // Convert legacy sections to real server project paths when a catalog is available.
            val normalizedSections = personalNotebooks.flatMap { it.sections }
                .filterNot { isWorkRepoPath(it.repoPath) || isVaultRepoPath(it.repoPath) || isVaultRepoPath(it.title) }
                .map { section ->
                val isGeneral = section.repoPath.isBlank() && section.title.equals("General", true)
                if (isGeneral) {
                    section.copy(title = "General", repoPath = "")
                } else if (catalog.isEmpty()) {
                    section
                } else {
                    val match = catalogByPath[section.repoPath.trim().lowercase()]
                        ?: catalogByName[section.title.trim().lowercase()]
                    section.copy(
                        title = match?.name ?: "General",
                        repoPath = match?.path.orEmpty()
                    )
                }
            }

            val normalizedPersonal = personal.copy(
                title = "Personal Notebook",
                sections = normalizedSections.ifEmpty { listOf(NoteSection()) }
            )
            val existingNotes = database.getAllNotes(includeDeleted = true)
            val activeExisting = existingNotes.filterNot { it.isDeleted }

            // Stage Personal Notebook pages into database
            for (section in normalizedPersonal.sections) {
                for (page in section.pages) {
                    val isBlankPlaceholder = (page.title.isBlank() ||
                        page.title.equals("New page", ignoreCase = true) ||
                        page.title.equals("New note", ignoreCase = true)) && page.content.isBlank()
                    if (isBlankPlaceholder && page.pcId == 0L) {
                        if (page.localDbId > 0L) database.purgeNote(page.localDbId)
                        continue
                    }

                    val existing = activeExisting.firstOrNull {
                        page.clientId.isNotBlank() && it.clientId == page.clientId
                    } ?: activeExisting.firstOrNull {
                        page.pcId > 0L && it.pcId == page.pcId
                    } ?: activeExisting.firstOrNull {
                        page.localDbId > 0L && it.id == page.localDbId
                    }

                    val now = RepoNote.getCurrentIsoUtcString()
                    val record = RepoNote(
                        id = existing?.id ?: page.localDbId,
                        pcId = page.pcId.takeIf { it > 0L } ?: existing?.pcId ?: 0L,
                        clientId = page.clientId.ifBlank { existing?.clientId ?: "local:" + page.id },
                        repoPath = section.repoPath,
                        title = page.title,
                        body = page.content,
                        createdUtc = existing?.createdUtc ?: now,
                        updatedUtc = if (page.isDirty) now else existing?.updatedUtc ?: now,
                        lastSyncedBody = page.lastSyncedContent.ifEmpty { existing?.lastSyncedBody ?: "" },
                        isDirty = page.isDirty || existing?.isDirty == true
                    )
                    if (existing != null) database.updateNote(record.copy(id = existing.id))
                    else database.insertNote(record)
                }
            }

            // Stage Work Notebook pages into database under "Work Notebook" repo path
            for (section in workNotebook.sections) {
                for (page in section.pages) {
                    if (page.title == "New note" && page.content.isBlank() &&
                        page.localDbId == 0L && page.pcId == 0L) continue

                    val existing = activeExisting.firstOrNull {
                        page.clientId.isNotBlank() && it.clientId == page.clientId
                    } ?: activeExisting.firstOrNull {
                        page.pcId > 0L && it.pcId == page.pcId
                    } ?: activeExisting.firstOrNull {
                        page.localDbId > 0L && it.id == page.localDbId
                    }

                    val now = RepoNote.getCurrentIsoUtcString()
                    val record = RepoNote(
                        id = existing?.id ?: page.localDbId,
                        pcId = page.pcId.takeIf { it > 0L } ?: existing?.pcId ?: 0L,
                        clientId = page.clientId.ifBlank { existing?.clientId ?: "work:" + page.id },
                        repoPath = "Work Notebook",
                        title = page.title,
                        body = page.content,
                        createdUtc = existing?.createdUtc ?: now,
                        updatedUtc = if (page.isDirty) now else existing?.updatedUtc ?: now,
                        lastSyncedBody = page.lastSyncedContent.ifEmpty { existing?.lastSyncedBody ?: "" },
                        isDirty = page.isDirty || existing?.isDirty == true
                    )
                    if (existing != null) database.updateNote(record.copy(id = existing.id))
                    else database.insertNote(record)
                }
            }

            // Stage Vault pages into database under "Vault - Personal" / "Vault - Work" repo paths
            for (section in vaultNotebook.sections) {
                val targetRepoPath = if (
                    section.id == VaultNotebookStore.SECTION_WORK_ID || isVaultWorkRepoPath(section.repoPath)
                ) VAULT_WORK_REPO_PATH else VAULT_PERSONAL_REPO_PATH
                for (page in section.pages) {
                    val isBlankPlaceholder = (page.title.isBlank() ||
                        page.title.equals("New page", ignoreCase = true) ||
                        page.title.equals("New note", ignoreCase = true)) && page.content.isBlank()
                    if (isBlankPlaceholder && page.pcId == 0L) {
                        if (page.localDbId > 0L) database.purgeNote(page.localDbId)
                        continue
                    }

                    val existing = activeExisting.firstOrNull {
                        page.clientId.isNotBlank() && it.clientId == page.clientId
                    } ?: activeExisting.firstOrNull {
                        page.pcId > 0L && it.pcId == page.pcId
                    } ?: activeExisting.firstOrNull {
                        page.localDbId > 0L && it.id == page.localDbId
                    }

                    val now = RepoNote.getCurrentIsoUtcString()
                    val record = RepoNote(
                        id = existing?.id ?: page.localDbId,
                        pcId = page.pcId.takeIf { it > 0L } ?: existing?.pcId ?: 0L,
                        clientId = page.clientId.ifBlank { existing?.clientId ?: "vault:" + page.id },
                        repoPath = targetRepoPath,
                        title = page.title,
                        body = page.content,
                        createdUtc = existing?.createdUtc ?: now,
                        updatedUtc = if (page.isDirty) now else existing?.updatedUtc ?: now,
                        lastSyncedBody = page.lastSyncedContent.ifEmpty { existing?.lastSyncedBody ?: "" },
                        isDirty = page.isDirty || existing?.isDirty == true
                    )
                    if (existing != null) database.updateNote(record.copy(id = existing.id))
                    else database.insertNote(record)
                }
            }

            val result = NotesSyncEngine.syncNotes(context, serverUrl, apiKey)
            if (result !is SyncResult.Success) return@withContext result

            val syncedRecords = database.getAllNotes()
            val canonicalRecords = syncedRecords.map { record ->
                if (isWorkRepoPath(record.repoPath)) {
                    if (record.repoPath != WORK_NOTEBOOK_REPO_PATH) {
                        val updated = record.copy(repoPath = WORK_NOTEBOOK_REPO_PATH, isDirty = true)
                        database.updateNote(updated)
                        updated
                    } else record
                } else if (isVaultPersonalRepoPath(record.repoPath)) {
                    if (record.repoPath != VAULT_PERSONAL_REPO_PATH) {
                        val updated = record.copy(repoPath = VAULT_PERSONAL_REPO_PATH, isDirty = true)
                        database.updateNote(updated)
                        updated
                    } else record
                } else if (isVaultWorkRepoPath(record.repoPath)) {
                    if (record.repoPath != VAULT_WORK_REPO_PATH) {
                        val updated = record.copy(repoPath = VAULT_WORK_REPO_PATH, isDirty = true)
                        database.updateNote(updated)
                        updated
                    } else record
                } else if (catalog.isEmpty()) {
                    record
                } else {
                    val oldSectionName = legacySectionName(record.repoPath)
                    val project = catalogByPath[record.repoPath.trim().lowercase()]
                        ?: catalogByName[oldSectionName.trim().lowercase()]
                        ?: catalogByName[projectLabel(record.repoPath).trim().lowercase()]
                    val canonicalPath = project?.path.orEmpty()
                    if (record.repoPath != canonicalPath) {
                        val updated = record.copy(repoPath = canonicalPath, isDirty = true)
                        database.updateNote(updated)
                        updated
                    } else record
                }
            }

            // Build Personal Notebook
            val generalNotes = canonicalRecords.filter { it.repoPath.isBlank() }
            val sections = mutableListOf<NoteSection>()
            sections += NoteSection(
                id = "general",
                title = "General",
                repoPath = "",
                pages = generalNotes.map { it.toPage() }.ifEmpty { listOf(NotePage()) }
            )
            val effectiveCatalog = if (catalog.isNotEmpty()) {
                catalog
            } else {
                val discoveredPaths = (canonicalRecords.map { it.repoPath } +
                    normalizedPersonal.sections.map { it.repoPath })
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !isWorkRepoPath(it) && !isVaultRepoPath(it) }
                    .distinctBy { it.lowercase() }
                discoveredPaths.map { path ->
                    NotesSyncEngine.ServerRepository(path = path, name = projectLabel(path))
                }
            }
            for (project in effectiveCatalog) {
                val projectNotes = canonicalRecords.filter { it.repoPath.equals(project.path, true) }
                sections += NoteSection(
                    id = "project-" + UUID.nameUUIDFromBytes(project.path.toByteArray(Charsets.UTF_8)).toString(),
                    title = project.name,
                    repoPath = project.path,
                    pages = projectNotes.map { it.toPage() }.ifEmpty { listOf(NotePage()) }
                )
            }

            val rebuiltPersonal = normalizedPersonal.copy(
                title = "Personal Notebook",
                sections = sections
            )

            // Build Work Notebook from synced records with repoPath == "Work Notebook"
            val workNotes = canonicalRecords.filter { isWorkRepoPath(it.repoPath) }
            val rebuiltWork = workNotebook.copy(
                title = "Work Notebook",
                sections = listOf(
                    NoteSection(
                        id = workNotebook.sections.firstOrNull()?.id ?: "work-general",
                        title = "General",
                        repoPath = WORK_NOTEBOOK_REPO_PATH,
                        pages = workNotes.map { it.toPage() }.ifEmpty { listOf(NotePage()) }
                    )
                )
            )

            // Build Vault Notebook from synced records with repoPath == "Vault - Personal" / "Vault - Work"
            val vaultPersonalNotes = canonicalRecords.filter { isVaultPersonalRepoPath(it.repoPath) }
            val vaultWorkNotes = canonicalRecords.filter { isVaultWorkRepoPath(it.repoPath) }
            val rebuiltVault = vaultNotebook.copy(
                title = VaultNotebookStore.VAULT_TITLE,
                sections = listOf(
                    NoteSection(
                        id = VaultNotebookStore.SECTION_PERSONAL_ID,
                        title = "Personal",
                        repoPath = VAULT_PERSONAL_REPO_PATH,
                        pages = vaultPersonalNotes.map { it.toPage() }.ifEmpty { listOf(NotePage()) }
                    ),
                    NoteSection(
                        id = VaultNotebookStore.SECTION_WORK_ID,
                        title = "Work",
                        repoPath = VAULT_WORK_REPO_PATH,
                        pages = vaultWorkNotes.map { it.toPage() }.ifEmpty { listOf(NotePage()) }
                    )
                )
            )

            if (!force && isEditingNote) return@withContext SyncResult.Deferred

            val afterFingerprint = notebooksFingerprint(listOf(rebuiltPersonal, rebuiltWork, rebuiltVault))
            val hasChanges = afterFingerprint != beforeFingerprint
            if (hasChanges) {
                store.save(listOf(rebuiltPersonal))
                workStore.save(rebuiltWork)
                vaultStore.save(rebuiltVault)
            }
            result.copy(hasChanges = hasChanges)
        }
    }

    private fun RepoNote.toPage(): NotePage {
        return NotePage(
            id = stablePageId(clientId, pcId),
            clientId = clientId,
            title = title,
            content = body,
            updatedAt = System.currentTimeMillis(),
            localDbId = id,
            pcId = pcId,
            lastSyncedContent = body,
            isDirty = false
        )
    }

    private fun legacySectionName(path: String): String {
        val parts = path.split('/')
        return if (parts.size == 6 && (parts[0] == "xTextEditor" || parts[0] == "xRepoNotes")) {
            try { android.net.Uri.decode(parts[5]) } catch (_: Exception) { "" }
        } else ""
    }
}
