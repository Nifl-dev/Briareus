package com.ai.assistance.operit.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.ai.assistance.operit.util.AppLogger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/*
 * Wraps individual secret strings (OAuth tokens) with an AES-256-GCM key that
 * lives in the Android Keystore, so the value is not readable at rest even if
 * the preferences file is extracted from a backup.
 *
 * Why not EncryptedSharedPreferences: GitHubAuthPreferences is built on
 * DataStore with a Flow-based API used across the app. Encrypting the secret
 * fields in place keeps that contract intact, whereas swapping the storage
 * engine would have meant rewriting every consumer.
 *
 * Migration: values written before this existed are plaintext. decrypt() returns
 * them unchanged when they lack the version prefix, so existing sessions keep
 * working; the next save rewrites them encrypted.
 *
 * The Keystore entry can be lost when preferences are restored from a backup
 * that includes app data but not the hardware-backed key (the same failure mode
 * CodexAuthPreferences documents). decrypt() therefore treats a failure as "no
 * usable value" rather than throwing, so the app degrades to signed-out instead
 * of crashing.
 */
object SecretCipher {

    private const val TAG = "SecretCipher"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "briareus_token_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /** Marks a value as already encrypted, so plaintext can be told apart. */
    private const val PREFIX = "v1:"

    /** True when the value is already in encrypted form. */
    fun isEncrypted(value: String?): Boolean = value?.startsWith(PREFIX) == true

    fun encrypt(plaintext: String?): String? {
        if (plaintext == null) return null
        if (isEncrypted(plaintext)) return plaintext
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            // IV and ciphertext are stored together; both are needed to decrypt.
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to encrypt secret", e)
            null
        }
    }

    /**
     * Returns the plaintext, or null when the value cannot be recovered.
     * Unprefixed input is assumed to be legacy plaintext and returned as-is.
     */
    fun decrypt(stored: String?): String? {
        if (stored == null) return null
        if (!isEncrypted(stored)) return stored
        return try {
            val combined = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (combined.size <= IV_BYTES) return null
            val iv = combined.copyOfRange(0, IV_BYTES)
            val ciphertext = combined.copyOfRange(IV_BYTES, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to decrypt secret; treating it as absent", e)
            null
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // A fresh IV is generated per encryption; the default rejects
                // caller-supplied IVs, which is what we want here.
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}