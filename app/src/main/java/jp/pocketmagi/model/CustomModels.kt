package jp.pocketmagi.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local models the user added: a GGUF repository on Hugging Face, or a .gguf file copied from the
 * phone. They join the catalog (tier "custom") and can be chosen for any role.
 */
object CustomModels {
    private fun file(c: Context) = File(c.filesDir, "custom_models.json")

    fun spec(id: String, role: SageRole, name: String, repo: String, quant: List<String>, nCtx: Int) = ModelSpec(
        id = id, role = role, name = name, vendor = "ユーザー追加", madeInJapan = false, license = "—",
        repo = repo, quantPrefer = quant, mmprojPrefer = emptyList(), approxGb = 0.0, nCtx = nCtx,
        isDefault = false, tier = "custom",
    )

    /** A spec whose file was copied from the phone (nothing to download). */
    fun isLocalFile(spec: ModelSpec) = spec.tier == "custom" && spec.repo == LOCAL
    const val LOCAL = "local"

    fun load(c: Context): List<ModelSpec> = try {
        val a = JSONArray(file(c).readText())
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val role = SageRole.entries.firstOrNull { it.name == o.optString("role") } ?: return@mapNotNull null
            spec(o.getString("id"), role, o.optString("name"), o.optString("repo"),
                o.optJSONArray("quant")?.let { q -> (0 until q.length()).map { q.getString(it) } } ?: listOf("Q4_K_M", "Q4_0"),
                o.optInt("nCtx", 4096))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(c: Context, list: List<ModelSpec>) {
        val a = JSONArray()
        list.forEach { s ->
            a.put(JSONObject().put("id", s.id).put("role", s.role.name).put("name", s.name).put("repo", s.repo)
                .put("quant", JSONArray(s.quantPrefer)).put("nCtx", s.nCtx))
        }
        file(c).writeText(a.toString(2))
        ModelCatalog.invalidate()
    }

    fun add(c: Context, spec: ModelSpec) = save(c, load(c).filterNot { it.id == spec.id } + spec)

    fun remove(c: Context, id: String) = save(c, load(c).filterNot { it.id == id })

    fun newId() = "custom-" + System.currentTimeMillis().toString(36)
}
