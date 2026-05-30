package com.phonewidget

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

/**
 * 电池数据读取封装
 *
 * 数据来源优先级：
 *   1. sysfs（小米私有快充协议、更精确的电流/电压）
 *   2. ACTION_BATTERY_CHANGED 广播（标准 Android）
 */
object BatteryDataProvider {

    data class BatteryInfo(
        val temperature: Float,
        val wattage: Float,
        val isCharging: Boolean,
        val level: Int,
        val health: Int,
        val chargeType: XiaomiChargerReader.ChargeType = XiaomiChargerReader.ChargeType.UNKNOWN,
        val chargeProtocol: String = "",
        val voltage: Float = 0f,
        val current: Float = 0f,
        val dataSource: String = "intent"
    )

    fun getBatteryInfo(context: Context): BatteryInfo {
        val sysfsData = XiaomiChargerReader.readSysfs()
        if (sysfsData != null) {
            return buildFromSysfs(sysfsData)
        }
        return getBatteryInfoFromIntent(context)
    }

    private fun buildFromSysfs(data: XiaomiChargerReader.SysfsData): BatteryInfo {
        val voltageV = data.voltageNowUv?.let { it / 1_000_000f } ?: 0f
        val currentA = data.currentNowUa?.let { it / 1_000_000f } ?: 0f
        val wattage = XiaomiChargerReader.calculateWattage(
            data.voltageNowUv ?: 0,
            data.currentNowUa ?: 0
        )

        val isCharging = when {
            data.status?.lowercase()?.contains("charging") == true -> true
            data.currentNowUa != null -> data.currentNowUa!! > 0
            else -> false
        }

        val temperature = data.temperatureRaw?.let { raw ->
            when {
                raw > 1000 -> raw / 100f
                raw > 100 -> raw / 10f
                else -> raw.toFloat()
            }
        } ?: 0f

        val protocol = if (data.chargeType != XiaomiChargerReader.ChargeType.UNKNOWN) {
            data.chargeType.label
        } else {
            XiaomiChargerReader.detectChargeProtocol(
                data.voltageNowUv ?: 0,
                data.currentNowUa ?: 0,
                data.chargeType
            )
        }

        return BatteryInfo(
            temperature = temperature,
            wattage = wattage,
            isCharging = isCharging,
            level = data.capacity ?: 0,
            health = BatteryManager.BATTERY_HEALTH_UNKNOWN,
            chargeType = data.chargeType,
            chargeProtocol = protocol,
            voltage = voltageV,
            current = currentA,
            dataSource = "sysfs"
        )
    }

    fun getBatteryInfoFromIntent(context: Context): BatteryInfo {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        if (intent == null) {
            return BatteryInfo(
                temperature = 0f,
                wattage = 0f,
                isCharging = false,
                level = 0,
                health = BatteryManager.BATTERY_HEALTH_UNKNOWN,
                dataSource = "none"
            )
        }

        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f

        val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val voltageV = voltageMv / 1000f

        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val isCharging = plugged != 0

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val levelPct = if (scale > 0) (level * 100f / scale).toInt() else 0

        val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)

        var currentNowUa = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getIntExtra(BatteryManager.EXTRA_CURRENT_NOW, 0)
        } else {
            intent.getIntExtra("current_now", 0)
        }
        if (currentNowUa == 0) {
            try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                currentNowUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            } catch (_: Exception) {}
        }

        val currentA = when {
            currentNowUa in -1000..1000 -> currentNowUa / 1_000f
            else -> currentNowUa / 1_000_000f
        }

        val wattage = if (isCharging && voltageMv > 0) {
            kotlin.math.abs(voltageV * currentA)
        } else {
            0f
        }

        return BatteryInfo(
            temperature = temperature,
            wattage = wattage,
            isCharging = isCharging,
            level = levelPct,
            health = health,
            chargeType = XiaomiChargerReader.ChargeType.UNKNOWN,
            chargeProtocol = if (isCharging) "充电中" else "",
            voltage = voltageV,
            current = currentA,
            dataSource = "intent"
        )
    }
}
