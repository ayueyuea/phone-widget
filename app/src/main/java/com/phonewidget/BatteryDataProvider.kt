package com.phonewidget

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * 电池数据读取 — 仅通过 ACTION_BATTERY_CHANGED 广播获取
 */
object BatteryDataProvider {

    data class BatteryInfo(
        val temperature: Float,       // °C
        val wattage: Float,           // W
        val isCharging: Boolean,
        val level: Int,               // %
        val health: Int,
        val chargeProtocol: String = "",
        val voltage: Float = 0f,
        val current: Float = 0f
    )

    fun getBatteryInfo(context: Context): BatteryInfo {
        // 1. 尝试 sysfs（小米私有快充信息）
        val sysfs = XiaomiChargerReader.readSysfs()
        if (sysfs != null) {
            return fromSysfs(sysfs)
        }
        // 2. 广播
        return fromIntent(context)
    }

    private fun fromSysfs(d: XiaomiChargerReader.SysfsData): BatteryInfo {
        val v = d.voltageNowUv?.let { it / 1_000_000f } ?: 0f
        val a = d.currentNowUa?.let { it / 1_000_000f } ?: 0f
        val w = if (d.voltageNowUv != null && d.currentNowUa != null && d.currentNowUa!! > 0)
            (d.voltageNowUv!!.toLong() * d.currentNowUa!!) / 1_000_000_000_000f else 0f
        val charging = d.currentNowUa?.let { it > 0 } ?: (d.status?.contains("harging", true) == true)
        val temp = d.temperatureRaw?.let { r -> when { r > 1000 -> r/100f; r > 100 -> r/10f; else -> r.toFloat() } } ?: 0f
        val protocol = if (d.chargeType != XiaomiChargerReader.ChargeType.UNKNOWN) d.chargeType.label
        else XiaomiChargerReader.detectChargeProtocol(d.voltageNowUv ?: 0, d.currentNowUa ?: 0, d.chargeType)
        return BatteryInfo(temp, w, charging, d.capacity ?: 0, BatteryManager.BATTERY_HEALTH_UNKNOWN, protocol, v, a)
    }

    private fun fromIntent(context: Context): BatteryInfo {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent == null) return BatteryInfo(0f, 0f, false, 0, BatteryManager.BATTERY_HEALTH_UNKNOWN)

        val temp = (intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)) / 10f
        val mv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val v = mv / 1000f
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = plugged != 0
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = level * 100 / scale
        val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)

        // 电流: 尝试从 intent 读，再尝试 BatteryManager API
        var ua = intent.getIntExtra("EXTRA_CURRENT_NOW", 0)
        if (ua == 0) {
            try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                if (bm != null) ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            } catch (_: Exception) {}
        }
        val a = ua / 1_000_000f
        val w = if (ua > 0 && mv > 0) v * a else if (charging) v * 0.5f else 0f

        val proto = if (charging) {
            when {
                w >= 55f -> "澎湃秒充"; w >= 30f -> "Turbo快充"
                w >= 18f -> "快充"; w >= 5f -> "充电中"; else -> "涓流充电"
            }
        } else ""

        return BatteryInfo(temp, w, charging, pct, health, proto, v, if (ua > 0) a else 0f)
    }
}
