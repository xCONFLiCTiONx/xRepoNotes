package com.xconflictionx.xreponotes.util

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object NoteImageStore {
    private val imageMarkdownRegex = Regex("!\\[([^\\]]*)]\\(([^)]+)\\)")
    private val internalImageRegex = Regex("!\\[[^\\]]*]\\(xreponotes-image://([a-zA-Z0-9_-]+)\\)")

    private fun imageDir(context: Context): File =
        File(context.filesDir, "note_images").apply { mkdirs() }

    private fun imageFile(context: Context, id: String): File =
        File(imageDir(context), "$id.png")

    private fun pendingFile(context: Context, id: String): File =
        File(imageDir(context), "$id.pending")

    fun hasClipboardImage(context: Context): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val clip = clipboard.primaryClip ?: return false
        if (clip.itemCount == 0) return false
        val uri = clip.getItemAt(0).uri ?: return false
        val mime = context.contentResolver.getType(uri)
        return mime?.startsWith("image/", ignoreCase = true) == true || clip.description.hasMimeType("image/*")
    }

    suspend fun saveClipboardImage(context: Context): String? = withContext(Dispatchers.IO) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@withContext null
        val clip = clipboard.primaryClip ?: return@withContext null
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            val saved = saveImageUri(context, uri)
            if (saved != null) return@withContext saved
        }
        null
    }

    suspend fun saveImageUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri)
        if (mime != null && !mime.startsWith("image/", ignoreCase = true)) return@withContext null

        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        if (rawBytes.isEmpty()) return@withContext null

        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return@withContext null
        val scaled = scaleDownIfNeeded(decoded, maxDimension = 2048)
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()

        val id = UUID.randomUUID().toString().replace("-", "")
        imageFile(context, id).writeBytes(output.toByteArray())
        pendingFile(context, id).writeText("image/png")
        id
    }

    suspend fun uploadImage(context: Context, imageId: String, serverUrlInput: String, apiKeyInput: String): Boolean =
        withContext(Dispatchers.IO) {
            val serverUrl = serverUrlInput.trim().trimEnd('/')
            val apiKey = apiKeyInput.trim()
            if (serverUrl.isBlank() || apiKey.isBlank()) return@withContext false

            val file = imageFile(context, imageId)
            if (!file.exists()) return@withContext false

            val base64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            val payload = JSONObject()
                .put("id", imageId)
                .put("mimeType", "image/png")
                .put("dataBase64", base64)

            val connection = (URL("$serverUrl/api/images").openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 20000
                connection.doOutput = true
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("X-RepoNotes-Key", apiKey)
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
                val ok = connection.responseCode in 200..299
                if (ok) pendingFile(context, imageId).delete()
                ok
            } catch (_: Exception) {
                false
            } finally {
                connection.disconnect()
            }
        }

    suspend fun uploadPendingImages(context: Context, serverUrl: String, apiKey: String) = withContext(Dispatchers.IO) {
        val pending = imageDir(context).listFiles { _, name -> name.endsWith(".pending") } ?: return@withContext
        for (marker in pending) {
            val id = marker.name.removeSuffix(".pending")
            uploadImage(context, id, serverUrl, apiKey)
        }
    }

    suspend fun loadImageBitmap(context: Context, imageId: String, serverUrlInput: String, apiKeyInput: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val cached = imageFile(context, imageId)
            if (cached.exists()) {
                return@withContext BitmapFactory.decodeFile(cached.absolutePath)
            }

            val serverUrl = serverUrlInput.trim().trimEnd('/')
            val apiKey = apiKeyInput.trim()
            if (serverUrl.isBlank() || apiKey.isBlank()) return@withContext null

            val connection = (URL("$serverUrl/api/images/$imageId").openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 20000
                connection.setRequestProperty("X-RepoNotes-Key", apiKey)
                if (connection.responseCode !in 200..299) return@withContext null
                val bytes = connection.inputStream.use { it.readBytes() }
                if (bytes.isEmpty()) return@withContext null
                cached.writeBytes(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) {
                null
            } finally {
                connection.disconnect()
            }
        }

    fun extractImageIds(markdown: String): List<String> =
        internalImageRegex.findAll(markdown).map { it.groupValues[1] }.distinct().toList()

    fun replaceImageMarkdownWithPlaceholder(text: String): String =
        imageMarkdownRegex.replace(text) { match ->
            val alt = match.groupValues[1].trim()
            if (alt.isBlank() || alt.equals("image", ignoreCase = true)) "[Image]" else "[Image: $alt]"
        }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val longest = maxOf(width, height)
        if (longest <= maxDimension || longest <= 0) return bitmap
        val scale = maxDimension.toFloat() / longest.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            maxOf(1, (width * scale).toInt()),
            maxOf(1, (height * scale).toInt()),
            true
        )
    }
}

