package com.koolon.macro

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.util.Calendar

object Scheduler {
    private fun pi(ctx: Context, name: String): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .setAction("com.koolon.macro.ALARM")
            .setData(Uri.parse("macro://alarm/" + Uri.encode(name)))
            .putExtra("name", name)
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun cancel(ctx: Context, name: String) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, name))
    }

    fun schedule(ctx: Context, m: Macro?) {
        if (m == null) return
        cancel(ctx, m.name)
        val hm = m.time?.split(":") ?: return
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hm[0].toInt())
            set(Calendar.MINUTE, hm[1].toInt())
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi(ctx, m.name))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi(ctx, m.name))
    }

    fun rescheduleAll(ctx: Context) = MacroStore.all(ctx).forEach { schedule(ctx, it) }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val name = intent.getStringExtra("name") ?: return
        Scheduler.schedule(ctx, MacroStore.get(ctx, name)) // 다음 날 예약
        val s = MacroService.instance
        if (s == null) Notifier.post(ctx, name, "접근성 서비스가 꺼져 있어 예약 실행 실패")
        else s.runMacro(name)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.rescheduleAll(ctx)
    }
}
