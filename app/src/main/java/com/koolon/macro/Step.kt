package com.koolon.macro

/**
 * 스크립트 문법 (한 줄에 한 단계, # 으로 시작하면 주석)
 *   click <글자> [@N]         글자가 포함된 N번째(기본 1) 요소 클릭 (못 찾으면 5초간 재시도)
 *   tap <x> <y>               좌표 탭
 *   wait <ms>                 대기
 *   temp <목표> <+글자> <-글자>  현재 온도를 읽어 목표 온도까지 +/- 버튼 클릭
 *   stopif <글자>             글자가 화면에 있으면 "이미 완료"로 종료
 *   home / back               홈 / 뒤로
 */
sealed class Step {
    data class Wait(val ms: Long) : Step()
    data class ClickText(val text: String, val index: Int = 1) : Step()
    data class Tap(val x: Float, val y: Float) : Step()
    data class Temp(val target: Int, val plus: String, val minus: String) : Step()
    data class StopIf(val text: String) : Step()
    object Home : Step()
    object Back : Step()

    companion object {
        /** @return (단계 목록, 오류 줄 목록) */
        fun parse(script: String): Pair<List<Step>, List<String>> {
            val steps = mutableListOf<Step>()
            val errors = mutableListOf<String>()
            script.lines().forEachIndexed { i, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val parts = line.split(Regex("\\s+"), limit = 2)
                val arg = parts.getOrNull(1)?.trim().orEmpty()
                val a = arg.split(Regex("\\s+"))
                val step: Step? = when (parts[0].lowercase()) {
                    "wait" -> arg.toLongOrNull()?.let { Wait(it) }
                    "click" -> {
                        val m = Regex("^(.*?)\\s+@(\\d+)$").find(arg)
                        if (m != null) ClickText(m.groupValues[1], m.groupValues[2].toInt().coerceAtLeast(1))
                        else if (arg.isNotEmpty()) ClickText(arg) else null
                    }
                    "stopif" -> if (arg.isNotEmpty()) StopIf(arg) else null
                    "tap" -> {
                        val x = a.getOrNull(0)?.toFloatOrNull()
                        val y = a.getOrNull(1)?.toFloatOrNull()
                        if (x != null && y != null) Tap(x, y) else null
                    }
                    "temp" -> {
                        val t = a.getOrNull(0)?.toIntOrNull()
                        if (t != null && a.size >= 3) Temp(t, a[1], a[2]) else null
                    }
                    "home" -> Home
                    "back" -> Back
                    else -> null
                }
                if (step == null) errors.add("${i + 1}행: $raw") else steps.add(step)
            }
            return Pair(steps, errors)
        }
    }
}
