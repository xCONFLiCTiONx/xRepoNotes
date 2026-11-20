package cc.xconflictionx.reponotes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class NotesApi(
    private val baseUrl: String,
    private val apiKey: String
) {
    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null
    ): String = withContext(Dispatchers.IO) {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-RepoNotes-Key", apiKey)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(response).optString("error") }.getOrNull().orEmpty()
                throw ApiException(status, detail.ifBlank { "Server returned HTTP $status" })
            }
            response
        } finally {
            connection.disconnect()
        }
    }

    suspend fun getNotes(): List<Note> {
        val array = JSONArray(request("GET", "/api/notes"))
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            Note(
                id = item.getLong("id"),
                repoPath = item.optString("repoPath", ""),
                title = item.optString("title", ""),
                body = item.optString("body", ""),
                createdUtc = item.optString("createdUtc", ""),
                updatedUtc = item.optString("updatedUtc", "")
            )
        }
    }

    suspend fun createNote(repoPath: String, title: String, body: String): Note {
        val json = JSONObject()
            .put("repoPath", repoPath)
            .put("title", title)
            .put("body", body)
        return parseNote(JSONObject(request("POST", "/api/notes", json)))
    }

    suspend fun updateNote(note: Note, title: String, body: String, repoPath: String): Note {
        val json = JSONObject()
            .put("repoPath", repoPath)
            .put("title", title)
            .put("body", body)
            .put("createdUtc", note.createdUtc)
            .put("expectedUpdatedUtc", note.updatedUtc)
        return parseNote(JSONObject(request("PUT", "/api/notes/${note.id}", json)))
    }

    suspend fun deleteNote(note: Note) {
        val encoded = java.net.URLEncoder.encode(note.updatedUtc, "UTF-8")
        request("DELETE", "/api/notes/${note.id}?expectedUpdatedUtc=$encoded")
    }

    private fun parseNote(item: JSONObject) = Note(
        id = item.getLong("id"),
        repoPath = item.optString("repoPath", ""),
        title = item.optString("title", ""),
        body = item.optString("body", ""),
        createdUtc = item.optString("createdUtc", ""),
        updatedUtc = item.optString("updatedUtc", "")
    )
}

class ApiException(val status: Int, message: String) : Exception(message)
