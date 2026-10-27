package com.koolon.macro

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 마지막 실행 기록 (앱 첫 화면에서 복사 가능) */
object RunLog {
    private val sb = StringBuilder()

    @Synchronized fun clear() = sb.setLength(0)

    @Synchronized fun add(s: String) {
        sb.append(SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())).append(' ').append(s).append('\n')
        if (sb.length > 20000) sb.delete(0, sb.length - 20000)
    }

    @Synchronized fun text(): String = sb.toString()
}
