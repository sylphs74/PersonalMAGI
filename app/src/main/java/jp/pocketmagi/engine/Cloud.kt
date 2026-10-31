package jp.pocketmagi.engine

import jp.pocketmagi.model.SageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

enum class CloudProvider(val id: String, val label: String, val keyHint: String, val keyUrl: String) {
    CLAUDE("claude", "Claude", "Claude Console の API キー（sk-ant-…）", "https://platform.claude.com/settings/keys"),
    OPENAI("openai", "ChatGPT", "OpenAI Platform の API キー（sk-…）", "https://platform.openai.com/api-keys"),
    GEMINI("gemini", "Gemini", "Google AI Studio の API キー", "https://aistudio.google.com/apikey"),
    PERPLEXITY("perplexity", "Perplexity", "Perplexity の API キー（pplx-…）", "https://console.perplexity.ai"),
    /** Any OpenAI-compatible API (URL, key and model set by the user). */
    CUSTOM("custom", "カスタムAPI", "OpenAI互換APIのキー（不要なサーバーでは空欄のまま）", "");

    companion object { fun of(id: String?): CloudProvider? = entries.firstOrNull { it.id == id } }
}

data class CloudReply(val text: String, val inTokens: Int, val outTokens: Int, val sources: List<Material>, val ms: Long,
                      /** The provider's own cost figure in USD, when it reports one (Perplexity). */
                      val costUsd: Double? = null)

/** Recommended cloud AI for a role: the AI's strengths matched to what the role weighs. */
data class Advice(val provider: CloudProvider, val why: String)

/**
 * Cloud AIs as sages, proposer or integrator, called with the user's own API keys.
 * Model names are read from each provider's model list at run time, so new models appear by themselves.
 */
object Cloud {
    /** OpenAI-compatible API set in the settings (base URL ending in /v1 or similar, and a model name). */
    @Volatile var customBase: String = ""
    @Volatile var customModel: String = ""


    val ADVICE: Map<SageRole, Advice> = mapOf(
        SageRole.PROPOSER to Advice(CloudProvider.PERPLEXITY,
            "毎回Webを検索して出典付きで答えるため、今日時点の事実を踏まえた中立な仮説を立てやすい"),
        SageRole.MELCHIOR to Advice(CloudProvider.GEMINI,
            "Google の幅広い知識と数学・科学の推論に強く、論理・根拠・事実の正確さを担う MELCHIOR に向く"),
        SageRole.BALTHASAR to Advice(CloudProvider.CLAUDE,
            "安全性と倫理を重視して作られ、人や社会への影響・守るべきものを慎重に考える BALTHASAR に向く"),
        SageRole.CASPER to Advice(CloudProvider.OPENAI,
            "会話が自然で共感的、暮らしの幅広い話題に強く、気持ち・直感・実感を重んじる CASPER に向く"),
        SageRole.INTEGRATOR to Advice(CloudProvider.CLAUDE,
            "長い意見を偏りなく整理し、元の意見にない事実を足さない慎重さが、審議結果のまとめ役に向く"),
    )

    /** Perplexity's Agent API is chosen by effort, not by model name. */
    val PERPLEXITY_EFFORTS = listOf("fast", "low", "medium", "high")
    fun effortLabel(e: String) = when (e) {
        "fast" -> "fast（速い）"; "low" -> "low（標準）"; "medium" -> "medium（じっくり）"; "high" -> "high（詳細調査・高コスト）"; else -> e
    }

    // ---- model lists

    suspend fun listModels(p: CloudProvider, key: String): List<String> = runInterruptible(Dispatchers.IO) {
        when (p) {
            CloudProvider.CLAUDE -> {
                val a = get("https://api.anthropic.com/v1/models?limit=100", claudeHeaders(key)).getJSONArray("data")
                objs(a).sortedByDescending { it.optString("created_at") }.map { it.getString("id") }
            }
            CloudProvider.OPENAI -> {
                val a = get("https://api.openai.com/v1/models", mapOf("Authorization" to "Bearer $key")).getJSONArray("data")
                val skip = Regex("audio|realtime|image|tts|transcribe|search|embedding|moderation|instruct|codex|oss|dall|whisper|babbage|davinci|computer|sora|deep-research", RegexOption.IGNORE_CASE)
                objs(a).filter { o -> val id = o.getString("id"); (id.startsWith("gpt-") || Regex("^o\\d").containsMatchIn(id) || id.startsWith("chatgpt-")) && !skip.containsMatchIn(id) }
                    .sortedByDescending { it.optLong("created") }.map { it.getString("id") }
            }
            CloudProvider.GEMINI -> {
                val a = get("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", mapOf("x-goog-api-key" to key)).optJSONArray("models") ?: JSONArray()
                val skip = Regex("embedding|imagen|tts|live|image|audio|robotics|computer|native|aqa|learnlm|gemma|veo", RegexOption.IGNORE_CASE)
                objs(a).filter { o ->
                    val methods = o.optJSONArray("supportedGenerationMethods")?.let { m -> (0 until m.length()).map { m.getString(it) } } ?: emptyList()
                    val id = o.getString("name").removePrefix("models/")
                    "generateContent" in methods && id.startsWith("gemini") && !skip.containsMatchIn(id)
                }.map { it.getString("name").removePrefix("models/") }
                    .sortedWith(compareByDescending<String> { Regex("gemini-(\\d+(?:\\.\\d+)?)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0 }
                        .thenBy { if (it.contains("preview") || it.contains("exp")) 1 else 0 })
            }
            CloudProvider.PERPLEXITY -> PERPLEXITY_EFFORTS
            // the user's own API: whatever it serves is offered as is
            CloudProvider.CUSTOM -> return@runInterruptible run {
                val base = customBase.trimEnd('/')
                require(base.isNotBlank()) { "カスタムAPIのURLが未設定です" }
                val list = try {
                    val a = get("$base/models", if (key.isNotBlank()) mapOf("Authorization" to "Bearer $key") else emptyMap()).optJSONArray("data")
                    objs(a).map { it.optString("id") }.filter { it.isNotBlank() }
                } catch (e: Exception) { if (customModel.isNotBlank()) emptyList() else throw e }
                (listOfNotNull(customModel.takeIf { it.isNotBlank() }) + list).distinct()
            }
        }
    }

    /** The model used when the user leaves it on 「おすすめ」. */
    fun recommendedModel(p: CloudProvider, models: List<String>): String? = when (p) {
        CloudProvider.CLAUDE -> models.firstOrNull { it.contains("sonnet") } ?: models.firstOrNull { it.contains("opus") } ?: models.firstOrNull()
        CloudProvider.OPENAI -> models.firstOrNull { it.startsWith("gpt-") && !Regex("mini|nano|chat-latest").containsMatchIn(it) } ?: models.firstOrNull()
        CloudProvider.GEMINI -> models.firstOrNull { it.contains("pro") && !it.contains("lite") } ?: models.firstOrNull()
        CloudProvider.PERPLEXITY -> "low"
        CloudProvider.CUSTOM -> customModel.ifBlank { models.firstOrNull() ?: "" }.ifBlank { null }
    }

    // ---- one request, one answer

    suspend fun chat(p: CloudProvider, model: String, key: String, system: String, user: String, maxTokens: Int): CloudReply =
        runInterruptible(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val r = when (p) {
                CloudProvider.CLAUDE -> {
                    val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("system", system)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
                    val o = post("https://api.anthropic.com/v1/messages", claudeHeaders(key), body, p)
                    val text = objs(o.optJSONArray("content")).filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
                    val u = o.optJSONObject("usage")
                    CloudReply(text, u?.optInt("input_tokens") ?: 0, u?.optInt("output_tokens") ?: 0, emptyList(), 0)
                }
                CloudProvider.OPENAI -> {
                    // reasoning models count their thinking in the completion budget, so it is generous
                    val body = JSONObject().put("model", model).put("max_completion_tokens", maxOf(maxTokens * 4, 6000))
                        .put("messages", JSONArray()
                            .put(JSONObject().put("role", "system").put("content", system))
                            .put(JSONObject().put("role", "user").put("content", user)))
                    val o = post("https://api.openai.com/v1/chat/completions", mapOf("Authorization" to "Bearer $key"), body, p)
                    val text = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                    val u = o.optJSONObject("usage")
                    CloudReply(text, u?.optInt("prompt_tokens") ?: 0, u?.optInt("completion_tokens") ?: 0, emptyList(), 0)
                }
                CloudProvider.GEMINI -> {
                    val body = JSONObject()
                        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                        .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
                        .put("generationConfig", JSONObject().put("maxOutputTokens", maxOf(maxTokens * 4, 8192)))
                    val o = post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", mapOf("x-goog-api-key" to key), body, p)
                    val parts = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    val text = objs(parts).filterNot { it.optBoolean("thought") }.joinToString("") { it.optString("text") }
                    val u = o.optJSONObject("usageMetadata")
                    CloudReply(text, u?.optInt("promptTokenCount") ?: 0,
                        (u?.optInt("candidatesTokenCount") ?: 0) + (u?.optInt("thoughtsTokenCount") ?: 0), emptyList(), 0)
                }
                CloudProvider.CUSTOM -> {
                    val base = customBase.trimEnd('/')
                    require(base.isNotBlank()) { "カスタムAPIのURLが未設定です" }
                    val body = JSONObject().put("model", model).put("max_tokens", maxOf(maxTokens * 2, 1500))
                        .put("messages", JSONArray()
                            .put(JSONObject().put("role", "system").put("content", system))
                            .put(JSONObject().put("role", "user").put("content", user)))
                    val o = post("$base/chat/completions", if (key.isNotBlank()) mapOf("Authorization" to "Bearer $key") else emptyMap(), body, p)
                    val text = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                    val u = o.optJSONObject("usage")
                    CloudReply(text, u?.optInt("prompt_tokens") ?: 0, u?.optInt("completion_tokens") ?: 0, emptyList(), 0)
                }
                CloudProvider.PERPLEXITY -> {
                    val body = JSONObject().put("effort", model.ifBlank { "low" }).put("instructions", system).put("input", user)
                        .put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
                        .put("max_output_tokens", maxOf(maxTokens * 2, 2000))
                    val o = post("https://api.perplexity.ai/v1/agent", mapOf("Authorization" to "Bearer $key"), body, p)
                    val out = objs(o.optJSONArray("output"))
                    val text = o.optString("output_text").ifBlank {
                        out.filter { it.optString("type") == "message" }.flatMap { objs(it.optJSONArray("content")) }
                            .filter { it.optString("type") == "output_text" || it.has("text") }.joinToString("") { it.optString("text") }
                    }
                    val sources = out.filter { it.optString("type") == "search_results" }.flatMap { objs(it.optJSONArray("results")) }
                        .mapNotNull { s ->
                            val url = s.optString("url"); if (url.isBlank()) null
                            else Material("引用", s.optString("title").ifBlank { url }, s.optString("snippet"), url)
                        }.distinctBy { it.url }.take(8)
                    val u = o.optJSONObject("usage")
                    val cost = u?.optJSONObject("cost")?.optDouble("total_cost")?.takeIf { !it.isNaN() }
                    CloudReply(text, u?.optInt("input_tokens") ?: 0, u?.optInt("output_tokens") ?: 0, sources, 0, cost)
                }
            }
            r.copy(text = r.text.replace(Regex("(?s)<think>.*?</think>"), "").trim(), ms = System.currentTimeMillis() - t0)
        }

    // ---- HTTP

    private fun claudeHeaders(key: String) = mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")

    private fun objs(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private fun get(url: String, headers: Map<String, String>): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 30_000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code ${errorMessage(text)}")
            return JSONObject(text)
        } finally { c.disconnect() }
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject, p: CloudProvider): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 20_000; c.readTimeout = 180_000
        c.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("${p.label}: HTTP $code ${errorMessage(text)}")
            return JSONObject(text)
        } finally { c.disconnect() }
    }

    private fun errorMessage(body: String): String = try {
        val o = JSONObject(body)
        o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: o.optString("detail").takeIf { it.isNotBlank() } ?: body.take(160)
    } catch (e: Exception) { body.take(160) }
}
