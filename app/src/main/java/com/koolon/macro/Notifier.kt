package com.koolon.macro

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object Notifier {
    private const val REC_ID = 9001

    fun recording(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "매크로 결과", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = android.app.PendingIntent.getBroadcast(
            ctx, 0, android.content.Intent(ctx, RecordStopReceiver::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            REC_ID,
            Notification.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("매크로 녹화 중")
                .setContentText("동작을 마치면 아래 버튼을 누르세요")
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "녹화 종료·저장", pi).build())
                .build(),
        )
    }

    fun cancelRecording(ctx: Context) =
        ctx.getSystemService(NotificationManager::class.java).cancel(REC_ID)

    private const val CH = "result"

    fun post(ctx: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "매크로 결과", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(
            title.hashCode(),
            Notification.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build(),
        )
    }
}
