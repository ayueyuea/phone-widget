package com.phonewidget

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import android.widget.LinearLayout
import android.graphics.Color
import android.view.Gravity

/**
 * 启动界面
 *
 * 澎湃 OS 要求应用必须被打开过一次，Widget 才能正常更新。
 * 这个页面提示用户去桌面添加 Widget。
 */
class PlaceholderActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tv = TextView(this).apply {
            text = "📱 手机状态 Widget\n\n请在桌面长按空白处\n→ 添加小部件\n→ 选择「手机状态 Widget」"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#333333"))
            setPadding(40, 40, 40, 40)
        }

        val layout = LinearLayout(this).apply {
            addView(tv)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            gravity = Gravity.CENTER
        }

        setContentView(layout)
    }
}
