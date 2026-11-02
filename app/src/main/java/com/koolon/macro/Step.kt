package com.koolon.macro

/**
 * 스크립트 문법 (한 줄에 한 단계, # 으로 시작하면 주석)
 *   click <글자> [@N]         글자가 포함된 N번째(기본 1) 요소 클릭 (못 찾으면 5초간 재시도)
 *   icon <글자> [@N]          글자 바로 위의 아이콘을 터치 (라벨이 아니라 아이콘이 눌리는 버튼용)
 *   touch <글자> [@N]         좌표 터치로만 클릭 (click 이 안 먹는 웹 요소용)
 *   tap <x> <y>               좌표 탭
 *   wait <ms>                 대기
 *   temp <목표> <+글자> <-글자> [기준글자]  기준글자(예: 설정 온도) 다음에 나오는 온도를 읽어 목표까지 +/- 클릭
 *   stopif <글자>             글자가 화면에 있으면 "이미 완료"로 종료
 *   tap <x>% <y>% [xN]        화면(앱 창) 비율 좌표를 터치. xN 이면 N번 연타 (문구 없는 버튼용)
 *   near <기준글자> [@N] <dx>% <dy>% [xN]   기준글자 중심에서 화면 비율만큼 떨어진 곳을 터치
 *   id <리소스ID> [@N]        view id(일부 일치)로 클릭
 *   until HH:mm[:ss]          그 시각이 될 때까지 대기 (선착순 정각 대기용)
 *   kill                      최근 앱 화면에서 현재 앱을 밀어서 종료 (실행 후 자동 종료가 켜져 있으면 생략 가능)
 *   home / back               홈 / 뒤로
 */
sealed class Step {
    data class Wait(val ms: Long) : Step()
    data class ClickText(val text: String, val index: Int = 1, val touch: Boolean = false, val icon: Boolean = false) : Step()
    data class Tap(val x: Float, val y: Float) : Step()
    data class Temp(val target: Int, val plus: String, val minus: String, val label: String = "") : Step()
    data class StopIf(val text: String) : Step()
    object Home : Step()
    object Back : Step()
    object Kill : Step()
    data class TapPct(val xp: Float, val yp: Float, val repeat: Int = 1) : Step()
    data class Near(val anchor: String, val index: Int, val dxp: Float, val dyp: Float, val repeat: Int = 1) : Step()
    data class ClickId(val id: String, val index: Int = 1) : Step()
    data class Until(val h: Int, val m: Int, val s: Int) : Step()

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
                    "click", "touch", "icon" -> {
                        val touch = parts[0].lowercase() == "touch"
                        val icon = parts[0].lowercase() == "icon"
                        val m = Regex("^(.*?)\\s+@(\\d+)$").find(arg)
                        if (m != null) ClickText(m.groupValues[1], m.groupValues[2].toInt().coerceAtLeast(1), touch, icon)
                        else if (arg.isNotEmpty()) ClickText(arg, 1, touch, icon) else null
                    }
                    "stopif" -> if (arg.isNotEmpty()) StopIf(arg) else null
                    "tap" -> {
                        if (arg.contains("%")) {
                            val x = a.getOrNull(0)?.removeSuffix("%")?.toFloatOrNull()
                            val y = a.getOrNull(1)?.removeSuffix("%")?.toFloatOrNull()
                            val n = a.getOrNull(2)?.removePrefix("x")?.toIntOrNull() ?: 1
                            if (x != null && y != null) TapPct(x, y, n.coerceIn(1, 200)) else null
                        } else {
                            val x = a.getOrNull(0)?.toFloatOrNull()
                            val y = a.getOrNull(1)?.toFloatOrNull()
                            if (x != null && y != null) Tap(x, y) else null
                        }
                    }
                    "near" -> {
                        val m = Regex("^(.*?)(?:\\s+@(\\d+))?\\s+(-?[0-9.]+)%\\s+(-?[0-9.]+)%(?:\\s+x(\\d+))?$").find(arg)
                        if (m != null && m.groupValues[1].isNotBlank()) {
                            val dx = m.groupValues[3].toFloatOrNull()
                            val dy = m.groupValues[4].toFloatOrNull()
                            if (dx != null && dy != null) Near(
                                m.groupValues[1].trim(),
                                m.groupValues[2].toIntOrNull() ?: 1,
                                dx, dy,
                                (m.groupValues[5].toIntOrNull() ?: 1).coerceIn(1, 200),
                            ) else null
                        } else null
                    }
                    "id" -> {
                        val m = Regex("^(\\S+)(?:\\s+@(\\d+))?$").find(arg)
                        if (m != null) ClickId(m.groupValues[1], m.groupValues[2].toIntOrNull() ?: 1) else null
                    }
                    "until" -> {
                        val t = arg.split(":").mapNotNull { it.trim().toIntOrNull() }
                        if (t.size >= 2) Until(t[0], t[1], t.getOrElse(2) { 0 }) else null
                    }
                    "temp" -> {
                        val t = a.getOrNull(0)?.toIntOrNull()
                        if (t != null && a.size >= 3) Temp(t, a[1], a[2], a.drop(3).joinToString(" ")) else null
                    }
                    "home" -> Home
                    "back" -> Back
                    "kill" -> Kill
                    else -> null
                }
                if (step == null) errors.add("${i + 1}행: $raw") else steps.add(step)
            }
            return Pair(steps, errors)
        }
    }
}
