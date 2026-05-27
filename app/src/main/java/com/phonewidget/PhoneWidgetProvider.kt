package com.phonewidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews

/**
 * 桌面 Widget 提供者
 * 显示电池温度、充电功率、快充协议，并监听电池变化事件自动刷新
 */
class PhoneWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        // 电池状态变化 → 立即刷新
        val action = intent.action
        if (action == Intent.ACTION_BATTERY_CHANGED ||
            action == Intent.ACTION_BATTERY_LOW ||
            action == Intent.ACTION_POWER_CONNECTED ||
            action == Intent.ACTION_POWER_DISCONNECTED ||
            action == AppWidgetManager.ACTION_APPWIDGET_UPDATE ||
            action == AUTO_REFRESH
        ) {
            // Timber.d("收到广播: $action")
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, PhoneWidgetProvider::class.java)
            )
            
            // 批量更新所有widget
            if (appWidgetIds.isNotEmpty()) {
                // Timber.d("更新 ${appWidgetIds.size} 个widget")
                for (appWidgetId in appWidgetIds) {
                    try {
                        updateWidget(context, appWidgetManager, appWidgetId)
                    } catch (e: Exception) {
                        // Timber.e(e, "更新widget失败: $appWidgetId")
                        // 继续更新其他widget
                    }
                }
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        startAutoRefresh(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        stopAutoRefresh(context)
    }

    companion object {
        private const val AUTO_REFRESH = "com.phonewidget.AUTO_REFRESH"
        private const val INTERVAL_MS = 15_000L   // 15秒自动刷新

        private fun startAutoRefresh(context: Context) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getBroadcast(context, 999,
                    Intent(context, PhoneWidgetProvider::class.java).apply { action = AUTO_REFRESH },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + INTERVAL_MS, INTERVAL_MS, pi)
            } catch (_: Exception) {}
        }

        private fun stopAutoRefresh(context: Context) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getBroadcast(context, 999,
                    Intent(context, PhoneWidgetProvider::class.java).apply { action = AUTO_REFRESH },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                am.cancel(pi)
            } catch (_: Exception) {}
        }
        /**
         * 刷新单个 Widget 实例
         */
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            // 1. 获取电池数据
            val info = BatteryDataProvider.getBatteryInfo(context)

            // 2. 构建 RemoteViews
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // 3. 显示温度
            val tempColor = when {
                info.temperature > 45f -> Color.parseColor("#E53935") // 过热 → 红
                info.temperature > 40f -> Color.parseColor("#FB8C00") // 偏高 → 橙
                info.temperature > 35f -> Color.parseColor("#43A047") // 正常 → 绿
                else -> Color.parseColor("#1976D2")                    // 偏凉 → 蓝
            }
            views.setTextViewText(R.id.tv_temperature, String.format("%.1f°C", info.temperature))
            views.setTextColor(R.id.tv_temperature, tempColor)

            // 4. 显示充电功率和协议
            if (info.isCharging && info.wattage > 0) {
                views.setTextViewText(
                    R.id.tv_wattage,
                    String.format("%.1fW  %s", info.wattage, formatVoltageCurrent(info))
                )

                // 协议名称 + 数据来源
                val protocolLabel = buildProtocolLabel(info)
                views.setTextViewText(R.id.tv_charge_status, protocolLabel)

                // 功率数字颜色：功率越高颜色越暖
                val powerColor = when {
                    info.wattage >= 55f -> Color.parseColor("#E53935") // 澎湃秒充 → 红
                    info.wattage >= 30f -> Color.parseColor("#FB8C00") // Turbo → 橙
                    info.wattage >= 18f -> Color.parseColor("#43A047") // 快充 → 绿
                    else -> Color.parseColor("#757575")                 // 普通 → 灰
                }
                views.setTextColor(R.id.tv_wattage, powerColor)
            } else {
                views.setTextViewText(R.id.tv_wattage, "--W")
                views.setTextViewText(R.id.tv_charge_status, getDischargeStatus(info))
                views.setTextColor(R.id.tv_wattage, Color.parseColor("#757575"))
            }

            // 5. 显示电量
            val levelColor = when {
                info.level <= 15 -> Color.parseColor("#E53935")  // 低电量 → 红
                info.level <= 30 -> Color.parseColor("#FB8C00")  // 偏低 → 橙
                else -> Color.parseColor("#43A047")               // 正常 → 绿
            }
            views.setTextViewText(R.id.tv_battery_level, "🔋 ${info.level}%")
            views.setTextColor(R.id.tv_battery_level, levelColor)

            // 6. 背景色通过 widget_bg.xml drawable 处理，不再覆盖

            // 7. 点击刷新
            val clickIntent = Intent(context, PhoneWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)

            // 8. 更新
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /**
         * 格式化电压电流显示
         */
        private fun formatVoltageCurrent(info: BatteryDataProvider.BatteryInfo): String {
            val v = if (info.voltage >= 1f) String.format("%.1fV", info.voltage) else ""
            val a = if (info.current >= 0.1f) String.format("%.2fA", info.current) else ""
            return when {
                v.isNotEmpty() && a.isNotEmpty() -> "$v $a"
                v.isNotEmpty() -> v
                a.isNotEmpty() -> a
                else -> ""
            }
        }

        /**
         * 构建协议标签
         */
        private fun buildProtocolLabel(info: BatteryDataProvider.BatteryInfo): String {
            val emoji = when (info.chargeType) {
                XiaomiChargerReader.ChargeType.HYPER_CHARGE -> "⚡⚡"
                XiaomiChargerReader.ChargeType.TURBO -> "⚡"
                else -> "🔌"
            }

            val protocol = info.chargeProtocol.ifEmpty { "充电中" }

            // 数据来源标记（调试用，可以省略）
            return "$emoji $protocol"
        }

        /**
         * 非充电状态显示
         */
        private fun getDischargeStatus(info: BatteryDataProvider.BatteryInfo): String {
            return when {
                info.current < 0 -> {
                    val drain = -info.current
                    String.format("🔋 放电中 (%.2fA)", drain)
                }
                else -> "🔋 未充电"
            }
        }
    }
}
