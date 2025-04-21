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
import com.android.volley.RequestQueue
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


class ForegroundService : Service() {

    private val channelId = "ForegroundServiceChannel"
    private var serviceJob: Job? = null

    companion object {
        var preferencesManager: PreferencesManager? = null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentText("Tracking")
            .build()

        startForeground(1, notification)

        val queue = Volley.newRequestQueue(this)

        serviceJob = CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                val endpoint = preferencesManager!!.getPreferenceFlow(PreferencesManager.Keys.ENDPOINT, "").first()
                val interval = preferencesManager!!.getPreferenceFlow(PreferencesManager.Keys.INTERVAL, 60).first()
                val cfAccessClientSecret = preferencesManager!!.getPreferenceFlow(PreferencesManager.Keys.CF_ACCESS_CLIENT_SECRET, "").first()
                val cfAccessClientId = preferencesManager!!.getPreferenceFlow(PreferencesManager.Keys.CF_ACCESS_CLIENT_ID, "").first()
                Log.d("ForegroundService", "Endpoint: $endpoint, Interval: $interval")
                delay(1000L)

                try {
                    val jsonRequest = JSONObject().apply {
                        put("key", "value")
                    }

                    suspendCancellableCoroutine<JSONObject> { continuation ->
                        val request = object : JsonObjectRequest(
                            Method.POST,
                            endpoint,
                            jsonRequest,
                            { response ->
                                continuation.resume(response)
                            },
                            { error ->
                                continuation.resumeWithException(error)
                            }
                        ) {
                            override fun getHeaders(): Map<String, String> {
                                val headers = HashMap<String, String>()
                                headers["CF-Access-Client-Id"] = cfAccessClientId
                                headers["CF-Access-Client-Secret"] = cfAccessClientSecret
                                headers["Content-Type"] = "application/json"
                                return headers
                            }
                        }

                        queue.add(request)

                        continuation.invokeOnCancellation {
                            request.cancel()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ForegroundService", "Error sending request: ${e.message}")
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
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
}