package com.koolon.macro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class MacroService : AccessibilityService() {

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var recording: StringBuilder? = null
    private var watcher: LocationWatcher? = null
    private var overlay: View? = null
    @Volatile private var quick = false
    private val autoStop = Runnable {
        if (quick) { toast("3분 경과: 녹화 자동 종료"); finishQuickRecord() }
    }

    override fun onServiceConnected() {
        instance = this
        watcher = LocationWatcher(this).also { it.start() }
        Scheduler.rescheduleAll(this)
        Shortcuts.update(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        watcher?.stop()
        hideOverlay()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val rec = recording ?: return
        if (running) return // 재생 중 클릭은 기록하지 않음
        if (event == null || event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        if (event.packageName == packageName || event.packageName == "com.android.systemui") return
        val src = event.source
        val label = listOf(
            event.text.joinToString(" "),
            event.contentDescription?.toString(),
            src?.text?.toString(),
            src?.contentDescription?.toString(),
            firstText(src, 3),
        ).firstOrNull { !it.isNullOrBlank() }?.replace(Regex("\\s+"), " ")?.trim()
        if (label != null) {
            val idx = indexOfMatch(label, src)
            rec.append("click ").append(label)
            if (idx > 1) rec.append(" @").append(idx)
            rec.append('\n')
        } else if (src != null) {
            val r = Rect().also { src.getBoundsInScreen(it) }
            rec.append("tap ").append(r.centerX()).append(' ').append(r.centerY()).append('\n')
        }
    }

    override fun onInterrupt() {}

    /** 같은 글자가 여러 개일 때 눌린 요소가 몇 번째인지 */
    private fun indexOfMatch(label: String, src: AccessibilityNodeInfo?): Int {
        if (src == null) return 1
        val sb = Rect().also { src.getBoundsInScreen(it) }
        val i = matches(rootInActiveWindow, label).indexOfFirst { n -> Rect().also { n.getBoundsInScreen(it) } == sb }
        return if (i < 0) 1 else i + 1
    }

    private fun firstText(n: AccessibilityNodeInfo?, depth: Int): String? {
        if (n == null || depth < 0) return null
        n.text?.takeIf { it.isNotBlank() }?.let { return it.toString() }
        n.contentDescription?.takeIf { it.isNotBlank() }?.let { return it.toString() }
        for (i in 0 until n.childCount) firstText(n.getChild(i), depth - 1)?.let { return it }
        return null
    }

    // ---- 녹화 / 위치 ----
    fun startRecording() { recording = StringBuilder() }
    fun stopRecording(): String = (recording?.toString() ?: "").also { recording = null }
    val isRecording get() = recording != null
    /** 앱을 켜고 녹화 시작. 알림의 종료 버튼 or 이 앱으로 복귀하면 자동 저장 */
    fun beginQuickRecord() {
        val launch = findTargetApp()
        if (launch == null) { toast("'$TARGET_APP_NAME' 앱을 찾을 수 없음"); return }
        startRecording()
        quick = true
        ui.postDelayed(autoStop, 180_000)
        Notifier.recording(this)
        showOverlay()
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        toast("녹화 중: 평소처럼 조작하세요. 끝나면 화면의 빨간 '녹화 종료' 버튼 누르기")
    }

    fun finishQuickRecord() {
        ui.removeCallbacks(autoStop)
        hideOverlay()
        Notifier.cancelRecording(this)
        if (!quick || !isRecording) { quick = false; return }
        quick = false
        val script = stopRecording()
        if (script.isBlank()) { toast("기록된 동작이 없음"); return }
        val name = "녹화 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.KOREA).format(java.util.Date())
        MacroStore.save(this, Macro(name, script))
        Shortcuts.update(this)
        report(name, "저장됨 (${script.lines().count { it.isNotBlank() }}단계). 앱에서 확인 후 위젯 추가")
    }

    /** 다른 앱 위에 뜨는 드래그 가능한 종료 버튼 (접근성 오버레이: 별도 권한 불필요) */
    private fun showOverlay() = ui.post {
        if (overlay != null) return@post
        val wm = getSystemService(WindowManager::class.java)
        val tv = TextView(this).apply {
            text = "⏹ 녹화 종료"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(44, 26, 44, 26)
            background = GradientDrawable().apply { setColor(0xE6D32F2F.toInt()); cornerRadius = 100f }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 220 }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) moved = true
                    lp.x = ox + dx.toInt(); lp.y = oy + dy.toInt()
                    wm.updateViewLayout(tv, lp)
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) finishQuickRecord(); true }
                else -> false
            }
        }
        try {
            wm.addView(tv, lp)
            overlay = tv
        } catch (e: Exception) {
            toast("종료 버튼 표시 실패: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun hideOverlay() = ui.post {
        overlay?.let {
            try {
                getSystemService(WindowManager::class.java).removeView(it)
            } catch (e: Exception) {
            }
        }
        overlay = null
    }

    fun refreshLocation() { watcher?.start() }

    private fun toast(msg: String) = ui.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    private fun report(name: String, msg: String) {
        toast("$name: $msg")
        Notifier.post(this, name, msg)
    }

    // ---- 실행 ----
    fun runMacro(name: String) {
        val m = MacroStore.get(this, name)
        if (m == null) report(name, "매크로를 찾을 수 없음") else runMacro(m)
    }

    fun runMacro(macro: Macro) {
        if (running) { toast("이미 실행 중"); return }
        val (steps, errors) = Step.parse(macro.script)
        if (errors.isNotEmpty()) { report(macro.name, "스크립트 오류 " + errors.first()); return }
        running = true
        RunLog.clear()
        RunLog.add("실행: ${macro.name} (${steps.size}단계)")
        Thread {
            try {
                execute(macro.name, steps)
            } catch (e: Exception) {
                report(macro.name, "오류: ${e.message}")
            } finally {
                running = false
            }
        }.start()
    }

    private fun execute(name: String, steps: List<Step>) {
        if (!prepareScreen(name)) { RunLog.add("화면 준비 실패(잠금)"); return }
        var last = ""
        for (attempt in 1..MAX_ATTEMPTS) {
            val (ok, msg) = attempt(steps)
            if (ok) { report(name, msg); return }
            last = msg
            if (attempt < MAX_ATTEMPTS) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                Thread.sleep(1000)
            }
        }
        report(name, "실패: $last")
    }

    /** @return (성공 여부, 메시지) */
    private fun attempt(steps: List<Step>): Pair<Boolean, String> {
        val launch = findTargetApp() ?: return false to "'$TARGET_APP_NAME' 앱을 찾을 수 없음"
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        Thread.sleep(LAUNCH_WAIT_MS)
        RunLog.add("앱 실행 후 화면: " + screenSummary())
        for ((i, step) in steps.withIndex()) {
            val n = i + 1
            RunLog.add("$n. $step")
            val err: String? = when (step) {
                is Step.Wait -> { Thread.sleep(step.ms); null }
                is Step.ClickText -> if (clickText(step.text, step.index)) null else "${n}단계 '${step.text}' @${step.index} 못 찾음"
                is Step.Tap -> if (tap(step.x, step.y)) null else "${n}단계 탭 실패"
                is Step.Temp -> if (setTemp(step)) null else "${n}단계 온도 ${step.target}도 설정 실패"
                is Step.StopIf -> if (isVisible(step.text)) return true to "이미 완료됨 ('${step.text}' 확인)" else null
                Step.Home -> { performGlobalAction(GLOBAL_ACTION_HOME); null }
                Step.Back -> { performGlobalAction(GLOBAL_ACTION_BACK); null }
            }
            if (err != null) {
                RunLog.add("실패: $err")
                RunLog.add("화면: " + screenSummary())
                return false to err
            }
            Thread.sleep(STEP_GAP_MS)
        }
        return true to "완료"
    }

    /** 화면 켜기 + (비밀번호 없는) 잠금 밀어서 해제 */
    private fun prepareScreen(name: String): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isInteractive) {
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "koolon:wake",
            )
            wl.acquire(15_000)
            Thread.sleep(1000)
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            if (km.isKeyguardSecure) {
                report(name, "잠금 상태: 화면을 직접 해제해야 실행됨")
                return false
            }
            val dm = resources.displayMetrics
            val path = Path().apply {
                moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.85f)
                lineTo(dm.widthPixels / 2f, dm.heightPixels * 0.2f)
            }
            dispatchGesture(
                GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build(),
                null, null,
            )
            Thread.sleep(1200)
        }
        return true
    }

    private fun findTargetApp(): Intent? {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(q, PackageManager.MATCH_ALL)
            .firstOrNull { it.loadLabel(pm).toString().contains(TARGET_APP_NAME) } ?: return null
        return pm.getLaunchIntentForPackage(match.activityInfo.packageName)
    }

    /** WebView 내부는 findAccessibilityNodeInfosByText 가 비어 나오므로 트리를 직접 탐색 */
    private fun matches(root: AccessibilityNodeInfo?, text: String): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val hits = mutableListOf<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            if (n.text?.contains(text) == true || n.contentDescription?.contains(text) == true) hits.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        val vis = hits.filter { it.isVisibleToUser }
        return if (vis.isNotEmpty()) vis else hits
    }

    private fun findNode(root: AccessibilityNodeInfo?, text: String, index: Int = 1) =
        matches(root, text).getOrNull(index - 1)

    private fun isVisible(text: String) = findNode(rootInActiveWindow, text) != null

    private fun clickText(text: String, index: Int = 1, retries: Int = 10): Boolean {
        repeat(retries) {
            val node = findNode(rootInActiveWindow, text, index)
            if (node != null) {
                val r = Rect().also { node.getBoundsInScreen(it) }
                var web = false
                var clickable: AccessibilityNodeInfo? = null
                var n: AccessibilityNodeInfo? = node
                while (n != null) {
                    if (n.className?.contains("WebView") == true) web = true
                    if (clickable == null && n.isClickable) clickable = n
                    n = n.parent
                }
                RunLog.add("  찾음: '$text' web=$web click=${clickable != null} bounds=$r")
                // 웹뷰는 실제 터치(제스처)가 가장 확실
                if (web && !r.isEmpty && tap(r.exactCenterX(), r.exactCenterY())) return true
                if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
                if (!r.isEmpty && tap(r.exactCenterX(), r.exactCenterY())) return true
            }
            Thread.sleep(500)
        }
        return false
    }

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        val ok = dispatchGesture(g, null, null)
        Thread.sleep(200)
        return ok
    }

    // ---- 온도 ----
    private fun readTemp(label: String): Int? {
        val texts = mutableListOf<String>()
        collectTexts(rootInActiveWindow, texts)
        val start = if (label.isBlank()) 0 else texts.indexOfFirst { it.contains(label) } + 1
        if (start == 0 && label.isNotBlank()) return null
        val re = Regex("(\\d{1,2})(?:\\.\\d)?\\s*(°|˚|℃|도)")
        return texts.drop(start).firstNotNullOfOrNull { re.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    }

    private fun screenSummary(): String {
        val root = rootInActiveWindow ?: return "(화면 읽기 불가)"
        val t = mutableListOf<String>()
        collectTexts(root, t)
        return "pkg=${root.packageName} " + t.filter { it.isNotBlank() }.take(60).joinToString(" | ")
    }

    private fun collectTexts(n: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (n == null) return
        n.text?.let { out.add(it.toString()) }
        n.contentDescription?.let { out.add(it.toString()) }
        for (i in 0 until n.childCount) collectTexts(n.getChild(i), out)
    }

    private fun setTemp(s: Step.Temp): Boolean {
        repeat(40) {
            val cur = readTemp(s.label) ?: return false
            RunLog.add("  현재 설정 온도 ${cur}")
            if (cur == s.target) return true
            if (!clickText(if (cur < s.target) s.plus else s.minus, 1, 3)) return false
            Thread.sleep(400)
        }
        return false
    }

    // ---- 화면 덤프 ----
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
        const val MAX_ATTEMPTS = 2
    }
}
