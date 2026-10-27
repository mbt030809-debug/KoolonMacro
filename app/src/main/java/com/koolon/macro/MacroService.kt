package com.koolon.macro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

sealed class Step {
    data class Wait(val ms: Long) : Step()
    /** 화면의 text/contentDescription 에 [text] 가 포함된 클릭 가능 요소를 누른다 */
    data class ClickText(val text: String) : Step()
    /** 절대 좌표 탭 */
    data class Tap(val x: Float, val y: Float) : Step()
}

class MacroService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    /** 앱 실행 후 STEPS 순서대로 수행 */
    fun runMacro() {
        if (running) return
        val launch = findTargetApp()
        if (launch == null) {
            toast("'$TARGET_APP_NAME' 앱을 찾을 수 없음")
            return
        }
        running = true
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        Thread {
            try {
                Thread.sleep(LAUNCH_WAIT_MS)
                if (STEPS.isEmpty()) toast("STEPS 비어 있음: 앱 실행만 수행")
                for (step in STEPS) {
                    val ok = when (step) {
                        is Step.Wait -> { Thread.sleep(step.ms); true }
                        is Step.ClickText -> clickText(step.text)
                        is Step.Tap -> tap(step.x, step.y)
                    }
                    if (!ok) { toast("실패: $step"); return@Thread }
                    Thread.sleep(STEP_GAP_MS)
                }
            } finally {
                running = false
            }
        }.start()
    }

    private fun findTargetApp(): Intent? {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(main, PackageManager.MATCH_ALL)
            .firstOrNull { it.loadLabel(pm).toString().contains(TARGET_APP_NAME) }
            ?: return null
        return pm.getLaunchIntentForPackage(match.activityInfo.packageName)
    }

    private fun clickText(text: String): Boolean {
        repeat(10) {
            val root = rootInActiveWindow
            if (root != null) {
                val node = root.findAccessibilityNodeInfosByText(text).firstOrNull()
                var n: AccessibilityNodeInfo? = node
                while (n != null) {
                    if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    n = n.parent
                }
            }
            Thread.sleep(500)
        }
        return false
    }

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        return dispatchGesture(gesture, null, null)
    }

    /** 현재 화면의 요소를 텍스트로 만들어 클립보드에 복사 */
    fun dumpScreen() {
        val root = rootInActiveWindow
        if (root == null) {
            toast("화면을 읽을 수 없음 (앱이 접근성을 차단했을 가능성)")
            return
        }
        val sb = StringBuilder("package=${root.packageName}\n")
        walk(root, 0, sb)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dump", sb.toString()))
        toast("덤프 복사됨 (${sb.length}자)")
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int, sb: StringBuilder) {
        val r = Rect().also { n.getBoundsInScreen(it) }
        sb.append("  ".repeat(depth))
            .append(n.className).append(" text=").append(n.text)
            .append(" desc=").append(n.contentDescription)
            .append(" id=").append(n.viewIdResourceName)
            .append(" click=").append(n.isClickable)
            .append(" [").append(r.left).append(',').append(r.top).append(',')
            .append(r.right).append(',').append(r.bottom).append("]\n")
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1, sb) }
    }

    companion object {
        @Volatile var instance: MacroService? = null

        const val TARGET_APP_NAME = "코오롱"
        const val LAUNCH_WAIT_MS = 3000L
        const val STEP_GAP_MS = 800L

        /** 덤프 결과를 보고 채울 것. 예: Step.ClickText("난방"), Step.Tap(540f, 1200f) */
        val STEPS: List<Step> = emptyList()
    }
}
