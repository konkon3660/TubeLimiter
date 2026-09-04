package com.tubelimiter.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tubelimiter.app.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (AppSettings(context).settings.first().monitoringEnabled) {
                    UsageMonitorService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
