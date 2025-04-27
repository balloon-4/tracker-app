package dev.evanfeng.tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.math.abs

@Suppress("OVERRIDE_DEPRECATION")
class ForegroundService : Service() {

    private val channelId = "ForegroundServiceChannel"
    private var serviceJob: Job? = null

    companion object {
        var preferencesManager: PreferencesManager? = null
        const val NOTIFICATION_ID = 1
        const val LOCATION_MIN_TIME_MS = 1000L
        const val LOCATION_MIN_DISTANCE = 0f
        const val MIN_ACCURACY_DIFFERENCE = 5f
        const val LOCATION_TIMEOUT_MS = 10000L
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        val queue = Volley.newRequestQueue(this)

        serviceJob = CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                val name = preferencesManager!!.getPreferenceFlow(PreferencesManager.Keys.NAME, "").first()
                val endpoint = preferencesManager!!.getPreferenceFlow(PreferencesManager.Keys.ENDPOINT, "").first()
                val interval = preferencesManager!!.getPreferenceFlow(PreferencesManager.Keys.INTERVAL, 60).first()
                val cfAccessClientSecret = preferencesManager!!.getPreferenceFlow(PreferencesManager.Keys.CF_ACCESS_CLIENT_SECRET, "").first()
                val cfAccessClientId = preferencesManager!!.getPreferenceFlow(PreferencesManager.Keys.CF_ACCESS_CLIENT_ID, "").first()

                val startFixTime = System.currentTimeMillis()
                val freshLocation = getFreshLocation()
                val calculatedFixTime = if (freshLocation?.provider == LocationManager.GPS_PROVIDER) System.currentTimeMillis() - startFixTime else null

                val jsonRequest = JSONObject().apply {
                    put("accuracy", freshLocation?.accuracy ?: JSONObject.NULL)
                    put("altitude", freshLocation?.altitude ?: JSONObject.NULL)
                    put("batteryPercent", getBatteryPercentage())
                    put("cellStrength", JSONObject.NULL)
                    put("date", getCurrentTimeAsISO8601())
                    put("latitude", freshLocation?.latitude ?: JSONObject.NULL)
                    put("longitude", freshLocation?.longitude ?: JSONObject.NULL)
                    put("pressure", getPressure() ?: JSONObject.NULL)
                    put("provider", freshLocation?.provider ?: JSONObject.NULL)
                    put("session", name)
                    put("speed", freshLocation?.speed ?: JSONObject.NULL)
                    put("temperature", getBatteryTemperature())
                    put("timeToFix", if (calculatedFixTime == null) JSONObject.NULL else calculatedFixTime / 1000.0)
                }

                Log.d("ForegroundService", "JSON Request: $jsonRequest")

                val request = object : JsonObjectRequest(
                    Method.POST,
                    endpoint,
                    jsonRequest,
                    { response ->
                    },
                    { error ->
                        Log.e("ForegroundService", "Error sending request: ${error}")
                        Log.e("ForegroundService", "Error sending request: ${error.networkResponse?.statusCode}")
                        error.networkResponse?.data?.let {
                            Log.e("ForegroundService", "Response body: ${String(it)}")
                        }
                    }
                ) {
                    override fun parseNetworkResponse(response: com.android.volley.NetworkResponse): com.android.volley.Response<JSONObject> {
                        return if (response.statusCode == 204) {
                            com.android.volley.Response.success(JSONObject(), null)
                        } else {
                            super.parseNetworkResponse(response)
                        }
                    }

                    override fun getHeaders(): Map<String, String> {
                        val headers = HashMap<String, String>()
                        headers["CF-Access-Client-Id"] = cfAccessClientId
                        headers["CF-Access-Client-Secret"] = cfAccessClientSecret
                        headers["Content-Type"] = "application/json"
                        return headers
                    }
                }

                queue.add(request)

                delay(interval.toLong() * 1000L)
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

    private suspend fun requestLocationByProvider(locationManager: LocationManager, provider: String): Location? {
        return withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            suspendCancellableCoroutine<Location?> { cont ->
                var lastAccuracy: Float? = null
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (lastAccuracy == null) {
                            lastAccuracy = location.accuracy
                        } else if (abs(location.accuracy - lastAccuracy!!) < MIN_ACCURACY_DIFFERENCE) {
                            locationManager.removeUpdates(this)
                            cont.resume(location)
                        } else {
                            lastAccuracy = location.accuracy
                        }
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
                    override fun onProviderEnabled(provider: String) { }
                    override fun onProviderDisabled(provider: String) { }
                }
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        LOCATION_MIN_TIME_MS,
                        LOCATION_MIN_DISTANCE,
                        listener,
                        Looper.getMainLooper()
                    )
                } catch (e: SecurityException) {
                    cont.resume(null)
                }
                cont.invokeOnCancellation {
                    locationManager.removeUpdates(listener)
                }
            }
        }
    }

    private suspend fun getFreshLocation(): Location? {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // try gps
        val gpsLocation = requestLocationByProvider(locationManager, LocationManager.GPS_PROVIDER)
        if (gpsLocation != null) return gpsLocation
        // else try network
        val networkLocation = requestLocationByProvider(locationManager, LocationManager.NETWORK_PROVIDER)
        if (networkLocation != null) return networkLocation
        // else if <= android 12 try fused
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val fusedLocation = requestLocationByProvider(locationManager, LocationManager.FUSED_PROVIDER);
            if (fusedLocation != null) return fusedLocation
        }
        // else try passive
        return requestLocationByProvider(locationManager, LocationManager.PASSIVE_PROVIDER)
    }

    private fun getBatteryPercentage(): Int {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }

    private fun getBatteryTemperature(): Float {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return if (temp != -1) temp / 10.0f else -1f
    }

    private fun getCurrentTimeAsISO8601(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.getDefault())
        dateFormat.timeZone = TimeZone.getDefault()
        val formattedDate = dateFormat.format(Date())
        return formattedDate.substring(0, formattedDate.length - 2) + ":" + formattedDate.substring(formattedDate.length - 2)
    }

    private suspend fun getPressure(): Float? {
        return suspendCancellableCoroutine { cont ->
            val sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val pressureSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
            if (pressureSensor == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    cont.resume(event.values[0])
                    sensorManager.unregisterListener(this)
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(listener, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL)
            cont.invokeOnCancellation {
                sensorManager.unregisterListener(listener)
            }
        }
    }
}
