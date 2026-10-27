package com.koolon.macro

import android.Manifest
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        status = TextView(this)
        root.addView(status)
        root.addView(btn("접근성 설정 열기") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        root.addView(btn("권한 요청 (알림·위치)") { requestPerms() })
        root.addView(btn("위치 '항상 허용' 설정 열기") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        root.addView(btn("현재 위치를 집으로 저장") { saveHome() })
        root.addView(btn("화면 덤프 (5초 후 클립보드 복사)") {
            val s = MacroService.instance
            if (s == null) toast("접근성 서비스가 꺼져 있음") else {
                toast("5초 후 덤프")
                Handler(Looper.getMainLooper()).postDelayed({ s.dumpScreen() }, 5000)
            }
        })
        root.addView(btn("+ 새 매크로") { startActivity(Intent(this, EditActivity::class.java)) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        status.text = "접근성: " + (if (MacroService.instance != null) "켜짐" else "꺼짐") +
            "\n집 위치: " + (if (MacroStore.home(this) != null) "저장됨" else "미설정")
        list.removeAllViews()
        MacroStore.all(this).forEach { m ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(
                TextView(this).apply {
                    text = m.name + (m.time?.let { "  ⏰$it" } ?: "") + (if (m.arrive) "  📍" else "")
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

    private fun requestPerms() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(p.toTypedArray(), 1)
        toast("집 도착 트리거를 쓰려면 위치를 '항상 허용'으로 바꿔야 함")
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        MacroService.instance?.refreshLocation()
    }

    private fun saveHome() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            toast("먼저 '권한 요청'을 누르세요"); return
        }
        val lm = getSystemService(LocationManager::class.java)
        val loc = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }
            .maxByOrNull { it.time }
        if (loc == null) {
            toast("위치를 못 얻음: 지도 앱에서 현재 위치를 한 번 갱신한 뒤 재시도")
        } else {
            MacroStore.setHome(this, loc.latitude, loc.longitude)
            MacroService.instance?.refreshLocation()
            toast("집 위치 저장됨 (반경 ${LocationWatcher.RADIUS_M.toInt()}m)")
            onResume()
        }
    }
}
