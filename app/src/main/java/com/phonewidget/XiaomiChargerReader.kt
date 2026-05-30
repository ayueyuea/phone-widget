package com.phonewidget

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

    enum class ChargeType(val label: String, val minWatt: Float) {
        UNKNOWN("未知", 0f),
        NORMAL("普通充电", 5f),
        FAST("快充", 18f),
        TURBO("小米Turbo快充", 30f),
        HYPER_CHARGE("小米澎湃秒充", 55f),
    }

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

    fun readSysfs(): SysfsData? {
        return try {
            readSysfsInternal()
        } catch (_: Exception) {
            null
        }
    }

    private fun readSysfsInternal(): SysfsData? {
        val baseDir = File(POWER_SUPPLY_PATH)
        if (!baseDir.exists() || !baseDir.isDirectory) return null

        var currentNowUa: Int? = null
        var voltageNowUv: Int? = null
        var temperatureRaw: Int? = null
        var capacity: Int? = null
        var chargeType = ChargeType.UNKNOWN
        var usbVoltageMaxUv: Int? = null
        var usbCurrentMaxUa: Int? = null
        var status: String? = null
        var isPresent = false

        val supplyDirs = baseDir.listFiles()?.filter { it.isDirectory } ?: emptyList()

        for (dir in supplyDirs) {
            val name = dir.name.lowercase()

            if (currentNowUa == null) {
                currentNowUa = readIntOrNull("${dir.absolutePath}/current_now")
            }

            if (voltageNowUv == null) {
                voltageNowUv = readIntOrNull("${dir.absolutePath}/voltage_now")
            }

            if (temperatureRaw == null) {
                temperatureRaw = readIntOrNull("${dir.absolutePath}/temp")
            }

            if (capacity == null) {
                capacity = readIntOrNull("${dir.absolutePath}/capacity")
            }

            if (chargeType == ChargeType.UNKNOWN) {
                readStringOrNull("${dir.absolutePath}/charge_type")?.let {
                    chargeType = parseChargeType(it)
                }
            }

            if (status == null) {
                status = readStringOrNull("${dir.absolutePath}/status")
            }

            if (name == "usb") {
                if (usbVoltageMaxUv == null) {
                    usbVoltageMaxUv = readIntOrNull("${dir.absolutePath}/voltage_max")
                }
                if (usbCurrentMaxUa == null) {
                    usbCurrentMaxUa = readIntOrNull("${dir.absolutePath}/input_current_max")
                }
                if (!isPresent) {
                    readIntOrNull("${dir.absolutePath}/present")?.let {
                        isPresent = it == 1
                    }
                }
            }
        }

        val hasAnyData = currentNowUa != null || voltageNowUv != null ||
                temperatureRaw != null || capacity != null ||
                chargeType != ChargeType.UNKNOWN || status != null

        if (!hasAnyData) return null

        return SysfsData(
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
    }

    internal fun parseChargeType(raw: String): ChargeType {
        return when (raw.trim().lowercase()) {
            "turbo" -> ChargeType.TURBO
            "fast" -> ChargeType.FAST
            "normal" -> ChargeType.NORMAL
            "hyper_charge", "hyper" -> ChargeType.HYPER_CHARGE
            else -> ChargeType.UNKNOWN
        }
    }

    fun calculateWattage(voltageUv: Int, currentUa: Int): Float {
        if (voltageUv <= 0 || currentUa <= 0) return 0f
        return (voltageUv.toDouble() * currentUa.toDouble() / 1_000_000_000_000.0).toFloat()
    }

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

    private fun readIntOrNull(path: String): Int? {
        return try {
            readStringOrNull(path)?.trim()?.toInt()
        } catch (_: Exception) {
            null
        }
    }

    private fun readStringOrNull(path: String): String? {
        return try {
            val file = File(path)
            if (!file.exists() || !file.canRead()) return null
            BufferedReader(FileReader(file)).use { it.readLine() }
        } catch (_: Exception) {
            null
        }
    }
}
