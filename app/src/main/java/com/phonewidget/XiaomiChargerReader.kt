package com.phonewidget

import android.os.Build
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * 小米私有快充协议读取器
 *
 * 通过读取 Linux sysfs 接口获取更精确的充电数据。
 * 非 root 设备上部分文件可读（权限 0444），不可读的静默降级。
 */
object XiaomiChargerReader {

    private const val POWER_SUPPLY_PATH = "/sys/class/power_supply"

    /** 小米快充类型 */
    enum class ChargeType(val label: String, val minWatt: Float) {
        UNKNOWN("未知", 0f),
        NORMAL("普通充电", 5f),
        FAST("快充", 18f),
        TURBO("小米Turbo快充", 30f),
        HYPER_CHARGE("小米澎湃秒充", 55f),
    }

    /** 从 sysfs 读取的数据快照 */
    data class SysfsData(
        val currentNowUa: Int? = null,
        val voltageNowUv: Int? = null,
        val temperatureRaw: Int? = null,
        val capacity: Int? = null,
        val chargeType: ChargeType = ChargeType.UNKNOWN,
        val usbVoltageMaxUv: Int? = null,
        val usbCurrentMaxUa: Int? = null,
        val status: String? = null,
        val isPresent: Boolean = false
    )

    /**
     * 尝试从 sysfs 读取充电数据
     */
    fun readSysfs(): SysfsData? {
        val baseDir = File(POWER_SUPPLY_PATH)
        if (!baseDir.exists() || !baseDir.isDirectory) return null

        val builder = SysfsData()
        var hasAnyData = false

        // 逐个路径读取，避免泛型类型推断问题
        hasAnyData = readIntOrNull("$POWER_SUPPLY_PATH/bms/current_now")?.let {
            builder.currentNowUa = it; true
        } ?: hasAnyData

        hasAnyData = readIntOrNull("$POWER_SUPPLY_PATH/bms/voltage_now")?.let {
            builder.voltageNowUv = it; true
        } ?: hasAnyData

        // 电池节点（BMS 没读到时才用）
        if (builder.currentNowUa == null) {
            hasAnyData = readIntOrNull("$POWER_SUPPLY_PATH/battery/current_now")?.let {
                builder.currentNowUa = it; true
            } ?: hasAnyData
        }
        if (builder.voltageNowUv == null) {
            hasAnyData = readIntOrNull("$POWER_SUPPLY_PATH/battery/voltage_now")?.let {
                builder.voltageNowUv = it; true
            } ?: hasAnyData
        }

        // 温度
        hasAnyData = readIntOrNull("$POWER_SUPPLY_PATH/battery/temp")?.let {
            builder.temperatureRaw = it; true
        } ?: hasAnyData

        // 电量
        hasAnyData = readIntOrNull("$POWER_SUPPLY_PATH/battery/capacity")?.let {
            builder.capacity = it; true
        } ?: hasAnyData

        // 小米私有 charge_type
        readStringOrNull("$POWER_SUPPLY_PATH/battery/charge_type")?.let {
            builder.chargeType = parseChargeType(it)
            hasAnyData = true
        }

        // 充电状态
        readStringOrNull("$POWER_SUPPLY_PATH/battery/status")?.let {
            builder.status = it
            hasAnyData = true
        }

        // USB 信息
        hasAnyData = readIntOrNull("$POWER_SUPPLY_PATH/usb/voltage_max")?.let {
            builder.usbVoltageMaxUv = it; true
        } ?: hasAnyData

        hasAnyData = readIntOrNull("$POWER_SUPPLY_PATH/usb/input_current_max")?.let {
            builder.usbCurrentMaxUa = it; true
        } ?: hasAnyData

        hasAnyData = readIntOrNull("$POWER_SUPPLY_PATH/usb/present")?.let {
            builder.isPresent = it == 1; true
        } ?: hasAnyData

        // 主充电器（备用）
        if (builder.currentNowUa == null) {
            hasAnyData = readIntOrNull("$POWER_SUPPLY_PATH/main/current_now")?.let {
                builder.currentNowUa = it; true
            } ?: hasAnyData
        }
        if (builder.voltageNowUv == null) {
            hasAnyData = readIntOrNull("$POWER_SUPPLY_PATH/main/voltage_now")?.let {
                builder.voltageNowUv = it; true
            } ?: hasAnyData
        }

        return if (hasAnyData) builder else null
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
     */
    fun calculateWattage(voltageUv: Int, currentUa: Int): Float {
        if (voltageUv <= 0 || currentUa <= 0) return 0f
        return (voltageUv.toLong() * currentUa.toLong()) / 1_000_000_000_000f
    }

    /**
     * 根据电压和电流判断小米快充协议名称
     */
    fun detectChargeProtocol(voltageUv: Int, currentUa: Int, chargeType: ChargeType): String {
        if (chargeType != ChargeType.UNKNOWN) {
            return chargeType.label
        }
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

    private fun readIntOrNull(path: String): Int? {
        return try {
            readStringOrNull(path)?.trim()?.toInt()
        } catch (e: Exception) {
            null
        }
    }

    private fun readStringOrNull(path: String): String? {
        return try {
            val file = File(path)
            if (!file.exists() || !file.canRead()) return null
            BufferedReader(FileReader(file)).use { it.readLine() }
        } catch (e: Exception) {
            null
        }
    }
}
