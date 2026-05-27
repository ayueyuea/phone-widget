package com.phonewidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews

/**
 * 桌面 Widget 提供者
 */
class PhoneWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            Intent.ACTION_BATTERY_LOW,
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            ACTION_REFRESH -> {
                updateAllWidgets(context)
                if (intent.action != ACTION_REFRESH) {
                    rescheduleRefresh(context)
                }
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        scheduleUpdate(context, UPDATE_INTERVAL_NORMAL)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelUpdate(context)
    }

    private fun updateAllWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, PhoneWidgetProvider::class.java))
        for (id in ids) {
            updateWidget(context, manager, id)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.phonewidget.REFRESH"
        private const val UPDATE_INTERVAL_NORMAL = 30_000L   // 30秒
        private const val REQ_REFRESH = 1001

        private fun scheduleUpdate(context: Context, ms: Long) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(context, REQ_REFRESH,
                Intent(context, PhoneWidgetProvider::class.java).apply { action = ACTION_REFRESH },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + ms, pi)
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + ms, pi)
            }
        }

        private fun cancelUpdate(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(context, REQ_REFRESH,
                Intent(context, PhoneWidgetProvider::class.java).apply { action = ACTION_REFRESH },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.cancel(pi)
        }

        private fun rescheduleRefresh(context: Context) {
            val info = BatteryDataProvider.getBatteryInfo(context)
            val interval = if (info.isCharging) 10_000L else UPDATE_INTERVAL_NORMAL
            scheduleUpdate(context, interval)
        }

        fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val info = BatteryDataProvider.getBatteryInfo(context)
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // 温度
            val tempText = try {
                String.format("%.1f°C", info.temperature)
            } catch (e: Exception) { "--.-°C" }
            views.setTextViewText(R.id.tv_temperature, tempText)

            // 电量
            views.setTextViewText(R.id.tv_battery_level, "🔋 ${info.level}%")

            // 充电状态和功率
            if (info.isCharging) {
                val wattText = if (info.wattage > 0) String.format("%.1fW", info.wattage) else "充电中"
                views.setTextViewText(R.id.tv_wattage, wattText)
                views.setTextViewText(R.id.tv_charge_status, info.chargeProtocol.ifEmpty { "充电中" })
            } else {
                views.setTextViewText(R.id.tv_wattage, "--W")
                views.setTextViewText(R.id.tv_charge_status, "🔋 未充电")
            }

            // 点击刷新
            val clickIntent = Intent(context, PhoneWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(id))
            }
            val pi = PendingIntent.getBroadcast(context, id, clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_container, pi)

            manager.updateAppWidget(id, views)
        }
    }
}
