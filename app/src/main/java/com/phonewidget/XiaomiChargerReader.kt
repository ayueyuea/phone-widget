package com.phonewidget

import android.os.Build
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * 小米私有快充协议读取器
 *
 * 通过读取 Linux sysfs 接口获取更精确的充电数据。
 * 小米/澎湃 OS 系统会在以下路径暴露充电信息：
 *
 *   /sys/class/power_supply/
 *   ├── battery/          # 电池属性
 *   │   ├── temp          # 温度 (0.1°C 或 0.01°C)
 *   │   ├── current_now   # 电流 (µA)
 *   │   ├── voltage_now   # 电压 (µV)
 *   │   ├── capacity      # 电量 (%)
 *   │   ├── status        # Charging/Discharging/Full
 *   │   └── charge_type   # [小米特有] Turbo/Fast/Normal
 *   ├── bms/              # 电池管理系统（最精确的电流值）
 *   │   ├── current_now   # 电流 (µA)
 *   │   └── voltage_now   # 电压 (µV)
 *   ├── usb/              # USB 充电器信息
 *   │   ├── voltage_max   # 最大电压 (µV)
 *   │   ├── input_current_max  # 最大输入电流 (µA)
 *   │   └── present       # 是否接入
 *   ├── main/             # 主充电器（部分机型）
 *   │   ├── current_now
 *   │   └── voltage_now
 *   └── wireless/         # 无线充电（部分机型）
 *
 * 非 root 设备上部分文件可读（权限 0444），不可读的静默降级。
 */
object XiaomiChargerReader {

    private const val POWER_SUPPLY_PATH = "/sys/class/power_supply"

    /** 小米快充类型 — 由 charge_type sysfs 节点报告 */
    enum class ChargeType(val label: String, val minWatt: Float) {
        UNKNOWN("未知", 0f),
        NORMAL("普通充电", 5f),
        FAST("快充", 18f),
        TURBO("小米Turbo快充", 30f),
        HYPER_CHARGE("小米澎湃秒充", 55f),
    }

    /** 从 sysfs 读取的数据快照 */
    data class SysfsData(
        val currentNowUa: Int? = null,   // µA
        val voltageNowUv: Int? = null,   // µV
        val temperatureRaw: Int? = null, // 0.1°C
        val capacity: Int? = null,       // %
        val chargeType: ChargeType = ChargeType.UNKNOWN,
        val usbVoltageMaxUv: Int? = null,
        val usbCurrentMaxUa: Int? = null,
        val status: String? = null,
        val isPresent: Boolean = false
    )

    /**
     * 尝试从 sysfs 读取充电数据
     * 返回 null 表示完全无法读取（无权限或路径不存在）
     */
    fun readSysfs(): SysfsData? {
        // 先检查 power_supply 目录是否存在
        val baseDir = File(POWER_SUPPLY_PATH)
        if (!baseDir.exists() || !baseDir.isDirectory) {
            // Timber.d("power_supply目录不存在: $POWER_SUPPLY_PATH")
            return null
        }

        var hasAnyData = false
        var currentNowUa: Int? = null
        var voltageNowUv: Int? = null
        var temperatureRaw: Int? = null
        var capacity: Int? = null
        var chargeType: ChargeType = ChargeType.UNKNOWN
        var usbVoltageMaxUv: Int? = null
        var usbCurrentMaxUa: Int? = null
        var status: String? = null
        var isPresent: Boolean = false

        // 读取顺序优化：先读最重要的数据
        val pathsToRead = listOf(
            // 1. 电池 BMS（最优先，最精确）
            "$POWER_SUPPLY_PATH/bms/current_now" to { value: Int -> currentNowUa = value },
            "$POWER_SUPPLY_PATH/bms/voltage_now" to { value: Int -> voltageNowUv = value },
            
            // 2. 常规电池节点（BMS 没读到时用这个）
            "$POWER_SUPPLY_PATH/battery/current_now" to { value: Int -> 
                if (currentNowUa == null) currentNowUa = value 
            },
            "$POWER_SUPPLY_PATH/battery/voltage_now" to { value: Int -> 
                if (voltageNowUv == null) voltageNowUv = value 
            },
            
            // 3. 温度
            "$POWER_SUPPLY_PATH/battery/temp" to { value: Int -> temperatureRaw = value },
            
            // 4. 电量
            "$POWER_SUPPLY_PATH/battery/capacity" to { value: Int -> capacity = value },
            
            // 5. 小米私有: charge_type
            "$POWER_SUPPLY_PATH/battery/charge_type" to { value: String -> 
                chargeType = parseChargeType(value)
            },
            
            // 6. 充电状态
            "$POWER_SUPPLY_PATH/battery/status" to { value: String -> status = value },
            
            // 7. USB 充电器信息
            "$POWER_SUPPLY_PATH/usb/voltage_max" to { value: Int -> usbVoltageMaxUv = value },
            "$POWER_SUPPLY_PATH/usb/input_current_max" to { value: Int -> usbCurrentMaxUa = value },
            "$POWER_SUPPLY_PATH/usb/present" to { value: Int -> isPresent = value == 1 },
            
            // 8. 主充电器（备用）
            "$POWER_SUPPLY_PATH/main/current_now" to { value: Int -> 
                if (currentNowUa == null) currentNowUa = value 
            },
            "$POWER_SUPPLY_PATH/main/voltage_now" to { value: Int -> 
                if (voltageNowUv == null) voltageNowUv = value 
            }
        )

        // 批量读取文件
        for ((path, setter) in pathsToRead) {
            try {
                if (path.endsWith("charge_type") || path.endsWith("status")) {
                    readStringSysfs(path)?.let { strValue ->
                        setter(strValue)
                        hasAnyData = true
                    }
                } else {
                    readIntSysfs(path)?.let { intValue ->
                        setter(intValue)
                        hasAnyData = true
                    }
                }
            } catch (e: Exception) {
                // 静默失败，继续读取其他文件
                // Timber.d("读取 $path 失败: ${e.message}")
            }
        }

        return if (hasAnyData) {
            // Timber.d("成功读取sysfs数据: current=$currentNowUa, voltage=$voltageNowUv, temp=$temperatureRaw")
            SysfsData(
                currentNowUa = currentNowUa,
                voltageNowUv = voltageNowUv,
                temperatureRaw = temperatureRaw,
                capacity = capacity,
                chargeType = chargeType,
                usbVoltageMaxUv = usbVoltageMaxUv,
                usbCurrentMaxUa = usbCurrentMaxUa,
                status = status,
                isPresent = isPresent
            )
        } else {
            // Timber.d("未读取到任何sysfs数据")
            null
        }
    }

    /**
     * 解析小米 charge_type
     */
    private fun parseChargeType(raw: String): ChargeType {
        return when (raw.trim().lowercase()) {
            "turbo" -> ChargeType.TURBO
            "fast" -> ChargeType.FAST
            "normal" -> ChargeType.NORMAL
            "hyper_charge", "hyper" -> ChargeType.HYPER_CHARGE
            else -> ChargeType.UNKNOWN
        }
    }

    /**
     * 计算充电功率 (W)
     * 电压(µV) × 电流(µA) = 功率(pW) → 除以 1e12 得 W
     */
    fun calculateWattage(voltageUv: Int, currentUa: Int): Float {
        if (voltageUv <= 0 || currentUa <= 0) return 0f
        return (voltageUv.toLong() * currentUa.toLong()) / 1_000_000_000_000f
    }

    /**
     * 根据电压和电流判断小米快充协议名称
     */
    fun detectChargeProtocol(voltageUv: Int, currentUa: Int, chargeType: ChargeType): String {
        // 先看 charge_type 节点（最直接）
        if (chargeType != ChargeType.UNKNOWN) {
            return chargeType.label
        }

        // 无 charge_type 时根据功率估算
        val watt = calculateWattage(voltageUv, currentUa)
        return when {
            watt >= 55f -> "澎湃秒充"
            watt >= 30f -> "Turbo快充"
            watt >= 18f -> "QC/PD快充"
            watt > 0f   -> "普通充电"
            else        -> "未充电"
        }
    }

    /**
     * 检查设备是否为小米/红米/澎湃 OS 设备
     */
    fun isXiaomiDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val fingerprint = Build.FINGERPRINT.lowercase()
        return manufacturer.contains("xiaomi") ||
               brand.contains("xiaomi") ||
               brand.contains("redmi") ||
               fingerprint.contains("miui") ||
               fingerprint.contains("hyperos") ||
               fingerprint.contains("xiaomi")
    }

    // ============== 底层文件读取 ==============

    /**
     * 读取 sysfs 节点返回 Int，失败返回 null
     */
    private fun readIntSysfs(path: String): Int? {
        return try {
            val content = readStringSysfs(path) ?: return null
            content.trim().toInt()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 读取 sysfs 节点返回原始字符串，失败返回 null
     */
    private fun readStringSysfs(path: String): String? {
        return try {
            val file = File(path)
            if (!file.exists() || !file.canRead()) return null
            BufferedReader(FileReader(file)).use { it.readLine() }
        } catch (e: Exception) {
            null
        }
    }
}
