package com.weitiao.tuning.tuning

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.weitiao.tuning.core.TuningPrefs

class PassThroughReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || Intent.ACTION_BOOT_COMPLETED != intent.action) {
            return
        }
        if (TuningPrefs.load(context).passThrough) {
            val serviceIntent = Intent(context, PassThroughService::class.java)
                .setAction(PassThroughService.ACTION_REFRESH)
            runCatching {
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
