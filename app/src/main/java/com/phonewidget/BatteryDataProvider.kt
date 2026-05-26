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
 *   2. BatteryManager API（标准 Android）
 *   3. ACTION_BATTERY_CHANGED 广播（备用）
 */
object BatteryDataProvider {

    data class BatteryInfo(
        val temperature: Float,          // 电池温度，单位 °C
        val wattage: Float,              // 充电功率，单位 W（0 表示未充电）
        val isCharging: Boolean,         // 是否正在充电
        val level: Int,                  // 电量百分比 0-100
        val health: Int,                 // 电池健康状态
        val chargeType: XiaomiChargerReader.ChargeType = XiaomiChargerReader.ChargeType.UNKNOWN, // 快充类型
        val chargeProtocol: String = "", // 充电协议名称（如"澎湃秒充"）
        val voltage: Float = 0f,         // 当前电压 (V)
        val current: Float = 0f,         // 当前电流 (A)
        val dataSource: String = "api"   // 数据来源: "sysfs" / "api" / "intent"
    )

    /**
     * 获取增强的电池信息（优先使用 sysfs）
     */
    fun getBatteryInfo(context: Context): BatteryInfo {
        // 1. 尝试 sysfs（小米机型上可获得更精确的快充数据）
        val sysfsData = XiaomiChargerReader.readSysfs()
        if (sysfsData != null) {
            return buildFromSysfs(sysfsData)
        }

        // 2. 回退到 BatteryManager API
        return try {
            buildFromApi(context)
        } catch (e: Exception) {
            // 3. 最后回退到广播 Intent
            getBatteryInfoFromIntent(context)
        }
    }

    /**
     * 从 sysfs 数据构建 BatteryInfo
     */
    private fun buildFromSysfs(data: XiaomiChargerReader.SysfsData): BatteryInfo {
        // 功率计算: 电压(µV) × 电流(µA) → W
        val voltageV = data.voltageNowUv?.let { it / 1_000_000f } ?: 0f
        val currentA = data.currentNowUa?.let { it / 1_000_000f } ?: 0f
        val wattage = XiaomiChargerReader.calculateWattage(
            data.voltageNowUv ?: 0,
            data.currentNowUa ?: 0
        )

        val isCharging = data.currentNowUa?.let { it > 0 }
            ?: (data.status?.lowercase()?.contains("charging") == true)

        // 温度: sysfs 中通常为 0.1°C 或 0.01°C（整数）
        val temperature = data.temperatureRaw?.let { raw ->
            when {
                raw > 1000 -> raw / 100f  // 0.01°C 精度 (如 3650 → 36.5°C)
                raw > 100 -> raw / 10f    // 0.1°C 精度 (如 365 → 36.5°C)
                else -> raw.toFloat()     // 已经是 °C
            }
        } ?: 0f

        // 协议名称
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
     * 从 BatteryManager API 构建 BatteryInfo
     */
    private fun buildFromApi(context: Context): BatteryInfo {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        // 温度 (0.1°C → °C)
        val temperatureRaw = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_TEMPERATURE)
        val temperature = temperatureRaw / 10f

        // 电压 (mV) 和 电流 (µA)
        val voltage = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_VOLTAGE)
        val currentNow = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

        val voltageV = voltage / 1000f
        val currentA = currentNow / 1_000_000f

        // 功率 P(W) = U(V) × I(A)
        val wattage = if (currentNow > 0 && voltage > 0) {
            voltageV * currentA
        } else {
            0f
        }

        val isCharging = currentNow > 0
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val health = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_HEALTH)

        // API 模式下无法得知 charge_type，通过功率估算
        val protocol = if (isCharging) {
            when {
                wattage >= 55f -> "澎湃秒充"
                wattage >= 30f -> "Turbo快充"
                wattage >= 18f -> "快充"
                wattage > 0f   -> "普通充电"
                else           -> ""
            }
        } else ""

        return BatteryInfo(
            temperature = temperature,
            wattage = wattage,
            isCharging = isCharging,
            level = level,
            health = health,
            chargeType = XiaomiChargerReader.ChargeType.UNKNOWN,
            chargeProtocol = protocol,
            voltage = voltageV,
            current = currentA,
            dataSource = "api"
        )
    }

    /**
     * 通过广播 Intent 获取电池信息（备用方案）
     */
    fun getBatteryInfoFromIntent(context: Context): BatteryInfo {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return buildFromApi(context)

        // 温度 (0.1°C → °C)
        val temperature = (intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)) / 10f

        // 电压 (mV)
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)

        // 是否充电
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val isCharging = plugged != 0

        // 电量
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val levelPct = level * 100 / scale

        // 健康
        val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)

        // 电流 (API 33+ 支持从广播读 EXTRA_CURRENT_NOW)
        val currentNowUa = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getIntExtra(BatteryManager.EXTRA_CURRENT_NOW, 0)
        } else {
            0
        }

        val voltageV = voltage / 1000f
        val currentA = currentNowUa / 1_000_000f

        val wattage = if (currentNowUa > 0 && voltage > 0) {
            voltageV * currentA
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
