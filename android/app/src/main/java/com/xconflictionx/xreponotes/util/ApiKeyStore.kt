package com.xconflictionx.xreponotes.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the xRepoNotes API key encrypted with an AES key held by Android Keystore.
 * The plaintext API key is never written to SharedPreferences.
 */
object ApiKeyStore {
    private const val KEY_ALIAS = "xRepoNotes.ApiKey"
    private const val LEGACY_KEY_ALIAS = "xTextEditor.RepoNotes.ApiKey"
    private const val AI_KEY_ALIAS = "xRepoNotes.AiApiKey"
    private const val PREFS_NAME = "xreponotes_prefs"
    private const val LEGACY_PREFS_NAME = "xtexteditor_prefs"
    private const val PREF_ENCRYPTED_KEY = "xreponotes_api_key_encrypted"
    private const val PREF_ENCRYPTED_AI_KEY = "xreponotes_ai_api_key_encrypted"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12

    fun save(context: Context, apiKey: String): Boolean =
        saveEncrypted(context, PREF_ENCRYPTED_KEY, KEY_ALIAS, LEGACY_KEY_ALIAS, apiKey)

    fun read(context: Context): String? =
        readEncrypted(context, PREF_ENCRYPTED_KEY, KEY_ALIAS, LEGACY_KEY_ALIAS, checkLegacyPrefs = true)

    fun clear(context: Context) {
        val appCtx = context.applicationContext
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(PREF_ENCRYPTED_KEY).commit()
        appCtx.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(PREF_ENCRYPTED_KEY).commit()
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.deleteEntry(KEY_ALIAS)
            keyStore.deleteEntry(LEGACY_KEY_ALIAS)
        } catch (_: Exception) { }
    }

    fun saveAiKey(context: Context, apiKey: String): Boolean =
        saveEncrypted(context, PREF_ENCRYPTED_AI_KEY, AI_KEY_ALIAS, null, apiKey)

    fun readAiKey(context: Context): String? =
        readEncrypted(context, PREF_ENCRYPTED_AI_KEY, AI_KEY_ALIAS, null, checkLegacyPrefs = false)

    fun clearAiKey(context: Context) {
        val appCtx = context.applicationContext
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(PREF_ENCRYPTED_AI_KEY).commit()
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.deleteEntry(AI_KEY_ALIAS)
        } catch (_: Exception) { }
    }

    private fun saveEncrypted(
        context: Context,
        prefKey: String,
        alias: String,
        legacyAlias: String?,
        plaintext: String
    ): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey(alias, legacyAlias))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val stored = cipher.iv + encrypted
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(prefKey, Base64.encodeToString(stored, Base64.NO_WRAP))
            .commit()
    } catch (_: Exception) {
        false
    }

    private fun readEncrypted(
        context: Context,
        prefKey: String,
        alias: String,
        legacyAlias: String?,
        checkLegacyPrefs: Boolean
    ): String? {
        return try {
            val appCtx = context.applicationContext
            val encoded = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(prefKey, null)
                ?: if (checkLegacyPrefs) {
                    appCtx.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(prefKey, null)
                } else {
                    null
                }
                ?: return null
            val stored = Base64.decode(encoded, Base64.NO_WRAP)
            if (stored.size <= IV_LENGTH) return null

            val iv = stored.copyOfRange(0, IV_LENGTH)
            val ciphertext = stored.copyOfRange(IV_LENGTH, stored.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(alias, legacyAlias), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun getOrCreateSecretKey(alias: String, legacyAlias: String?): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = (keyStore.getKey(alias, null)
            ?: legacyAlias?.let { keyStore.getKey(it, null) }) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
