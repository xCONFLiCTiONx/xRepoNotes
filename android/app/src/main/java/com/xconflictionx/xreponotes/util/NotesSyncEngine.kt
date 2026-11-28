package com.xconflictionx.xreponotes.util

import android.content.Context
import com.xconflictionx.xreponotes.data.NotesDatabaseHelper
import com.xconflictionx.xreponotes.data.RepoNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

sealed class SyncResult {
    data class Success(
        val count: Int,
        val conflicts: Int,
        val message: String,
        val hasChanges: Boolean = true
    ) : SyncResult()
    data object Deferred : SyncResult()
    data class Failure(val errorMessage: String) : SyncResult()
}

object NotesSyncEngine {
    const val DEFAULT_SERVER_URL = ""
    const val PREF_KEY_SERVER_URL = "xreponotes_server_url"

    data class ServerRepository(val path: String, val name: String)

    suspend fun getRepositories(
        serverUrlInput: String? = null,
        apiKeyInput: String? = null,
        context: Context
    ): List<ServerRepository> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("xreponotes_prefs", Context.MODE_PRIVATE)
        val legacyPrefs = context.getSharedPreferences("xtexteditor_prefs", Context.MODE_PRIVATE)
        val serverUrl = (serverUrlInput ?: prefs.getString(PREF_KEY_SERVER_URL, legacyPrefs.getString(PREF_KEY_SERVER_URL, DEFAULT_SERVER_URL)) ?: DEFAULT_SERVER_URL).trim().trimEnd('/')
        val apiKey = (apiKeyInput ?: ApiKeyStore.read(context)).orEmpty().trim()
        if (serverUrl.isBlank() || apiKey.isBlank()) return@withContext emptyList()
        val array = JSONArray(request("$serverUrl/api/repositories", "GET", apiKey))
        (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            ServerRepository(item.optString("path", ""), item.optString("name", ""))
        }.filter { it.path.isNotBlank() }
    }

    private data class ServerNote(
        val id: Long,
        val clientId: String,
        val repoPath: String,
        val title: String,
        val body: String,
        val createdUtc: String,
        val updatedUtc: String
    )

    suspend fun syncNotes(
        context: Context,
        serverUrlInput: String? = null,
        apiKeyInput: String? = null
    ): SyncResult = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("xreponotes_prefs", Context.MODE_PRIVATE)
        val legacyPrefs = context.getSharedPreferences("xtexteditor_prefs", Context.MODE_PRIVATE)
        val serverUrl = (serverUrlInput
            ?: prefs.getString(PREF_KEY_SERVER_URL, legacyPrefs.getString(PREF_KEY_SERVER_URL, DEFAULT_SERVER_URL))
            ?: DEFAULT_SERVER_URL).trim().trimEnd('/')
        val apiKey = (apiKeyInput ?: ApiKeyStore.read(context)).orEmpty().trim()

        if (serverUrl.isBlank()) return@withContext SyncResult.Failure("Enter the xRepoNotes server URL in Settings.")
        if (apiKey.isBlank()) return@withContext SyncResult.Failure("Enter your xRepoNotes API key in Settings.")

        try {
            NoteImageStore.uploadPendingImages(context, serverUrl, apiKey)
            val database = NotesDatabaseHelper(context)
            val localNotes = database.getAllNotes(includeDeleted = true)
            var remoteNotes = getRemoteNotes(serverUrl, apiKey)
            val deletedNotes = localNotes.filter { it.isDeleted }
            for (deleted in deletedNotes) {
                val remote = remoteNotes.firstOrNull { it.id == deleted.pcId && deleted.pcId > 0L }
                    ?: remoteNotes.firstOrNull { it.clientId == deleted.clientId }
                if (remote != null) {
                    val changedRemotely = if (deleted.isDirty) {
                        remote.body != deleted.lastSyncedBody
                    } else {
                        remote.updatedUtc != deleted.updatedUtc
                    }
                    if (deleted.pcId > 0L && changedRemotely)
                        throw HttpStatusException(409, "A note changed on xServer after deletion was queued. The local deletion is retained for review.")
                    deleteRemoteNote(serverUrl, apiKey, remote)
                }
                database.purgeNote(deleted.id)
            }
            val activeLocalNotes = localNotes.filterNot { it.isDeleted }
            if (remoteNotes.isEmpty() && activeLocalNotes.any { it.pcId > 0L })
                throw IOException("The xServer database returned no notes even though this device has previously synced notes. Local data was kept; refusing to upload or replace it automatically.")
            remoteNotes = getRemoteNotes(serverUrl, apiKey)
            val matchedLocalIds = mutableSetOf<Long>()
            var conflictCount = 0

            // Reconcile each server record with its matching local copy.
            for (remote in remoteNotes) {
                val local = activeLocalNotes.firstOrNull { it.pcId == remote.id && remote.id > 0L }
                    ?: activeLocalNotes.firstOrNull { it.clientId == remote.clientId && remote.clientId.isNotBlank() }
                if (local != null) matchedLocalIds.add(local.id)

                val baseBody = local?.let {
                    if (it.pcId == 0L) remote.body else it.lastSyncedBody.ifEmpty { it.body }
                } ?: remote.body
                val mergedBody = if (local != null && local.isDirty) {
                    if (local.body != remote.body && remote.body != baseBody && local.body != baseBody) conflictCount++
                    mergeContent(baseBody, remote.body, local.body)
                } else {
                    remote.body
                }
                val title = if (local != null && local.isDirty) local.title else remote.title
                val repoPath = if (local != null && local.isDirty) local.repoPath else remote.repoPath

                val savedRemote = if (
                    local != null && local.isDirty &&
                    (mergedBody != remote.body || title != remote.title || repoPath != remote.repoPath)
                ) {
                    updateRemoteNote(serverUrl, apiKey, remote, title, repoPath, mergedBody)
                } else {
                    remote
                }

                val synced = RepoNote(
                    id = local?.id ?: 0L,
                    pcId = savedRemote.id,
                    clientId = savedRemote.clientId.ifBlank { local?.clientId ?: "legacy:" + savedRemote.id },
                    repoPath = savedRemote.repoPath,
                    title = savedRemote.title,
                    body = savedRemote.body,
                    createdUtc = savedRemote.createdUtc,
                    updatedUtc = savedRemote.updatedUtc,
                    lastSyncedBody = savedRemote.body,
                    isDirty = false
                )
                if (local != null) database.updateNote(synced.copy(id = local.id))
                else database.insertNote(synced)
            }

            // Upload local-only notes. The server assigns the authoritative ID.
            for (local in activeLocalNotes.filter { it.id !in matchedLocalIds }) {
                if (local.pcId > 0L && !local.isDirty) {
                    database.purgeNote(local.id)
                    continue
                }
                val isBlankPlaceholder = (local.title.isBlank() ||
                    local.title.equals("New page", ignoreCase = true) ||
                    local.title.equals("New note", ignoreCase = true)) && local.body.isBlank()
                if (isBlankPlaceholder) {
                    database.purgeNote(local.id)
                    continue
                }
                val created = createRemoteNote(serverUrl, apiKey, local)
                database.updateNote(
                    local.copy(
                        pcId = created.id,
                        repoPath = created.repoPath,
                        title = created.title,
                        body = created.body,
                        createdUtc = created.createdUtc,
                        updatedUtc = created.updatedUtc,
                        lastSyncedBody = created.body,
                        isDirty = false
                    )
                )
            }

            val total = remoteNotes.size + activeLocalNotes.count { it.id !in matchedLocalIds }
            SyncResult.Success(
                count = total,
                conflicts = conflictCount,
                message = if (conflictCount > 0) {
                    "Synced $total notes; merged $conflictCount conflicting edit(s) with [M]/[D] tags"
                } else {
                    "Synced $total note(s) with the xServer database"
                }
            )
        } catch (e: HttpStatusException) {
            val message = when (e.statusCode) {
                401 -> "The xRepoNotes API key was rejected. Check the key in Settings."
                409 -> "A note changed on the server during sync. Sync again to reconcile the latest version."
                else -> "xRepoNotes returned HTTP ${e.statusCode}: ${e.message}"
            }
            SyncResult.Failure(message)
        } catch (e: Exception) {
            SyncResult.Failure("Sync failed: ${e.localizedMessage ?: "Could not connect to xRepoNotes at $serverUrl"}")
        }
    }

    private fun getRemoteNotes(serverUrl: String, apiKey: String): List<ServerNote> {
        val response = request("$serverUrl/api/notes", "GET", apiKey)
        val array = JSONArray(response)
        return (0 until array.length()).map { index -> parseServerNote(array.getJSONObject(index)) }
    }

    private fun createRemoteNote(serverUrl: String, apiKey: String, local: RepoNote): ServerNote {
        val payload = JSONObject().apply {
            put("clientId", local.clientId)
            put("repoPath", local.repoPath)
            put("title", local.title)
            put("body", local.body)
            put("createdUtc", local.createdUtc)
            put("updatedUtc", local.updatedUtc)
        }
        return parseServerNote(JSONObject(request("$serverUrl/api/notes", "POST", apiKey, payload)))
    }

    private fun updateRemoteNote(
        serverUrl: String,
        apiKey: String,
        remote: ServerNote,
        title: String,
        repoPath: String,
        body: String
    ): ServerNote {
        val payload = JSONObject().apply {
            put("clientId", remote.clientId)
            put("repoPath", repoPath)
            put("title", title)
            put("body", body)
            put("createdUtc", remote.createdUtc)
            put("updatedUtc", remote.updatedUtc)
            put("expectedUpdatedUtc", remote.updatedUtc)
        }
        return parseServerNote(JSONObject(request("$serverUrl/api/notes/${remote.id}", "PUT", apiKey, payload)))
    }

    private fun deleteRemoteNote(serverUrl: String, apiKey: String, remote: ServerNote) {
        val expected = java.net.URLEncoder.encode(remote.updatedUtc, Charsets.UTF_8.name())
        request("$serverUrl/api/notes/${remote.id}?expectedUpdatedUtc=$expected", "DELETE", apiKey, allowNotFound = true)
    }

    private fun parseServerNote(obj: JSONObject): ServerNote = ServerNote(
        id = obj.getLong("id"),
        clientId = obj.optString("clientId", ""),
        repoPath = obj.optString("repoPath", ""),
        title = obj.optString("title", ""),
        body = obj.optString("body", ""),
        createdUtc = obj.optString("createdUtc", RepoNote.getCurrentIsoUtcString()),
        updatedUtc = obj.optString("updatedUtc", RepoNote.getCurrentIsoUtcString())
    )

    private fun request(
        targetUrl: String,
        method: String,
        apiKey: String,
        payload: JSONObject? = null,
        allowNotFound: Boolean = false
    ): String {
        val connection = URL(targetUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-RepoNotes-Key", apiKey)
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
            }.orEmpty()
            if (status !in 200..299 && !(allowNotFound && status == 404)) throw HttpStatusException(status, response)
            return response
        } finally {
            connection.disconnect()
        }
    }

    private class HttpStatusException(val statusCode: Int, message: String) :
        IOException("HTTP $statusCode${if (message.isNotBlank()) ": $message" else ""}")

    fun mergeContent(baseText: String, desktopText: String, mobileText: String): String {
        if (desktopText == mobileText) return mobileText
        if (desktopText == baseText) return mobileText
        if (mobileText == baseText) return desktopText

        val baseLines = baseText.split("\n")
        val desktopLines = desktopText.split("\n")
        val mobileLines = mobileText.split("\n")
        val maxLines = max(desktopLines.size, mobileLines.size)
        val resultLines = mutableListOf<String>()

        for (i in 0 until maxLines) {
            val dLine = desktopLines.getOrNull(i)
            val mLine = mobileLines.getOrNull(i)
            val bLine = baseLines.getOrNull(i)
            when {
                dLine == mLine && dLine != null -> resultLines.add(dLine)
                mLine != null && bLine != null && mLine == bLine && dLine != null -> resultLines.add(dLine)
                dLine != null && bLine != null && dLine == bLine && mLine != null -> resultLines.add(mLine)
                else -> {
                    if (mLine != null) resultLines.add(if (mLine.startsWith("[M]")) mLine else "[M] $mLine")
                    if (dLine != null) resultLines.add(if (dLine.startsWith("[D]")) dLine else "[D] $dLine")
                }
            }
        }
        return resultLines.joinToString("\n")
    }
}
