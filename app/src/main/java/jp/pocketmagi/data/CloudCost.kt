package jp.pocketmagi.data

import android.content.Context

/**
 * Cost of the cloud AIs in yen: token counts × the per-provider price (USD per million tokens,
 * editable because prices change) × the exchange rate. Totals are kept per month, and a monthly
 * cap makes the cloud roles fall back to the phone's models once it is reached.
 */
object CloudCost {
    /** Rough list prices (USD / 1M tokens, input to output) of the recommended models; edit in the settings. */
    val DEFAULTS = mapOf(
        "claude" to (3.0 to 15.0), "openai" to (1.25 to 10.0), "gemini" to (1.25 to 10.0),
        "perplexity" to (1.0 to 1.0), "custom" to (0.0 to 0.0),
    )

    private fun s(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)   // exported with the settings
    private fun t(c: Context) = c.getSharedPreferences("cloudcost", Context.MODE_PRIVATE)

    fun price(c: Context, id: String): Pair<Double, Double> {
        val d = DEFAULTS[id] ?: (0.0 to 0.0)
        return s(c).getFloat("price.$id.in", d.first.toFloat()).toDouble() to s(c).getFloat("price.$id.out", d.second.toFloat()).toDouble()
    }

    fun setPrice(c: Context, id: String, input: Double, output: Double) =
        s(c).edit().putFloat("price.$id.in", input.toFloat()).putFloat("price.$id.out", output.toFloat()).apply()

    fun rate(c: Context): Double = s(c).getFloat("usdJpy", 150f).toDouble()
    fun setRate(c: Context, r: Double) = s(c).edit().putFloat("usdJpy", r.toFloat()).apply()

    /** Monthly cap in yen; 0 = none. */
    fun cap(c: Context): Int = s(c).getInt("costCapYen", 0)
    fun setCap(c: Context, yen: Int) = s(c).edit().putInt("costCapYen", yen).apply()

    private fun month() = java.time.YearMonth.now().toString()

    fun monthTotal(c: Context): Double = t(c).getFloat("yen.${month()}", 0f).toDouble()

    fun add(c: Context, yen: Double) {
        if (yen <= 0) return
        t(c).edit().putFloat("yen.${month()}", (monthTotal(c) + yen).toFloat()).apply()
    }

    fun resetMonth(c: Context) = t(c).edit().remove("yen.${month()}").apply()

    fun overCap(c: Context): Boolean = cap(c) > 0 && monthTotal(c) >= cap(c)

    /** Yen for one provider's tokens; [usd] is the provider's own cost figure when it reports one. */
    fun yen(c: Context, id: String, inTokens: Int, outTokens: Int, usd: Double? = null): Double {
        val (pi, po) = price(c, id)
        return (usd ?: ((inTokens * pi + outTokens * po) / 1_000_000.0)) * rate(c)
    }
}
