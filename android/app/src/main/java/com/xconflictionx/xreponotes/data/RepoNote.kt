package com.xconflictionx.xreponotes.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

const val WORK_NOTEBOOK_REPO_PATH = "Work Notebook"
const val VAULT_PERSONAL_REPO_PATH = "Vault - Personal"
const val VAULT_WORK_REPO_PATH = "Vault - Work"

fun isWorkRepoPath(rawPath: String): Boolean {
    val trimmed = rawPath.trim()
    return trimmed.equals(WORK_NOTEBOOK_REPO_PATH, ignoreCase = true) ||
        trimmed.equals("work", ignoreCase = true)
}

fun isVaultPersonalRepoPath(rawPath: String): Boolean =
    rawPath.trim().equals(VAULT_PERSONAL_REPO_PATH, ignoreCase = true)

fun isVaultWorkRepoPath(rawPath: String): Boolean =
    rawPath.trim().equals(VAULT_WORK_REPO_PATH, ignoreCase = true)

fun isVaultRepoPath(rawPath: String): Boolean =
    isVaultPersonalRepoPath(rawPath) || isVaultWorkRepoPath(rawPath)

/**
 * Converts Windows paths (including URL-encoded paths from older sync records)
 * into a short project label for the notebook UI.
 */
fun projectLabel(rawPath: String): String {
    if (isWorkRepoPath(rawPath)) {
        return WORK_NOTEBOOK_REPO_PATH
    }
    if (isVaultPersonalRepoPath(rawPath)) {
        return "Personal"
    }
    if (isVaultWorkRepoPath(rawPath)) {
        return "Work"
    }
    var decoded = rawPath.trim()
    repeat(3) {
        val next = android.net.Uri.decode(decoded)
        if (next != decoded) decoded = next
    }
    val normalized = decoded.replace("\\", "/").trimEnd('/')
    val leaf = normalized.substringAfterLast('/')
    return leaf.substringAfterLast(':').ifBlank { "General" }
}

data class RepoNote(
    val id: Long = 0L,
    val pcId: Long = 0L,
    val clientId: String = "local:" + UUID.randomUUID().toString(),
    val repoPath: String = "",
    val title: String = "",
    val body: String = "",
    val createdUtc: String = getCurrentIsoUtcString(),
    val updatedUtc: String = getCurrentIsoUtcString(),
    val lastSyncedBody: String = "",
    val isDirty: Boolean = false,
    val isDeleted: Boolean = false
) {
    val displayTitle: String
        get() = when {
            title.isNotBlank() -> title
            body.isNotBlank() -> body.lines().firstOrNull { it.isNotBlank() }?.take(35) ?: "Untitled Note"
            else -> "Untitled Note"
        }

    val formattedDate: String
        get() {
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val date = sdf.parse(updatedUtc) ?: Date()
                val outSdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                outSdf.format(date)
            } catch (e: Exception) {
                updatedUtc
            }
        }

    companion object {
        fun getCurrentIsoUtcString(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(Date())
        }
    }
}
