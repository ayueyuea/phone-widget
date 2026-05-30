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
 * 显示电池温度、充电功率、快充协议，并监听电池变化事件自动刷新
 */
class PhoneWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        startAutoRefresh(context)
        for (appWidgetId in appWidgetIds) {
            try {
                updateWidget(context, appWidgetManager, appWidgetId)
            } catch (_: Exception) {
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        val action = intent.action
        if (action == Intent.ACTION_BATTERY_LOW ||
            action == Intent.ACTION_BATTERY_CHANGED ||
            action == Intent.ACTION_POWER_CONNECTED ||
            action == Intent.ACTION_POWER_DISCONNECTED ||
            action == AppWidgetManager.ACTION_APPWIDGET_UPDATE ||
            action == AUTO_REFRESH
        ) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, PhoneWidgetProvider::class.java)
            )

            if (appWidgetIds.isNotEmpty()) {
                for (appWidgetId in appWidgetIds) {
                    try {
                        updateWidget(context, appWidgetManager, appWidgetId)
                    } catch (_: Exception) {
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
        private const val INTERVAL_MS = 30_000L
        private const val REQUEST_CODE_REFRESH = 999

        private fun startAutoRefresh(context: Context) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getBroadcast(context, REQUEST_CODE_REFRESH,
                    Intent(context, PhoneWidgetProvider::class.java).apply { action = AUTO_REFRESH },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (am.canScheduleExactAlarms()) {
                        am.setInexactRepeating(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            android.os.SystemClock.elapsedRealtime() + INTERVAL_MS,
                            INTERVAL_MS, pi
                        )
                    } else {
                        am.setRepeating(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            android.os.SystemClock.elapsedRealtime() + INTERVAL_MS,
                            INTERVAL_MS, pi
                        )
                    }
                } else {
                    am.setInexactRepeating(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + INTERVAL_MS,
                        INTERVAL_MS, pi
                    )
                }
            } catch (_: Exception) {}
        }

        private fun stopAutoRefresh(context: Context) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getBroadcast(context, REQUEST_CODE_REFRESH,
                    Intent(context, PhoneWidgetProvider::class.java).apply { action = AUTO_REFRESH },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                am.cancel(pi)
            } catch (_: Exception) {}
        }

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val info = BatteryDataProvider.getBatteryInfo(context)
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            val tempColor = when {
                info.temperature > 45f -> Color.parseColor("#E53935")
                info.temperature > 40f -> Color.parseColor("#FB8C00")
                info.temperature > 35f -> Color.parseColor("#43A047")
                else -> Color.parseColor("#1976D2")
            }
            views.setTextViewText(R.id.tv_temperature, String.format("%.1f°C", info.temperature))
            views.setTextColor(R.id.tv_temperature, tempColor)

            if (info.isCharging) {
                if (info.wattage > 0) {
                    views.setTextViewText(R.id.tv_wattage,
                        String.format("%.1fW  %s", info.wattage, formatVoltageCurrent(info)))
                    views.setTextColor(R.id.tv_wattage, when {
                        info.wattage >= 55f -> Color.parseColor("#E53935")
                        info.wattage >= 30f -> Color.parseColor("#FB8C00")
                        info.wattage >= 18f -> Color.parseColor("#43A047")
                        info.wattage >= 5f -> Color.parseColor("#FF9800")
                        else -> Color.parseColor("#FFC107")
                    })
                    views.setTextViewText(R.id.tv_charge_status, buildProtocolLabel(info))
                } else {
                    views.setTextViewText(R.id.tv_wattage, "充电中")
                    views.setTextColor(R.id.tv_wattage, Color.parseColor("#43A047"))
                    views.setTextViewText(R.id.tv_charge_status, "🔌 等待充电")
                }
                views.setViewVisibility(R.id.tv_charge_status, android.view.View.VISIBLE)
            } else {
                views.setTextViewText(R.id.tv_wattage, "--W")
                views.setTextViewText(R.id.tv_charge_status, getDischargeStatus(info))
                views.setTextColor(R.id.tv_wattage, Color.parseColor("#757575"))
                views.setViewVisibility(R.id.tv_charge_status, android.view.View.VISIBLE)
            }

            val levelColor = when {
                info.level <= 15 -> Color.parseColor("#E53935")
                info.level <= 30 -> Color.parseColor("#FB8C00")
                else -> Color.parseColor("#43A047")
            }
            views.setTextViewText(R.id.tv_battery_level, String.format("🔋 %d%%", info.level))
            views.setTextColor(R.id.tv_battery_level, levelColor)

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

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun formatVoltageCurrent(info: BatteryDataProvider.BatteryInfo): String {
            val v = if (info.voltage >= 3f) String.format("%.1fV", info.voltage) else ""
            val a = if (info.current >= 0.05f) String.format("%.2fA", info.current) else ""
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
                XiaomiChargerReader.ChargeType.FAST -> "⚡"
                else -> "🔌"
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
