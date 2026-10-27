package com.koolon.macro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RecordStopReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        MacroService.instance?.finishQuickRecord()
    }
}
