package dev.evanfeng.tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import io.sentry.Sentry

@Suppress("OVERRIDE_DEPRECATION")
class ForegroundService : Service() {

    private val channelId = "ForegroundServiceChannel"
    private var serviceJob: Job? = null

    companion object {
        const val NOTIFICATION_ID = 1
        const val ERROR_NOTIFICATION_ID = NOTIFICATION_ID + 1
        const val MAX_FAILED_REQUESTS = 1000
        const val PREFS_NAME = "telemetry_prefs"
        const val KEY_FAILED_REQUESTS = "failed_requests"
    }

    private var telemetrySender: TelemetrySender? = null
    private var telemetryDataProvider: TelemetryDataProvider? = null

    private var preferencesManager: PreferencesManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        preferencesManager = preferencesManager ?: PreferencesManager(this.dataStore)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        telemetrySender = HttpTelemetrySender(this)
        telemetryDataProvider = TelemetryDataProvider(this)
        serviceJob = CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                val prefsData = loadPreferences()
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val failedArray = getFailedRequests(prefs)
                prefs.edit { remove(KEY_FAILED_REQUESTS) }
                val startFixTime = System.currentTimeMillis()
                val freshLocation = telemetryDataProvider?.getLocation()
                val calculatedFixTime =
                    if (freshLocation?.provider == "gps") System.currentTimeMillis() - startFixTime else null
                val jsonRequest = telemetryDataProvider?.buildTelemetryJson(failedArray, freshLocation, calculatedFixTime)

                Log.d("ForegroundService", "JSON Request: $jsonRequest")

                telemetrySender?.sendTelemetry(
                    prefsData.endpoint,
                    jsonRequest ?: JSONArray(),
                    prefsData.cfAccessClientId,
                    prefsData.cfAccessClientSecret,
                    failedArray,
                    prefs,
                    onError = ::handleError,
                    onSuccess = {}
                )

                delay(prefsData.interval.toLong() * 1000L)
            }
        }

        return START_STICKY
    }

    private data class PrefsData(
        val name: String,
        val endpoint: String,
        val interval: Int,
        val cfAccessClientId: String,
        val cfAccessClientSecret: String
    )

    private suspend fun loadPreferences(): PrefsData {
        val pm = preferencesManager!!
        return PrefsData(
            name = pm.getPreferenceFlow(PreferencesManager.Keys.NAME, "").first(),
            endpoint = pm.getPreferenceFlow(PreferencesManager.Keys.ENDPOINT, "").first(),
            interval = pm.getPreferenceFlow(PreferencesManager.Keys.INTERVAL, 60).first(),
            cfAccessClientId = pm.getPreferenceFlow(PreferencesManager.Keys.CF_ACCESS_CLIENT_ID, "").first(),
            cfAccessClientSecret = pm.getPreferenceFlow(PreferencesManager.Keys.CF_ACCESS_CLIENT_SECRET, "").first()
        )
    }

    private fun getFailedRequests(prefs: android.content.SharedPreferences): JSONArray {
        val failedJson = prefs.getString(KEY_FAILED_REQUESTS, "[]")!!
        return JSONArray(failedJson)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                channelId,
                "Foreground Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun showErrorNotification(message: String) {
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val errorNotification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("HTTP Request Failed")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(ERROR_NOTIFICATION_ID, errorNotification)
    }

    private fun handleError(error: Exception, msg: String) {
        showErrorNotification(msg)
        Sentry.captureException(error)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
