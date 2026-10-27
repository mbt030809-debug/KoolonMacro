package com.koolon.macro

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri

/** 런처 길게 누르기 / 삼성 루틴 / Bixby 에서 쓸 수 있는 매크로별 바로가기 */
object Shortcuts {
    fun update(ctx: Context) {
        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
        val list = MacroStore.all(ctx).take(sm.maxShortcutCountPerActivity).map {
            ShortcutInfo.Builder(ctx, "m_" + it.name.hashCode())
                .setShortLabel(it.name)
                .setIcon(Icon.createWithResource(ctx, android.R.drawable.sym_def_app_icon))
                .setIntent(
                    Intent(Intent.ACTION_VIEW, Uri.parse("koolonmacro://run/" + Uri.encode(it.name)))
                        .setClass(ctx, RunActivity::class.java),
                )
                .build()
        }
        try {
            sm.dynamicShortcuts = list
        } catch (e: Exception) {
        }
    }
}
