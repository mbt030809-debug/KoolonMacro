package com.koolon.macro

import android.app.Activity
import android.app.TimePickerDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class EditActivity : Activity() {

    private lateinit var nameEt: EditText
    private lateinit var scriptEt: EditText
    private val dayCbs = mutableListOf<CheckBox>()
    private lateinit var timeBtn: Button
    private lateinit var recBtn: Button
    private var time: String? = null
    private var editing: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editing = intent.getStringExtra("name")
        val m = editing?.let { MacroStore.get(this, it) }
        time = m?.time

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(TextView(this).apply {
            text = "명령어 (한 줄에 하나, # 은 주석)\n" +
                "click 글자 · tap x y · wait ms\n" +
                "temp 26 + -  (목표온도, +버튼글자, -버튼글자)\n" +
                "stopif 글자 (있으면 이미 완료로 종료) · home · back"
        })
        nameEt = EditText(this).apply {
            hint = "매크로 이름"
            setText(m?.name ?: "")
            isEnabled = editing == null
        }
        root.addView(nameEt)
        scriptEt = EditText(this).apply {
            hint = "click 난방\nclick 실내"
            setText(m?.script ?: "")
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP
            minLines = 8
        }
        root.addView(scriptEt)
        timeBtn = Button(this).apply {
            setOnClickListener {
                TimePickerDialog(this@EditActivity, { _, h, mi ->
                    time = "%02d:%02d".format(h, mi); refreshTime()
                }, 8, 0, true).show()
            }
            setOnLongClickListener { time = null; refreshTime(); true }
        }
        root.addView(timeBtn)
        val dayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val days = m?.days ?: 127
        listOf("월", "화", "수", "목", "금", "토", "일").forEachIndexed { i, label ->
            val cb = CheckBox(this).apply { text = label; isChecked = days and (1 shl i) != 0 }
            dayCbs.add(cb)
            dayRow.addView(cb)
        }
        root.addView(dayRow)
        recBtn = Button(this).apply { setOnClickListener { toggleRecord() } }
        root.addView(recBtn)
        root.addView(btn("테스트 실행") {
            val s = MacroService.instance
            if (s == null) toast("접근성 서비스가 꺼져 있음") else s.runMacro(current(nameEt.text.toString().ifBlank { "테스트" }))
        })
        root.addView(btn("저장") { save() })
        if (editing != null) root.addView(btn("삭제") { delete() })
        refreshTime()
        refreshRec()
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun refreshTime() {
        timeBtn.text = "⏰ 실행 시각(선택한 요일): " + (time ?: "없음") + " (길게 눌러 해제)"
    }

    private fun refreshRec() {
        recBtn.text = if (MacroService.instance?.isRecording == true) "⏹ 녹화 종료 (스크립트에 추가)" else "⏺ 녹화 시작"
    }

    private fun toggleRecord() {
        val s = MacroService.instance
        if (s == null) { toast("접근성 서비스가 꺼져 있음"); return }
        if (s.isRecording) {
            val lines = s.stopRecording()
            val cur = scriptEt.text.toString()
            scriptEt.setText(cur + (if (cur.isNotEmpty() && !cur.endsWith("\n")) "\n" else "") + lines)
            toast("녹화 내용 추가됨")
        } else {
            s.startRecording()
            toast("코오롱 앱에서 동선을 직접 누른 뒤, 이 화면으로 돌아와 녹화 종료")
        }
        refreshRec()
    }

    private fun current(name: String) = Macro(name, scriptEt.text.toString(), time, dayCbs.withIndex().sumOf { (i, c) -> if (c.isChecked) 1 shl i else 0 })

    private fun save() {
        val name = nameEt.text.toString().trim()
        if (name.isEmpty()) { toast("이름을 입력하세요"); return }
        if (editing == null && MacroStore.get(this, name) != null) { toast("같은 이름이 이미 있음"); return }
        val m = current(name)
        val errors = Step.parse(m.script).second
        if (errors.isNotEmpty()) { toast("문법 오류: " + errors.first()); return }
        MacroStore.save(this, m)
        Scheduler.schedule(this, m)
        Shortcuts.update(this)
        finish()
    }

    private fun delete() {
        val name = editing ?: return
        MacroStore.delete(this, name)
        Scheduler.cancel(this, name)
        Shortcuts.update(this)
        finish()
    }
}
