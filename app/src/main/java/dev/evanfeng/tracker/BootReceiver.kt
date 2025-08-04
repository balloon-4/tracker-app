package dev.evanfeng.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val startServiceActions = mutableSetOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            startServiceActions.add(Intent.ACTION_LOCKED_BOOT_COMPLETED)
            startServiceActions.add(Intent.ACTION_USER_UNLOCKED)
        }

        if (intent?.action in startServiceActions) {
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
