package com.phonewidget

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * 电池数据读取封装
 *
 * 数据来源优先级：
 *   1. sysfs（小米私有快充协议、更精确的电流/电压）
 *   2. ACTION_BATTERY_CHANGED 广播（标准 Android）
 *
 * 注意：BatteryManager.getIntProperty() 不支持温度和电压，
 * 温度和电压只能通过 ACTION_BATTERY_CHANGED 广播获取。
 */
object BatteryDataProvider {

    data class BatteryInfo(
        val temperature: Float,          // 电池温度，单位 °C
        val wattage: Float,              // 充电功率，单位 W（0 表示未充电）
        val isCharging: Boolean,         // 是否正在充电
        val level: Int,                  // 电量百分比 0-100
        val health: Int,                 // 电池健康状态
        val chargeType: XiaomiChargerReader.ChargeType = XiaomiChargerReader.ChargeType.UNKNOWN,
        val chargeProtocol: String = "",
        val voltage: Float = 0f,         // 当前电压 (V)
        val current: Float = 0f,         // 当前电流 (A)
        val dataSource: String = "intent"
    )

    /**
     * 获取增强的电池信息
     */
    fun getBatteryInfo(context: Context): BatteryInfo {
        // 1. 优先 sysfs（小米机型上可获得更精确的快充数据）
        val sysfsData = XiaomiChargerReader.readSysfs()
        if (sysfsData != null) {
            return buildFromSysfs(sysfsData)
        }

        // 2. 回退到广播 Intent（唯一能获取温度和电压的方式）
        return getBatteryInfoFromIntent(context)
    }

    /**
     * 从 sysfs 数据构建 BatteryInfo
     */
    private fun buildFromSysfs(data: XiaomiChargerReader.SysfsData): BatteryInfo {
        val voltageV = data.voltageNowUv?.let { it / 1_000_000f } ?: 0f
        val currentA = data.currentNowUa?.let { it / 1_000_000f } ?: 0f
        val wattage = XiaomiChargerReader.calculateWattage(
            data.voltageNowUv ?: 0,
            data.currentNowUa ?: 0
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

    /**
     * 通过广播 Intent 获取电池信息（主要方案）
     *
     * 温度和电压只能通过 ACTION_BATTERY_CHANGED 广播获取，
     * 这是 Android 系统提供的标准接口。
     */
    fun getBatteryInfoFromIntent(context: Context): BatteryInfo {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        if (intent == null) {
            // 极端情况：无法获取广播，返回空数据
            return BatteryInfo(
                temperature = 0f,
                wattage = 0f,
                isCharging = false,
                level = 0,
                health = BatteryManager.BATTERY_HEALTH_UNKNOWN,
                dataSource = "none"
            )
        }

        // 温度 (0.1°C → °C) — 仅来自广播
        val temperature = (intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)) / 10f

        // 电压 (mV) — 仅来自广播
        val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val voltageV = voltageMv / 1000f

        // 是否充电
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val isCharging = plugged != 0

        // 电量
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val levelPct = level * 100 / scale

        // 健康
        val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)

        // 电流 — 从 Intent 获取；如果为 0 再尝试 BatteryManager API
        var currentNowUa = intent.getIntExtra("EXTRA_CURRENT_NOW", 0)
        if (currentNowUa == 0) {
            try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                currentNowUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            } catch (_: Exception) {}
        }
        val currentA = currentNowUa / 1_000_000f

        // 功率 P(W) = U(V) × I(A)
        val wattage = if (currentNowUa > 0 && voltageMv > 0) {
            voltageV * currentA
        } else if (isCharging) {
            // 检测到充电但读不到电流，估算 5W
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
            chargeProtocol = if (isCharging) "普通充电" else "",
            voltage = voltageV,
            current = currentA,
            dataSource = "intent"
        )
    }

    /**
     * 获取健康状态文本描述
     */
    fun healthToString(health: Int): String {
        return when (health) {
            BatteryManager.BATTERY_HEALTH_COLD -> "过冷"
            BatteryManager.BATTERY_HEALTH_DEAD -> "损坏"
            BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "过压"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "异常"
            else -> "未知"
        }
    }

    /**
     * 获取充电状态图标文本
     */
    fun chargingStatusEmoji(info: BatteryInfo): String {
        return if (info.isCharging) {
            when {
                info.chargeType == XiaomiChargerReader.ChargeType.HYPER_CHARGE -> "⚡澎湃秒充"
                info.chargeType == XiaomiChargerReader.ChargeType.TURBO -> "⚡Turbo快充"
                info.wattage >= 50f -> "⚡超级快充"
                info.wattage >= 20f -> "⚡快充中"
                info.wattage >= 5f -> "🔌普通充电"
                info.wattage > 0f -> "🔋涓流充电"
                else -> "🔋待充"
            }
        } else {
            "🔋未充电"
        }
    }
}
