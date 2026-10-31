package jp.pocketmagi.model

import android.content.Context
import org.json.JSONObject

enum class SageRole(val label: String, val number: Int, val persona: String) {
    MELCHIOR("MELCHIOR", 1, "科学者"),
    BALTHASAR("BALTHASAR", 2, "母"),
    CASPER("CASPER", 3, "女"),
    PROPOSER("PROPOSER", 4, "中立の提案役"),
    /** Writes the result from the sages' opinions (locally: the last loaded sage model). */
    INTEGRATOR("INTEGRATOR", 5, "審議結果のまとめ役");

    val code: String get() = label
}

data class ModelSpec(
    val id: String,
    val role: SageRole,
    val name: String,
    val vendor: String,
    val madeInJapan: Boolean,
    val license: String,
    val repo: String,
    val quantPrefer: List<String>,
    val mmprojPrefer: List<String>,
    val approxGb: Double,
    val nCtx: Int,
    val isDefault: Boolean,
    /** Catalog entry this spec belongs to; differs from [id] once a newer generation replaced it. */
    val baseId: String = id,
    val family: Family? = null,
    /** "standard" or "high" (needs a phone with more memory, see [minRamGb]). */
    val tier: String = "standard",
    val minRamGb: Double = 0.0,
) {
    val needsMmproj: Boolean get() = mmprojPrefer.isNotEmpty()
}

/**
 * How to recognise newer generations of the same model on Hugging Face.
 * [pattern] is matched against the repo name (after the slash) and must capture the
 * generation in group "ver" (and optionally a revision in "sub"); the size is part of
 * the pattern itself, so only same-size successors match.
 */
data class Family(val query: String, val authors: List<String>, val pattern: Regex)

object ModelCatalog {
    @Volatile private var cache: List<ModelSpec>? = null

    /** Catalog with installed newer generations applied. */
    fun all(context: Context): List<ModelSpec> {
        cache?.let { return it }
        val list = base(context).map { Generations.effective(context, it) }
        cache = list
        return list
    }

    fun invalidate() { cache = null; baseCache = null }

    @Volatile private var baseCache: List<ModelSpec>? = null

    /** The catalog exactly as shipped in the APK. */
    fun base(context: Context): List<ModelSpec> {
        baseCache?.let { return it }
        val text = context.assets.open("models.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONObject(text).getJSONArray("models")
        val list = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            // an entry with a missing or unknown role is skipped instead of stopping the app
            val role = SageRole.entries.firstOrNull { it.name == o.optString("role") } ?: return@mapNotNull null
            ModelSpec(
                id = o.getString("id"),
                role = role,
                name = o.getString("name"),
                vendor = o.getString("vendor"),
                madeInJapan = o.getBoolean("madeInJapan"),
                license = o.getString("license"),
                repo = o.getString("repo"),
                quantPrefer = o.getJSONArray("quant").let { a -> (0 until a.length()).map { a.getString(it) } },
                mmprojPrefer = o.optJSONArray("mmproj")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                    ?: emptyList(),
                approxGb = o.getDouble("approxGb"),
                nCtx = o.optInt("nCtx", 4096),
                isDefault = o.optBoolean("isDefault", false),
                tier = o.optString("tier", "standard"),
                minRamGb = o.optDouble("minRamGb", 0.0),
                family = o.optJSONObject("family")?.let { f ->
                    Family(
                        query = f.getString("query"),
                        authors = f.getJSONArray("authors").let { a -> (0 until a.length()).map { a.getString(it) } },
                        pattern = Regex(f.getString("pattern"), RegexOption.IGNORE_CASE),
                    )
                },
            )
        }
        // models the user added (Hugging Face repositories or .gguf files) join the catalog
        val all = list + CustomModels.load(context)
        baseCache = all
        return all
    }

    fun byId(context: Context, id: String): ModelSpec? = all(context).firstOrNull { it.id == id || it.baseId == id }

    fun forRole(context: Context, role: SageRole): List<ModelSpec> = all(context).filter { it.role == role }
}
