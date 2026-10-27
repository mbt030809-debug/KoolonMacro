package com.koolon.macro

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 위젯 추가 시 실행할 매크로를 고르는 화면 */
class WidgetConfigActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(TextView(this).apply { text = "이 위젯으로 실행할 매크로 선택" })
        MacroStore.all(this).forEach { m ->
            root.addView(Button(this).apply {
                text = m.name
                setOnClickListener {
                    MacroStore.bindWidget(this@WidgetConfigActivity, id, m.name)
                    MacroWidget.update(this@WidgetConfigActivity, AppWidgetManager.getInstance(this@WidgetConfigActivity), id)
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                    finish()
                }
            })
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
