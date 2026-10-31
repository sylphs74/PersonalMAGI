package jp.pocketmagi.data

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Export / import of the settings and, optionally, the API keys. The keys are never written in
 * plain text: they are encrypted with a password (PBKDF2-SHA256 + AES-GCM) that is not stored.
 */
object Backup {
    private const val ITER = 200_000
    private val PROVIDERS = listOf("claude", "openai", "gemini", "perplexity")

    class WrongPassword : Exception("パスワードが違います")

    fun export(c: Context, keys: ApiKeys, password: String?, history: JSONArray? = null): String {
        val prefs = c.getSharedPreferences("settings", Context.MODE_PRIVATE).all
        val settings = JSONObject()
        for ((k, v) in prefs) {
            val e = JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                is Set<*> -> e.put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            settings.put(k, e)
        }
        val out = JSONObject().put("app", "PersonalMAGI").put("format", 1)
            .put("exportedAt", System.currentTimeMillis()).put("settings", settings)
        if (history != null) out.put("history", history)
        // own colour schemes, so a selected custom theme still exists after importing
        try { java.io.File(c.filesDir, "palettes.json").takeIf { it.exists() }?.let { out.put("palettes", JSONArray(it.readText())) } } catch (_: Exception) {}
        if (!password.isNullOrEmpty()) {
            val plain = JSONObject()
            PROVIDERS.forEach { p -> keys.get(p)?.let { plain.put(p, it) } }
            if (plain.length() > 0) out.put("keys", encrypt(plain.toString(), password))
        }
        return out.toString(2)
    }

    /** True when the file carries encrypted API keys (a password is then asked for). */
    fun hasKeys(json: String): Boolean = try { JSONObject(json).has("keys") } catch (e: Exception) { false }

    /**
     * Applies the settings, and the API keys when [password] is given. Returns how many keys were
     * restored. Throws [WrongPassword] before changing anything if the password does not fit.
     */
    data class Result(val keys: Int, val records: Int)

    fun import(c: Context, json: String, keys: ApiKeys, password: String?, history: HistoryStore? = null): Result {
        val o = JSONObject(json)
        require(o.optString("app") in setOf("PersonalMAGI", "PocketMAGI")) { "PersonalMAGI の設定ファイルではありません" }
        val restored = if (!password.isNullOrEmpty() && o.has("keys")) {
            JSONObject(decrypt(o.getJSONObject("keys"), password))
        } else null
        val s = o.optJSONObject("settings") ?: JSONObject()
        val ed = c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
        for (k in s.keys()) {
            val e = s.optJSONObject(k) ?: continue
            when (e.optString("t")) {
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "s" -> ed.putString(k, e.getString("v"))
                "ss" -> e.getJSONArray("v").let { a -> ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
            }
        }
        ed.commit()
        var n = 0
        restored?.let { r -> PROVIDERS.forEach { p -> r.optString(p).takeIf { it.isNotBlank() }?.let { keys.set(p, it); n++ } } }
        val records = o.optJSONArray("history")?.let { a -> history?.merge(a) } ?: 0
        o.optJSONArray("palettes")?.let { incoming ->
            val f = java.io.File(c.filesDir, "palettes.json")
            val cur = try { JSONArray(f.readText()) } catch (e: Exception) { JSONArray() }
            val byId = linkedMapOf<String, JSONObject>()
            for (i in 0 until cur.length()) cur.optJSONObject(i)?.let { byId[it.optString("id")] = it }
            for (i in 0 until incoming.length()) incoming.optJSONObject(i)?.let { byId[it.optString("id")] = it }
            f.writeText(JSONArray(byId.values.toList()).toString(2))
        }
        return Result(n, records)
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(PBEKeySpec(password.toCharArray(), salt, ITER, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private fun encrypt(plain: String, password: String): JSONObject {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        return JSONObject().put("kdf", "PBKDF2-SHA256").put("iter", ITER).put("salt", b64(salt)).put("iv", b64(iv))
            .put("data", b64(c.doFinal(plain.toByteArray(Charsets.UTF_8))))
    }

    private fun decrypt(o: JSONObject, password: String): String = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(password, unb64(o.getString("salt"))), GCMParameterSpec(128, unb64(o.getString("iv"))))
        String(c.doFinal(unb64(o.getString("data"))), Charsets.UTF_8)
    } catch (e: javax.crypto.AEADBadTagException) {
        throw WrongPassword()
    }
}
