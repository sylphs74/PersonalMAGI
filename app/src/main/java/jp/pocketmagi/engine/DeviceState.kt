package jp.pocketmagi.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Battery, temperature and charging state. On 自動, the larger local models are used only when the
 * phone is cool and has power to spare; otherwise the standard ones.
 */
object DeviceState {
    data class Snapshot(val level: Int, val charging: Boolean, val batteryTempC: Float, val thermal: Int)

    fun read(c: Context): Snapshot {
        val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.let { i ->
            val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && s > 0) l * 100 / s else 100
        } ?: 100
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val thermal = c.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        return Snapshot(level, charging, temp, thermal)
    }

    /** Null when the larger models may be used, otherwise the reason they are not. */
    fun blocksHigh(c: Context): String? {
        val s = read(c)
        return when {
            s.thermal >= PowerManager.THERMAL_STATUS_SEVERE -> "本体が高温"
            s.batteryTempC >= 42f -> "電池温度 %.0f℃".format(s.batteryTempC)
            !s.charging && s.level < 30 -> "電池残量 ${s.level}%"
            else -> null
        }
    }
}
