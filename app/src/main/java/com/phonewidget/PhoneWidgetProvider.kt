package com.phonewidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.widget.RemoteViews

/**
 * 桌面 Widget 提供者
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

        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                // 插入充电器 → 加快刷新频率（每 10 秒）
                scheduleWidgetUpdate(context, REFRESH_INTERVAL_CHARGING_MS)
                updateAllWidgets(context)
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                // 拔掉充电器 → 放慢刷新频率
                scheduleWidgetUpdate(context, REFRESH_INTERVAL_IDLE_MS)
                updateAllWidgets(context)
            }
            Intent.ACTION_BATTERY_LOW -> {
                updateAllWidgets(context)
            }
            AppWidgetManager.ACTION_APPWIDGET_UPDATE -> {
                updateAllWidgets(context)
            }
            ACTION_REFRESH -> {
                // 定时刷新触发的广播
                updateAllWidgets(context)
                // 重新调度下一次
                rescheduleRefresh(context)
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // 首次添加 Widget → 启动定时刷新
        scheduleWidgetUpdate(context, REFRESH_INTERVAL_IDLE_MS)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // 所有 Widget 被移除 → 取消定时刷新
        cancelWidgetUpdate(context)
    }

    private fun updateAllWidgets(context: Context) {
        try {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, PhoneWidgetProvider::class.java)
            )
            for (appWidgetId in appWidgetIds) {
                updateWidget(context, appWidgetManager, appWidgetId)
            }
        } catch (e: Exception) {
            // 静默处理
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.phonewidget.ACTION_REFRESH"
        private const val REFRESH_INTERVAL_CHARGING_MS = 10_000L   // 充电时 10 秒
        private const val REFRESH_INTERVAL_IDLE_MS = 60_000L       // 非充电时 60 秒
        private const val REQUEST_CODE = 1001

        /**
         * 调度下一次定时刷新
         */
        private fun scheduleWidgetUpdate(context: Context, intervalMs: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pendingIntent = getRefreshPendingIntent(context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // API 23+ 使用 setExactAndAllowWhileIdle（省电模式下也能触发）
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + intervalMs,
                    pendingIntent
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                // API 19-22 使用 setExact
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + intervalMs,
                    pendingIntent
                )
            } else {
                // API 18 以下使用 setRepeating
                alarmManager.setRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + intervalMs,
                    intervalMs,
                    pendingIntent
                )
            }
        }

        /**
         * 取消定时刷新
         */
        private fun cancelWidgetUpdate(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            alarmManager.cancel(getRefreshPendingIntent(context))
        }

        /**
         * 重新调度刷新（根据当前是否充电）
         */
        private fun rescheduleRefresh(context: Context) {
            val info = BatteryDataProvider.getBatteryInfo(context)
            val interval = if (info.isCharging) REFRESH_INTERVAL_CHARGING_MS
            else REFRESH_INTERVAL_IDLE_MS
            scheduleWidgetUpdate(context, interval)
        }

        /**
         * 获取刷新 PendingIntent
         */
        private fun getRefreshPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, PhoneWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            return PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * 刷新单个 Widget
         */
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val info = BatteryDataProvider.getBatteryInfo(context)
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // 温度
            val tempColor = when {
                info.temperature > 45f -> Color.parseColor("#E53935")
                info.temperature > 40f -> Color.parseColor("#FB8C00")
                info.temperature > 35f -> Color.parseColor("#43A047")
                else -> Color.parseColor("#1976D2")
            }
            views.setTextViewText(R.id.tv_temperature, String.format("%.1f°C", info.temperature))
            views.setTextColor(R.id.tv_temperature, tempColor)

            // 功率/充电状态
            if (info.isCharging) {
                val wattText = if (info.wattage > 0) {
                    String.format("%.1fW", info.wattage)
                } else {
                    "充电中"
                }
                val extraText = formatVoltageCurrent(info)
                views.setTextViewText(R.id.tv_wattage, if (extraText.isNotEmpty()) "$wattText  $extraText" else wattText)

                val protocol = buildProtocolLabel(info)
                views.setTextViewText(R.id.tv_charge_status, protocol)

                val powerColor = when {
                    info.wattage >= 55f -> Color.parseColor("#E53935")
                    info.wattage >= 30f -> Color.parseColor("#FB8C00")
                    info.wattage >= 18f -> Color.parseColor("#43A047")
                    info.wattage > 0f -> Color.parseColor("#FF9800")
                    else -> Color.parseColor("#43A047")
                }
                views.setTextColor(R.id.tv_wattage, powerColor)
            } else {
                views.setTextViewText(R.id.tv_wattage, "--W")
                views.setTextViewText(R.id.tv_charge_status, getDischargeStatus(info))
                views.setTextColor(R.id.tv_wattage, Color.parseColor("#757575"))
            }

            // 电量
            val levelColor = when {
                info.level <= 15 -> Color.parseColor("#E53935")
                info.level <= 30 -> Color.parseColor("#FB8C00")
                else -> Color.parseColor("#43A047")
            }
            views.setTextViewText(R.id.tv_battery_level, "🔋 ${info.level}%")
            views.setTextColor(R.id.tv_battery_level, levelColor)

            // 点击刷新
            val clickIntent = Intent(context, PhoneWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, appWidgetId, clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

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

        private fun buildProtocolLabel(info: BatteryDataProvider.BatteryInfo): String {
            val emoji = when (info.chargeType) {
                XiaomiChargerReader.ChargeType.HYPER_CHARGE -> "⚡⚡"
                XiaomiChargerReader.ChargeType.TURBO -> "⚡"
                else -> if (info.wattage >= 18f) "⚡" else "🔌"
            }
            val protocol = info.chargeProtocol.ifEmpty { "充电中" }
            return "$emoji $protocol"
        }

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
