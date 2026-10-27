package com.koolon.macro

import android.Manifest
import android.app.AlarmManager
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        status = TextView(this)
        root.addView(status)
        root.addView(btn("⏺ 녹화: 내가 하는 동작 따라하기") {
            val s = MacroService.instance
            if (s == null) toast("접근성 서비스가 꺼져 있음") else s.beginQuickRecord()
        })
        root.addView(btn("① 앱 정보 열기 (⋮ → 제한된 설정 허용)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        root.addView(btn("② 접근성 설정 열기") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        root.addView(btn("권한 요청 (알림·정확한 알람)") { requestPerms() })
        root.addView(btn("화면 덤프 (5초 후 클립보드 복사)") {
            val s = MacroService.instance
            if (s == null) toast("접근성 서비스가 꺼져 있음") else {
                toast("5초 후 덤프")
                Handler(Looper.getMainLooper()).postDelayed({ s.dumpScreen() }, 5000)
            }
        })
        root.addView(btn("📋 마지막 실행 로그 복사") {
            val t = RunLog.text()
            if (t.isEmpty()) toast("로그 없음: 매크로를 먼저 실행하세요") else {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("log", t))
                toast("로그 복사됨. 채팅에 붙여넣기")
            }
        })
        logView = TextView(this).apply { textSize = 11f }
        root.addView(logView)
        root.addView(btn("+ 예시 만들기: 안방 난방 26도") {
            MacroStore.save(this, Macro(MacroStore.SAMPLE_NAME, MacroStore.SAMPLE_SCRIPT))
            Shortcuts.update(this)
            toast("'${MacroStore.SAMPLE_NAME}' 생성됨. 목록에서 ▶ 실행")
            onResume()
        })
        root.addView(btn("+ 예시 만들기: 안방 외출 (난방 → 외출)") {
            MacroStore.save(this, Macro(MacroStore.SAMPLE2_NAME, MacroStore.SAMPLE2_SCRIPT))
            Shortcuts.update(this)
            toast("'${MacroStore.SAMPLE2_NAME}' 생성됨. 목록에서 ▶ 실행")
            onResume()
        })
        root.addView(android.widget.CheckBox(this).apply {
            text = "실행 후 앱 자동 종료 (최근 앱에서 밀어내기)"
            isChecked = MacroStore.autoClose(this@MainActivity)
            setOnCheckedChangeListener { _, v -> MacroStore.setAutoClose(this@MainActivity, v) }
        })
        root.addView(btn("+ 새 매크로") { startActivity(Intent(this, EditActivity::class.java)) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        MacroService.instance?.finishQuickRecord()
        @Suppress("DEPRECATION")
        val build = packageManager.getPackageInfo(packageName, 0).versionCode
        status.text = "빌드 #$build\n접근성: " + (if (MacroService.instance != null) "켜짐" else "꺼짐") +
            "\n정확한 알람: " + (if (Build.VERSION.SDK_INT < 31 || getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) "허용" else "불허 (예약 시각이 늦을 수 있음)")
        logView.text = RunLog.text().takeLast(500)
        list.removeAllViews()
        MacroStore.all(this).forEach { m ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(
                TextView(this).apply {
                    text = m.name + (m.time?.let { "  ⏰$it" } ?: "") + (if (m.time != null) daysLabel(m.days) else "")
                    textSize = 16f
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(btn("▶") {
                val s = MacroService.instance
                if (s == null) toast("접근성 서비스가 꺼져 있음") else s.runMacro(m.name)
            })
            row.addView(btn("편집") {
                startActivity(Intent(this, EditActivity::class.java).putExtra("name", m.name))
            })
            list.addView(row)
        }
    }

    private fun btn(label: String, onClick: (View) -> Unit) = Button(this).apply {
        text = label
        setOnClickListener(onClick)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun daysLabel(d: Int): String =
        if (d and 127 == 127) "" else " (" + listOf("월", "화", "수", "목", "금", "토", "일")
            .filterIndexed { i, _ -> d and (1 shl i) != 0 }.joinToString("") + ")"

    private fun requestPerms() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")),
            )
        }
    }
}
