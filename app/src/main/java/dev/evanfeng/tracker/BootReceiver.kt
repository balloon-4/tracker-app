package dev.evanfeng.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val preferencesManager = PreferencesManager(context.dataStore)
            val wasRunning = runBlocking {
                preferencesManager.getPreferenceFlow(
                    PreferencesManager.Keys.IS_SERVICE_RUNNING, false
                ).first()
            }
            if (wasRunning) {
                val serviceIntent = Intent(context, ForegroundService::class.java)
                ForegroundService.preferencesManager = preferencesManager
                context.startCompatibleForegroundService(serviceIntent)
            }
        }
    }
}
