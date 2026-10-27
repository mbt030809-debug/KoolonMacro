package com.koolon.macro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Macro(
    val name: String,
    val script: String,
    /** 매일 실행 시각 "HH:mm", 없으면 null */
    val time: String? = null,
    /** 집 근처 도착 시 실행 */
    /** 요일 비트마스크 (월=1, 화=2, … 일=64), 기본 매일 */
    val days: Int = 127,
)

object MacroStore {
    /** 덤프로 확인한 코오롱 화면 기준 예시: 난방 → 안방 상세 설정 → 난방 모드 → 설정 온도 26 */
    const val SAMPLE2_NAME = "안방 외출"
    const val SAMPLE2_SCRIPT = "icon 난방\nwait 300\nclick 상세 설정 @2\nwait 300\nclick 외출\n"

    fun autoClose(ctx: Context) = prefs(ctx).getBoolean("autoClose", true)

    fun setAutoClose(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("autoClose", v).apply()

    const val SAMPLE_NAME = "안방 난방 26도"
    const val SAMPLE_SCRIPT = "icon 난방\nwait 300\nclick 상세 설정 @2\nwait 300\nclick 난방 @2\nwait 200\ntemp 26 + - 설정 온도\n"

    private const val DEFAULT_SCRIPT = "click 난방\nclick 실내\n# temp 26 + -\n# home\n"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("macro", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Macro> {
        val p = prefs(ctx)
        if (!p.contains("macros")) {
            save(ctx, Macro("난방", DEFAULT_SCRIPT))
        }
        val arr = JSONArray(p.getString("macros", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Macro(
                o.getString("name"),
                o.getString("script"),
                if (o.isNull("time")) null else o.getString("time"),
                o.optInt("days", 127),
            )
        }
    }

    fun get(ctx: Context, name: String) = all(ctx).firstOrNull { it.name == name }

    fun save(ctx: Context, m: Macro) {
        val p = prefs(ctx)
        val list = (if (p.contains("macros")) all(ctx) else emptyList()).filter { it.name != m.name } + m
        write(ctx, list)
    }

    fun delete(ctx: Context, name: String) = write(ctx, all(ctx).filter { it.name != name })

    private fun write(ctx: Context, list: List<Macro>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("name", it.name).put("script", it.script)
                .put("time", it.time ?: JSONObject.NULL).put("days", it.days))
        }
        prefs(ctx).edit().putString("macros", arr.toString()).apply()
    }

    // 위젯 <-> 매크로 연결
    fun bindWidget(ctx: Context, id: Int, name: String) =
        prefs(ctx).edit().putString("w$id", name).apply()

    fun widgetMacro(ctx: Context, id: Int): String? = prefs(ctx).getString("w$id", null)

    fun unbindWidget(ctx: Context, id: Int) = prefs(ctx).edit().remove("w$id").apply()
}
