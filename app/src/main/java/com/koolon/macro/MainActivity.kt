package com.koolon.macro

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "1. 접근성 서비스 켜기\n2. 화면 덤프: 누른 뒤 5초 안에 코오롱 앱의 난방 설정 화면으로 이동\n3. 홈 화면에 위젯 추가 후 탭"
        })
        root.addView(Button(this).apply {
            text = "1. 접근성 설정 열기"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "2. 화면 덤프 (5초 후)"
            setOnClickListener {
                val s = MacroService.instance
                if (s == null) {
                    Toast.makeText(this@MainActivity, "접근성 서비스가 꺼져 있음", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "5초 후 덤프", Toast.LENGTH_SHORT).show()
                    Handler(Looper.getMainLooper()).postDelayed({ s.dumpScreen() }, 5000)
                }
            }
        })
        root.addView(Button(this).apply {
            text = "3. 매크로 실행 테스트"
            setOnClickListener { MacroService.instance?.runMacro() }
        })
        setContentView(root)
    }
}
