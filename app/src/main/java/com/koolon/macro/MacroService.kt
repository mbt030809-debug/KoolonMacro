package com.koolon.macro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.os.Build
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
    private var overlay: View? = null
    @Volatile private var quick = false
    @Volatile private var targetLabel = ""
    @Volatile private var targetPkg = ""
    @Volatile private var recordApp = ""
    private val autoStop = Runnable {
        if (quick) { toast("3분 경과: 녹화 자동 종료"); finishQuickRecord() }
    }

    /** 잠금 해제(USER_PRESENT) 시, 잠금 중 예약 시각이 지난 매크로를 실행 */
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val names = MacroStore.takePending(c, PENDING_MAX_AGE_MS)
            if (names.isNotEmpty()) ui.postDelayed({ runAll(names) }, 1500)
        }
    }

    override fun onServiceConnected() {
        instance = this
        val f = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlockReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(unlockReceiver, f)
        Scheduler.rescheduleAll(this)
        Shortcuts.update(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        try { unregisterReceiver(unlockReceiver) } catch (e: Exception) {}
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
    fun beginQuickRecord(app: String) {
        recordApp = app
        val launch = findTargetApp(app)
        if (launch == null) { toast("'$app' 앱을 찾을 수 없음"); return }
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
        MacroStore.save(this, Macro(name, script, app = recordApp.ifBlank { MacroStore.defaultApp(this) }))
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

    /** 진단용: 매크로가 터치하는 위치에 빨간 점을 잠깐 표시 (터치는 통과) */
    private fun showDot(x: Float, y: Float) {
        ui.post {
            try {
                val wm = getSystemService(WindowManager::class.java)
                val size = 44
                val dot = View(this).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCCFF0000.toInt()) }
                }
                val lp = WindowManager.LayoutParams(
                    size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP or Gravity.START; this.x = (x - size / 2).toInt(); this.y = (y - size / 2).toInt() }
                wm.addView(dot, lp)
                ui.postDelayed({ try { wm.removeView(dot) } catch (e: Exception) {} }, 900)
            } catch (e: Exception) {
            }
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

    /** 여러 매크로를 순서대로 실행 (잠금 해제 후 대기분) */
    fun runAll(names: List<String>) {
        if (running) return
        running = true
        RunLog.clear()
        Thread {
            try {
                for (n in names) {
                    val m = MacroStore.get(this, n) ?: continue
                    val (steps, errors) = Step.parse(m.script)
                    if (errors.isNotEmpty()) { report(n, "스크립트 오류 " + errors.first()); continue }
                    RunLog.add("실행(잠금 해제 후): $n (${steps.size}단계)")
                    execute(n, steps, m.app)
                }
            } catch (e: Exception) {
                RunLog.add("오류: ${e.message}")
            } finally {
                running = false
            }
        }.start()
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
                execute(macro.name, steps, macro.app)
            } catch (e: Exception) {
                report(macro.name, "오류: ${e.message}")
            } finally {
                running = false
            }
        }.start()
    }

    private fun execute(name: String, steps: List<Step>, app: String) {
        if (!prepareScreen(name)) { RunLog.add("화면 준비 실패(잠금)"); return }
        var last = ""
        for (attempt in 1..MAX_ATTEMPTS) {
            val (ok, msg) = attempt(steps, app)
            if (ok) {
                if (MacroStore.autoClose(this) && steps.lastOrNull() != Step.Kill) killApp()
                report(name, msg)
                return
            }
            last = msg
            if (attempt < MAX_ATTEMPTS) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                Thread.sleep(400)
            }
        }
        report(name, "실패: $last")
    }

    /** @return (성공 여부, 메시지) */
    private fun attempt(steps: List<Step>, app: String): Pair<Boolean, String> {
        val launch = findTargetApp(app) ?: return false to "'$app' 앱을 찾을 수 없음"
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        val launchEnd = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < launchEnd && rootInActiveWindow?.packageName?.toString() != targetPkg) Thread.sleep(30)
        val dm = resources.displayMetrics
        RunLog.add("화면 ${dm.widthPixels}x${dm.heightPixels} 창: " + windows.joinToString(" ; ") { w ->
            "type=${w.type} act=${w.isActive} d=${if (Build.VERSION.SDK_INT >= 34) w.displayId else -1} " + Rect().also { w.getBoundsInScreen(it) }
        })
        for ((i, step) in steps.withIndex()) {
            val n = i + 1
            RunLog.add("$n. $step")
            val err: String? = when (step) {
                is Step.Wait -> { Thread.sleep(step.ms); null }
                is Step.ClickText -> if (clickText(step.text, step.index, 40, step.touch, step.icon)) null else "${n}단계 '${step.text}' @${step.index} 못 찾음"
                is Step.Tap -> if (tap(step.x, step.y)) null else "${n}단계 탭 실패"
                is Step.Temp -> if (setTemp(step)) null else "${n}단계 온도 ${step.target}도 설정 실패"
                is Step.StopIf -> if (isVisible(step.text)) return true to "이미 완료됨 ('${step.text}' 확인)" else null
                Step.Home -> { performGlobalAction(GLOBAL_ACTION_HOME); null }
                Step.Back -> { performGlobalAction(GLOBAL_ACTION_BACK); null }
                Step.Kill -> { killApp(); null }
                is Step.TapPct -> {
                    val ref = screenRect()
                    repeatTap(ref.left + ref.width() * step.xp / 100f, ref.top + ref.height() * step.yp / 100f, step.repeat)
                    null
                }
                is Step.Near -> {
                    val anchor = waitNode(step.anchor, step.index, 5000)
                    if (anchor == null) "${n}단계 기준 '${step.anchor}' 못 찾음" else {
                        val a = Rect().also { anchor.getBoundsInScreen(it) }
                        val ref = screenRect()
                        repeatTap(a.exactCenterX() + ref.width() * step.dxp / 100f, a.exactCenterY() + ref.height() * step.dyp / 100f, step.repeat)
                        null
                    }
                }
                is Step.ClickId -> if (clickById(step.id, step.index)) null else "${n}단계 id '${step.id}' 못 찾음"
                is Step.Until -> { waitUntil(step.h, step.m, step.s); null }
            }
            if (err != null) {
                RunLog.add("실패: $err")
                RunLog.add("화면: " + screenSummary())
                return false to err
            }
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
            dispatchGesture(buildGesture(path, 300, activeDisplayId()), null, null)
            Thread.sleep(1200)
        }
        return true
    }

    private fun findTargetApp(app: String): Intent? {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(q, PackageManager.MATCH_ALL)
            .firstOrNull { it.activityInfo.packageName != packageName && it.loadLabel(pm).toString().contains(app, ignoreCase = true) }
            ?: return null
        targetLabel = match.loadLabel(pm).toString()
        targetPkg = match.activityInfo.packageName
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

    /** 최근 앱 화면에서 대상 앱 카드(이름으로 식별)를 위로 밀어 종료. 못 찾으면 아무것도 닫지 않음 */
    private fun killApp() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        val label = targetLabel.ifBlank { TARGET_APP_NAME }
        var card: AccessibilityNodeInfo? = null
        val cardEnd = System.currentTimeMillis() + 1800
        while (System.currentTimeMillis() < cardEnd) { // 최근 앱 화면에 카드가 보이는 즉시 진행
            card = findNode(rootInActiveWindow, label)
            if (card != null) { Thread.sleep(120); card = findNode(rootInActiveWindow, label) ?: card; break }
            Thread.sleep(35)
        }
        if (card == null) {
            RunLog.add("앱 종료 생략: 최근 앱에서 '$label' 카드를 못 찾음")
            RunLog.add("최근 앱 화면: " + screenSummary())
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        val dm = resources.displayMetrics
        val h = dm.heightPixels.toFloat()
        val r = Rect().also { card.getBoundsInScreen(it) }
        val x = r.exactCenterX()
        val startY = if (r.height() > h * 0.4f) r.exactCenterY() else minOf(r.bottom + h * 0.3f, h * 0.85f)
        RunLog.add("앱 종료: '$label' 카드 위로 밀기 x=${x.toInt()} y=${startY.toInt()}")
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, maxOf(startY - h * 0.6f, 5f))
        }
        dispatchGesture(buildGesture(path, 160, activeDisplayId()), null, null)
        Thread.sleep(250)
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /** 앱 창(없으면 화면) 영역: 비율 좌표의 기준 */
    private fun screenRect(): Rect {
        val r = Rect()
        rootInActiveWindow?.getBoundsInScreen(r)
        if (r.isEmpty) {
            val dm = resources.displayMetrics
            r.set(0, 0, dm.widthPixels, dm.heightPixels)
        }
        return r
    }

    private fun repeatTap(x: Float, y: Float, count: Int) {
        repeat(count) { tap(x, y, 60) }
    }

    private fun waitNode(text: String, index: Int, ms: Long): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            findNode(rootInActiveWindow, text, index)?.let { return it }
            Thread.sleep(40)
        }
        return null
    }

    private fun clickById(id: String, index: Int): Boolean {
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end) {
            val hits = mutableListOf<AccessibilityNodeInfo>()
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            rootInActiveWindow?.let { stack.add(it) }
            while (stack.isNotEmpty()) {
                val n = stack.removeFirst()
                if (n.viewIdResourceName?.contains(id) == true) hits.add(n)
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
            val node = hits.filter { it.isVisibleToUser }.ifEmpty { hits }.getOrNull(index - 1)
            if (node != null && press(node)) return true
            Thread.sleep(40)
        }
        return false
    }

    /** 오늘 h:m:s 가 될 때까지 대기 (이미 5분 넘게 지났으면 건너뜀) */
    private fun waitUntil(h: Int, m: Int, s: Int) {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m)
            set(java.util.Calendar.SECOND, s); set(java.util.Calendar.MILLISECOND, 0)
        }
        val target = cal.timeInMillis
        if (System.currentTimeMillis() - target > 5 * 60_000L) { RunLog.add("  until: 이미 지난 시각이라 건너뜀"); return }
        RunLog.add("  until: ${h}:${m}:${s} 까지 대기")
        while (true) {
            val left = target - System.currentTimeMillis()
            if (left <= 0) break
            Thread.sleep(if (left > 200) 100 else 5)
        }
    }

    private fun waitFor(text: String, index: Int, ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (findNode(rootInActiveWindow, text, index) != null) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun isVisible(text: String) = findNode(rootInActiveWindow, text) != null

    private fun clickText(text: String, index: Int = 1, retries: Int = 40, touchOnly: Boolean = false, icon: Boolean = false): Boolean {
        repeat(retries) {
            val node = findNode(rootInActiveWindow, text, index)
            if (node != null) {
                // 전환 애니메이션 중에는 좌표가 움직이므로, 짧은 간격으로 두 번 읽어 위치가 같으면 바로 누름
                val r1 = Rect().also { node.getBoundsInScreen(it) }
                Thread.sleep(35)
                val again = findNode(rootInActiveWindow, text, index)
                val r2 = again?.let { n -> Rect().also { n.getBoundsInScreen(it) } }
                if (again != null && r1 == r2 && press(again, touchOnly, icon)) return true // 확인·재클릭 없이 한 번만 누름
            }
            Thread.sleep(35)
        }
        return false
    }

    /** 지금 앱이 떠 있는 화면(display) id. 폴더블의 커버/메인 화면이 다르면 제스처를 그 화면으로 보내야 함 */
    private fun activeDisplayId(node: AccessibilityNodeInfo? = null): Int {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                return (node?.window ?: rootInActiveWindow?.window)?.displayId ?: 0
            } catch (e: Exception) {
            }
        }
        return 0
    }

    private fun buildGesture(path: Path, durationMs: Long, displayId: Int): GestureDescription {
        val b = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
        if (Build.VERSION.SDK_INT >= 32) b.setDisplayId(displayId)
        return b.build()
    }

    /**
     * 노드를 한 번 누름 (확인·재클릭 없음).
     * 웹뷰: 실제 터치(제스처)를 앱이 떠 있는 화면(display)으로 보냄. 접근성 클릭은 웹 요소에 반응이 없었음(로그 확인).
     * 그 외: 접근성 클릭 우선.
     */
    private fun press(node: AccessibilityNodeInfo, touchOnly: Boolean = false, icon: Boolean = false): Boolean {
        val r = Rect().also { node.getBoundsInScreen(it) }
        var web = false
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.className?.contains("WebView") == true) web = true
            n = n.parent
        }
        val did = activeDisplayId(node)
        var tx = r.exactCenterX()
        var ty = r.exactCenterY()
        if (icon) {
            val c = iconCenter(node, r)
            tx = c.first; ty = c.second
        }
        RunLog.add("  누름: web=$web display=$did label=$r → 터치 ${tx.toInt()},${ty.toInt()}${if (icon) " (아이콘)" else ""}")
        if (!touchOnly && !web && !icon && clickChain(node)) return true
        if (!r.isEmpty && tap(tx, ty, TOUCH_HOLD_MS, did)) return true
        return !touchOnly && clickChain(node)
    }

    /** 라벨 바로 앞(위)에 있는 형제 노드(아이콘)의 중앙. 없으면 라벨 높이의 2.7배 위(스크린샷 기준 타일 중앙) */
    private fun iconCenter(label: AccessibilityNodeInfo, r: Rect): Pair<Float, Float> {
        val p = label.parent
        if (p != null) {
            val idx = (0 until p.childCount).indexOfFirst { p.getChild(it) == label }
            for (j in idx - 1 downTo 0) {
                val sib = p.getChild(j) ?: continue
                val b = Rect().also { sib.getBoundsInScreen(it) }
                if (!b.isEmpty && b.height() > r.height()) return Pair(b.exactCenterX(), b.exactCenterY())
            }
        }
        return Pair(r.exactCenterX(), (r.exactCenterY() - r.height() * 2.7f).coerceAtLeast(1f))
    }

    private fun clickChain(node: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = node
        while (c != null && c.className?.contains("WebView") != true) {
            if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            c = c.parent
        }
        return false
    }

    /** 고정 시간(holdMs) 동안 누르고 뗌. 확인·재시도 없음 */
    private fun tap(x: Float, y: Float, holdMs: Long = TOUCH_HOLD_MS, displayId: Int = activeDisplayId()): Boolean {
        val path = Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }
        showDot(x, y)
        val done = java.util.concurrent.CountDownLatch(1)
        val cb = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { done.countDown() }
            override fun onCancelled(g: GestureDescription?) { done.countDown() }
        }
        val ok = dispatchGesture(buildGesture(path, holdMs, displayId), cb, null)
        if (ok) done.await(holdMs + 600, java.util.concurrent.TimeUnit.MILLISECONDS)
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
        var cur = waitTemp(s.label, null, 2500) ?: return false
        repeat(40) {
            RunLog.add("  현재 설정 온도 $cur")
            if (cur == s.target) return true
            if (!clickText(if (cur < s.target) s.plus else s.minus, 1, 40)) return false
            cur = waitTemp(s.label, cur, 900) ?: cur // 값이 바뀌는 즉시 다음 클릭
        }
        return false
    }

    /** 온도를 읽음. changedFrom 이 있으면 그 값에서 바뀔 때까지(최대 ms) 기다림 */
    private fun waitTemp(label: String, changedFrom: Int?, ms: Long): Int? {
        val end = System.currentTimeMillis() + ms
        var last: Int? = null
        while (System.currentTimeMillis() < end) {
            val t = readTemp(label)
            if (t != null) {
                last = t
                if (changedFrom == null || t != changedFrom) return t
            }
            Thread.sleep(35)
        }
        return last
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
        const val LAUNCH_WAIT_MS = 250L
        const val STEP_GAP_MS = 80L
        const val MAX_ATTEMPTS = 2
        const val PENDING_MAX_AGE_MS = 3 * 60 * 60 * 1000L // 예약 시각 후 3시간 안에 해제하면 실행
        const val TOUCH_HOLD_MS = 150L // 누르는 시간 (너무 길면 롱프레스로 인식)
    }
}
