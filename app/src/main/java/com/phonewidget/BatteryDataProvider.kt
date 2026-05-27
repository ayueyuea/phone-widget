package com.phonewidget

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * 电池数据读取封装
 *
 * 数据来源优先级：
 *   1. sysfs（小米私有快充）
 *   2. ACTION_BATTERY_CHANGED 广播 + BatteryManager API 混合
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
        // 1. 优先 sysfs
        val sysfsData = XiaomiChargerReader.readSysfs()
        if (sysfsData != null) {
            return buildFromSysfs(sysfsData)
        }
        // 2. 混合模式
        return getHybridBatteryInfo(context)
    }

    private fun buildFromSysfs(data: XiaomiChargerReader.SysfsData): BatteryInfo {
        val voltageV = data.voltageNowUv?.let { it / 1_000_000f } ?: 0f
        val currentA = data.currentNowUa?.let { it / 1_000_000f } ?: 0f
        val wattage = XiaomiChargerReader.calculateWattage(
            data.voltageNowUv ?: 0, data.currentNowUa ?: 0
        )
        val isCharging = data.currentNowUa?.let { it > 0 }
            ?: (data.status?.lowercase()?.contains("charging") == true)
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
                data.voltageNowUv ?: 0, data.currentNowUa ?: 0, data.chargeType
            )
        }
        return BatteryInfo(
            temperature = temperature, wattage = wattage, isCharging = isCharging,
            level = data.capacity ?: 0, health = BatteryManager.BATTERY_HEALTH_UNKNOWN,
            chargeType = data.chargeType, chargeProtocol = protocol,
            voltage = voltageV, current = currentA, dataSource = "sysfs"
        )
    }

    /**
     * 混合模式：Intent + BatteryManager API
     *
     * - 温度/电压/电量/充电状态 → Intent 广播
     * - 电流 → BatteryManager.getIntProperty(CURRENT_NOW)（API 21+ 稳定可用）
     * - 功率 = 电压 × 电流
     */
    private fun getHybridBatteryInfo(context: Context): BatteryInfo {
        // 从广播获取基础数据
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        if (intent == null) {
            return BatteryInfo(temperature = 0f, wattage = 0f, isCharging = false,
                level = 0, health = BatteryManager.BATTERY_HEALTH_UNKNOWN, dataSource = "none")
        }

        // 温度 (0.1°C → °C)
        val temperature = (intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)) / 10f

        // 电压 (mV → V)
        val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val voltageV = voltageMv / 1000f

        // 充电状态
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val isCharging = plugged != 0

        // 电量
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val levelPct = level * 100 / scale

        // 健康
        val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)

        // 电流 (µA) — 优先从 BatteryManager API 读（比 Intent 可靠）
        var currentNowUa = 0
        if (batteryManager != null) {
            try {
                currentNowUa = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                // 如果电流为 0 但正在充电，可能是 API 返回 0，再试 intent 里的 EXTRA_CURRENT_NOW
                if (currentNowUa == 0) {
                    currentNowUa = intent.getIntExtra("EXTRA_CURRENT_NOW", 0)
                }
            } catch (e: Exception) {
                currentNowUa = intent.getIntExtra("EXTRA_CURRENT_NOW", 0)
            }
        }

        val currentA = currentNowUa / 1_000_000f

        // 功率 P(W) = U(V) × I(A)
        // 注意：电流为正时充电，负值时放电
        val wattage = if (currentNowUa > 0 && voltageMv > 0) {
            voltageV * currentA
        } else if (isCharging && currentNowUa <= 0) {
            // 检测到正在充电但电流为 0（部分机型不暴露电流值）
            // 使用典型电压估算：5V × 1A = 5W（最低充电功率）
            voltageV * 1.0f
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
            chargeProtocol = if (isCharging) {
                when {
                    wattage >= 55f -> "澎湃秒充"
                    wattage >= 30f -> "Turbo快充"
                    wattage >= 18f -> "快充"
                    wattage >= 5f -> "充电中"
                    else -> "涓流充电"
                }
            } else "",
            voltage = voltageV,
            current = if (currentNowUa > 0) currentA else 0f,
            dataSource = "hybrid"
        )
    }

    fun healthToString(health: Int): String = when (health) {
        BatteryManager.BATTERY_HEALTH_COLD -> "过冷"
        BatteryManager.BATTERY_HEALTH_DEAD -> "损坏"
        BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "过压"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "异常"
        else -> "未知"
    }

    fun chargingStatusEmoji(info: BatteryInfo): String = if (info.isCharging) {
        when {
            info.chargeType == XiaomiChargerReader.ChargeType.HYPER_CHARGE -> "⚡澎湃秒充"
            info.chargeType == XiaomiChargerReader.ChargeType.TURBO -> "⚡Turbo快充"
            info.wattage >= 50f -> "⚡超级快充"
            info.wattage >= 20f -> "⚡快充中"
            info.wattage >= 5f -> "🔌充电中"
            info.wattage > 0f -> "🔋涓流充电"
            else -> "🔌充电中"
        }
    } else "🔋未充电"
}
