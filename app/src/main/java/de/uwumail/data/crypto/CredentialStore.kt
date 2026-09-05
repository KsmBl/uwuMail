package de.uwumail.data.crypto

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
 * Stores account passwords encrypted with a hardware-backed AES key.
 *
 * The key never leaves the Android keystore; only the IV + ciphertext are kept in
 * SharedPreferences, so a database or backup copy on its own is useless.
 */
class CredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)

    private val secretKey: SecretKey
        get() {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        }

    fun put(key: String, secret: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + encrypted
        prefs.edit().putString(key, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return runCatching {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, GCM_IV_LENGTH)
            val body = blob.copyOfRange(GCM_IV_LENGTH, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(key: String) = prefs.edit().remove(key).apply()

    fun imapKey(accountId: Long) = "imap_$accountId"
    fun smtpKey(accountId: Long) = "smtp_$accountId"
    fun refreshTokenKey(accountId: Long) = "oauth_refresh_$accountId"
    fun accessTokenKey(accountId: Long) = "oauth_access_$accountId"

    fun removeAccount(accountId: Long) {
        remove(imapKey(accountId))
        remove(smtpKey(accountId))
        remove(refreshTokenKey(accountId))
        remove(accessTokenKey(accountId))
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "uwumail_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
    }
}
