package com.phonewidget

import android.appwidget.AppWidgetManager
import android.app.PendingIntent
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * 极致简化版 Widget — 只显示温度、电量、充电状态
 */
class PhoneWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val info = BatteryDataProvider.getBatteryInfo(context)
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            views.setTextViewText(R.id.tv_temperature, String.format("%.1f°C", info.temperature))
            views.setTextViewText(R.id.tv_battery_level, "🔋 ${info.level}%")

            if (info.isCharging) {
                val w = if (info.wattage > 0) String.format("%.1fW", info.wattage) else "充电中"
                views.setTextViewText(R.id.tv_wattage, w)
                views.setTextViewText(R.id.tv_charge_status, info.chargeProtocol.ifEmpty { "充电中" })
            } else {
                views.setTextViewText(R.id.tv_wattage, "--W")
                views.setTextViewText(R.id.tv_charge_status, "🔋 未充电")
            }

            // 点击刷新
            val pi = PendingIntent.getBroadcast(context, 0,
                Intent(context, PhoneWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_container, pi)

            manager.updateAppWidget(id, views)
        }
    }
}
