package com.koolon.macro

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

class RunActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.data?.lastPathSegment ?: intent.getStringExtra("name")
        val s = MacroService.instance
        when {
            name == null -> Toast.makeText(this, "매크로 이름 없음", Toast.LENGTH_LONG).show()
            s == null -> Toast.makeText(this, "접근성 서비스를 먼저 켜세요", Toast.LENGTH_LONG).show()
            else -> s.runMacro(name)
        }
        finish()
    }
}
