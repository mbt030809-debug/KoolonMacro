package com.koolon.macro

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import android.widget.Toast

class MacroWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) update(context, manager, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { MacroStore.unbindWidget(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_RUN) return
        val name = intent.getStringExtra("name") ?: return
        val service = MacroService.instance
        if (service == null) {
            Toast.makeText(context, "접근성 서비스를 먼저 켜세요", Toast.LENGTH_LONG).show()
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            service.runMacro(name)
        }
    }

    companion object {
        const val ACTION_RUN = "com.koolon.macro.RUN"

        fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val name = MacroStore.widgetMacro(context, id)
            val views = RemoteViews(context.packageName, R.layout.widget)
            views.setTextViewText(R.id.widget_label, "🔥 " + (name ?: "매크로 선택"))
            val intent = Intent(context, MacroWidget::class.java)
                .setAction(ACTION_RUN)
                .setData(Uri.parse("macro://widget/$id"))
                .putExtra("name", name)
            val pi = PendingIntent.getBroadcast(
                context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            manager.updateAppWidget(id, views)
        }
    }
}
