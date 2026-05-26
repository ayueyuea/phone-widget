package com.phonewidget

import org.junit.Assert.*
import org.junit.Test

/**
 * BatteryDataProvider 单元测试
 */
class BatteryDataProviderTest {
    
    @Test
    fun testHealthToString() {
        // 测试电池健康状态转换
        assertEquals("过冷", BatteryDataProvider.healthToString(android.os.BatteryManager.BATTERY_HEALTH_COLD))
        assertEquals("损坏", BatteryDataProvider.healthToString(android.os.BatteryManager.BATTERY_HEALTH_DEAD))
        assertEquals("良好", BatteryDataProvider.healthToString(android.os.BatteryManager.BATTERY_HEALTH_GOOD))
        assertEquals("过热", BatteryDataProvider.healthToString(android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT))
        assertEquals("过压", BatteryDataProvider.healthToString(android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE))
        assertEquals("异常", BatteryDataProvider.healthToString(android.os.BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE))
        assertEquals("未知", BatteryDataProvider.healthToString(999)) // 未知状态
    }
    
    @Test
    fun testXiaomiChargerReader() {
        // 测试充电类型解析
        assertEquals(XiaomiChargerReader.ChargeType.TURBO, 
            XiaomiChargerReader.parseChargeType("turbo"))
        assertEquals(XiaomiChargerReader.ChargeType.FAST, 
            XiaomiChargerReader.parseChargeType("fast"))
        assertEquals(XiaomiChargerReader.ChargeType.NORMAL, 
            XiaomiChargerReader.parseChargeType("normal"))
        assertEquals(XiaomiChargerReader.ChargeType.HYPER_CHARGE, 
            XiaomiChargerReader.parseChargeType("hyper_charge"))
        assertEquals(XiaomiChargerReader.ChargeType.HYPER_CHARGE, 
            XiaomiChargerReader.parseChargeType("hyper"))
        assertEquals(XiaomiChargerReader.ChargeType.UNKNOWN, 
            XiaomiChargerReader.parseChargeType("unknown"))
        
        // 测试功率计算
        val wattage = XiaomiChargerReader.calculateWattage(5000000, 2000000) // 5V, 2A
        assertEquals(10.0f, wattage, 0.01f)
        
        // 测试零值处理
        assertEquals(0f, XiaomiChargerReader.calculateWattage(0, 2000000), 0.01f)
        assertEquals(0f, XiaomiChargerReader.calculateWattage(5000000, 0), 0.01f)
    }
    
    @Test
    fun testChargeProtocolDetection() {
        // 测试协议检测
        val protocol1 = XiaomiChargerReader.detectChargeProtocol(
            9000000, 6000000, XiaomiChargerReader.ChargeType.UNKNOWN
        ) // 9V * 6A = 54W
        assertEquals("Turbo快充", protocol1)
        
        val protocol2 = XiaomiChargerReader.detectChargeProtocol(
            5000000, 3000000, XiaomiChargerReader.ChargeType.UNKNOWN
        ) // 5V * 3A = 15W
        assertEquals("普通充电", protocol2)
        
        // 测试已知充电类型
        val protocol3 = XiaomiChargerReader.detectChargeProtocol(
            0, 0, XiaomiChargerReader.ChargeType.HYPER_CHARGE
        )
        assertEquals("小米澎湃秒充", protocol3)
    }
}