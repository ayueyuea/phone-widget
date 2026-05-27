package com.phonewidget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.appwidget.AppWidgetManager

/**
 * 占位 Activity
 *
 * MIUI/HyperOS 要求应用至少有一个 Activity 才会被识别为"已安装"。
 * 此 Activity 启动后立即跳转到 Widget 添加页面，用户无感知。
 */
class PlaceholderActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 尝试跳转到 Widget 添加页面
        try {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            startActivity(intent)
        } catch (e: Exception) {
            // 跳转失败就算了，直接关闭
        }

        // 立即关闭，用户无感知
        finish()
    }
}
