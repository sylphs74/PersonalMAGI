package jp.pocketmagi.data

import android.content.Context

data class MagiSettings(
    val threads: Int = 4,
    val judgeMaxTokens: Int = 260,
    val integrateMaxTokens: Int = 320,
    val wifiOnly: Boolean = true,
    /** The proposer may look things up on the web when it lacks material (weather, Wikipedia, search). */
    val webEnabled: Boolean = true,
    /** After the first verdicts each sage sees the other two and may reconsider once. */
    val rebuttal: Boolean = false,
    /** Auto choice of local models follows the battery, temperature and charging state. */
    val deviceAware: Boolean = true,
    /** Simultaneous connections per file when downloading models. */
    val dlConnections: Int = 6,
    /** Speaking style of the answers (see Styles). */
    val speechStyle: String = "standard",
    val useGpu: Boolean = true,          // falls back to CPU automatically when no usable GPU
    /** Models that crashed on the GPU: they run on the CPU, the others keep using the GPU. */
    val gpuDeny: Set<String> = emptySet(),
    val autoUpdateCheck: Boolean = true, // check models for updates at most once a week, on Wi-Fi
    /** Per sage: "auto" (pick by the phone's memory) or the base id of a catalog model. */
    val roleChoice: Map<String, String> = emptyMap(),
    /** Per role: "local" or a cloud provider id (claude / openai / gemini / perplexity). */
    val roleEngine: Map<String, String> = emptyMap(),
    /** Per role: cloud model id, "" = the recommended one. */
    val roleCloudModel: Map<String, String> = emptyMap(),
    // appearance
    val theme: String = "magi",          // magi / tokyonight / nerv
    val bodyFontId: String = "",         // "" = system font
    val headFontId: String = "",         // "" = monospace
    // history retention (0 = no limit)
    val historyEnabled: Boolean = true,
    val historyMaxCount: Int = 100,
    val historyMaxDays: Int = 0,
    // music: looped while the sages deliberate, once when they finish (files in Sounds)
    val musicRunningOn: Boolean = false,
    val musicRunningName: String = "",
    val musicDoneOn: Boolean = false,
    val musicDoneName: String = "",
    /** OpenAI-compatible API ("カスタムAPI"): base URL (e.g. https://…/v1) and model name. */
    val customApiBase: String = "",
    val customApiModel: String = "",
)

/** Roles that can run on a cloud AI instead of a local model. */
val ENGINE_ROLES = listOf("PROPOSER", "MELCHIOR", "BALTHASAR", "CASPER", "INTEGRATOR")

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): MagiSettings {
        val d = MagiSettings()
        return MagiSettings(
            threads = p.getInt("threads", d.threads),
            judgeMaxTokens = p.getInt("judgeMaxTokens", d.judgeMaxTokens),
            integrateMaxTokens = p.getInt("integrateMaxTokens", d.integrateMaxTokens),
            wifiOnly = p.getBoolean("wifiOnly", d.wifiOnly),
            webEnabled = p.getBoolean("webEnabled", d.webEnabled),
            rebuttal = p.getBoolean("rebuttal", d.rebuttal),
            deviceAware = p.getBoolean("deviceAware", d.deviceAware),
            dlConnections = p.getInt("dlConnections", d.dlConnections),
            speechStyle = p.getString("speechStyle", d.speechStyle) ?: d.speechStyle,
            useGpu = p.getBoolean("useGpu", d.useGpu),
            gpuDeny = p.getStringSet("gpuDeny", emptySet())?.toSet() ?: emptySet(),
            autoUpdateCheck = p.getBoolean("autoUpdateCheck", d.autoUpdateCheck),
            roleChoice = listOf("MELCHIOR", "BALTHASAR", "CASPER", "PROPOSER")
                .associateWith { r -> p.getString("choice.$r", "auto") ?: "auto" },
            roleEngine = ENGINE_ROLES.associateWith { r -> p.getString("engine.$r", "local") ?: "local" },
            roleCloudModel = ENGINE_ROLES.associateWith { r -> p.getString("cloudModel.$r", "") ?: "" },
            theme = p.getString("theme", d.theme) ?: d.theme,
            bodyFontId = p.getString("bodyFontId", d.bodyFontId) ?: d.bodyFontId,
            headFontId = p.getString("headFontId", d.headFontId) ?: d.headFontId,
            historyEnabled = p.getBoolean("historyEnabled", d.historyEnabled),
            historyMaxCount = p.getInt("historyMaxCount", d.historyMaxCount),
            historyMaxDays = p.getInt("historyMaxDays", d.historyMaxDays),
            musicRunningOn = p.getBoolean("musicRunningOn", d.musicRunningOn),
            musicRunningName = p.getString("musicRunningName", "") ?: "",
            musicDoneOn = p.getBoolean("musicDoneOn", d.musicDoneOn),
            musicDoneName = p.getString("musicDoneName", "") ?: "",
            customApiBase = p.getString("customApiBase", "") ?: "",
            customApiModel = p.getString("customApiModel", "") ?: "",
        )
    }

    fun save(s: MagiSettings) {
        p.edit()
            .putInt("threads", s.threads)
            .putInt("judgeMaxTokens", s.judgeMaxTokens)
            .putInt("integrateMaxTokens", s.integrateMaxTokens)
            .putBoolean("wifiOnly", s.wifiOnly)
            .putBoolean("webEnabled", s.webEnabled)
            .putBoolean("rebuttal", s.rebuttal)
            .putBoolean("deviceAware", s.deviceAware)
            .putInt("dlConnections", s.dlConnections)
            .putString("speechStyle", s.speechStyle)
            .putBoolean("useGpu", s.useGpu)
            .putStringSet("gpuDeny", s.gpuDeny)
            .putBoolean("autoUpdateCheck", s.autoUpdateCheck)
            .apply { s.roleChoice.forEach { (r, v) -> putString("choice.$r", v) } }
            .apply { s.roleEngine.forEach { (r, v) -> putString("engine.$r", v) } }
            .apply { s.roleCloudModel.forEach { (r, v) -> putString("cloudModel.$r", v) } }
            .putString("theme", s.theme)
            .putString("bodyFontId", s.bodyFontId)
            .putString("headFontId", s.headFontId)
            .putBoolean("historyEnabled", s.historyEnabled)
            .putInt("historyMaxCount", s.historyMaxCount)
            .putInt("historyMaxDays", s.historyMaxDays)
            .putBoolean("musicRunningOn", s.musicRunningOn)
            .putString("musicRunningName", s.musicRunningName)
            .putBoolean("musicDoneOn", s.musicDoneOn)
            .putString("musicDoneName", s.musicDoneName)
            .putString("customApiBase", s.customApiBase)
            .putString("customApiModel", s.customApiModel)
            .apply()
    }
}
