package com.phonewidget

import android.app.Application
import timber.log.Timber

/**
 * 应用入口，用于初始化全局配置
 */
class PhoneWidgetApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // 初始化日志（仅在debug版本启用）
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("PhoneWidgetApplication initialized")
        }
        
        // 可以在这里初始化其他全局配置
        // 例如：WorkManager、数据库、网络客户端等
    }
}