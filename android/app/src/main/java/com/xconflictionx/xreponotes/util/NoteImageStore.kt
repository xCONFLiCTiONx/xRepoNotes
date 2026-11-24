package com.xconflictionx.xreponotes.util

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object NoteImageStore {
    private val imageMarkdownRegex = Regex("!\\[([^\\]]*)]\\(([^)]+)\\)")
    private val internalImageRegex = Regex("!\\[[^\\]]*]\\(xreponotes-image://([a-zA-Z0-9_-]+)\\)")

    private val thumbnailCache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    private fun imageDir(context: Context): File =
        File(context.filesDir, "note_images").apply { mkdirs() }

    private fun imageFile(context: Context, id: String): File =
        File(imageDir(context), "$id.png")

    private fun pendingFile(context: Context, id: String): File =
        File(imageDir(context), "$id.pending")

    fun getCachedThumbnail(imageId: String): Bitmap? = thumbnailCache.get(imageId)

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
        val oriented = applyExifOrientation(decoded, rawBytes)
        if (oriented !== decoded) decoded.recycle()

        val scaled = scaleDownIfNeeded(oriented, maxDimension = 2048)
        if (scaled !== oriented) oriented.recycle()

        val output = ByteArrayOutputStream()
        var uploadMime = "image/png"
        scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
        var finalBytes = output.toByteArray()
        if (finalBytes.size > 12 * 1024 * 1024) {
            output.reset()
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, output)
            finalBytes = output.toByteArray()
            uploadMime = "image/jpeg"
        }
        scaled.recycle()

        val id = UUID.randomUUID().toString().replace("-", "")
        imageFile(context, id).writeBytes(finalBytes)
        pendingFile(context, id).writeText(uploadMime)
        id
    }

    suspend fun uploadImage(context: Context, imageId: String, serverUrlInput: String, apiKeyInput: String): Boolean =
        withContext(Dispatchers.IO) {
            val serverUrl = serverUrlInput.trim().trimEnd('/')
            val apiKey = apiKeyInput.trim()
            if (serverUrl.isBlank() || apiKey.isBlank()) return@withContext false

            val file = imageFile(context, imageId)
            if (!file.exists()) return@withContext false

            val pending = pendingFile(context, imageId)
            val mimeType = if (pending.exists()) {
                pending.readText().trim().ifBlank { "image/png" }
            } else {
                "image/png"
            }

            val base64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            val payload = JSONObject()
                .put("id", imageId)
                .put("mimeType", mimeType)
                .put("dataBase64", base64)

            val connection = (URL("$serverUrl/api/images").openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 30000
                connection.doOutput = true
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("X-RepoNotes-Key", apiKey)
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
                val ok = connection.responseCode in 200..299
                if (ok) pending.delete()
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

    private fun ensureCachedImageFile(
        context: Context,
        imageId: String,
        serverUrlInput: String,
        apiKeyInput: String
    ): File? {
        val cached = imageFile(context, imageId)
        if (cached.exists() && cached.length() > 0L) return cached

        val serverUrl = serverUrlInput.trim().trimEnd('/')
        val apiKey = apiKeyInput.trim()
        if (serverUrl.isBlank() || apiKey.isBlank()) return null

        val connection = (URL("$serverUrl/api/images/$imageId").openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.setRequestProperty("X-RepoNotes-Key", apiKey)
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return null
            cached.writeBytes(bytes)
            cached
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    suspend fun loadThumbnailBitmap(
        context: Context,
        imageId: String,
        serverUrlInput: String,
        apiKeyInput: String,
        maxDimension: Int = 256
    ): Bitmap? {
        thumbnailCache.get(imageId)?.let { return it }
        return withContext(Dispatchers.IO) {
            thumbnailCache.get(imageId)?.let { return@withContext it }
            val file = ensureCachedImageFile(context, imageId, serverUrlInput, apiKeyInput) ?: return@withContext null
            val decoded = decodeSampledBitmap(file, maxDimension) ?: return@withContext null
            thumbnailCache.put(imageId, decoded)
            decoded
        }
    }

    suspend fun loadImageBitmap(context: Context, imageId: String, serverUrlInput: String, apiKeyInput: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val file = ensureCachedImageFile(context, imageId, serverUrlInput, apiKeyInput) ?: return@withContext null
            BitmapFactory.decodeFile(file.absolutePath)
        }

    fun extractImageIds(markdown: String): List<String> =
        internalImageRegex.findAll(markdown).map { it.groupValues[1] }.distinct().toList()

    fun stripInternalImageMarkdown(text: String): String =
        internalImageRegex.replace(text, "")

    fun replaceImageMarkdownWithPlaceholder(text: String): String =
        imageMarkdownRegex.replace(text) { match ->
            val alt = match.groupValues[1].trim()
            if (alt.isBlank() || alt.equals("image", ignoreCase = true)) "[Image]" else "[Image: $alt]"
        }

    private fun decodeSampledBitmap(file: File, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        var inSampleSize = 1
        while ((width / inSampleSize) > maxDimension * 2 || (height / inSampleSize) > maxDimension * 2) {
            inSampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        val sampled = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        val scaled = scaleDownIfNeeded(sampled, maxDimension)
        if (scaled !== sampled) sampled.recycle()
        return scaled
    }

    private fun applyExifOrientation(bitmap: Bitmap, rawBytes: ByteArray): Bitmap {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(rawBytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
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

