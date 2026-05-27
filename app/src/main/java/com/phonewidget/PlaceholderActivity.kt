package com.phonewidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle

/**
 * 占位 Activity
 *
 * MIUI/HyperOS 要求应用至少有一个 Launcher Activity 才会被识别为"已安装"。
 * 点击图标后跳转到 Widget 添加页面，然后立即关闭。
 * 用户也可以从桌面长按直接添加 Widget，无需打开此 Activity。
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
            // 跳转失败就算了
        }

        // 立即关闭
        finish()
    }
}
