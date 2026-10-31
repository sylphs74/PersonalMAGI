package jp.pocketmagi.data

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
 * API keys of the cloud AIs, encrypted with a key that never leaves the Android Keystore.
 * Keys are never written to the records, the diagnostic log or crash reports.
 */
class ApiKeys(context: Context) {
    private val prefs = context.getSharedPreferences("apikeys", Context.MODE_PRIVATE)

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun get(provider: String): String? = try {
        prefs.getString(provider, null)?.let { stored ->
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }
    } catch (e: Exception) { null }

    fun set(provider: String, key: String?) {
        if (key.isNullOrBlank()) { prefs.edit().remove(provider).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secret())
        val out = c.iv + c.doFinal(key.trim().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(provider, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    fun has(provider: String): Boolean = prefs.contains(provider)

    companion object { private const val ALIAS = "pocketmagi-apikeys" }
}
