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
import androidx.core.content.edit
import com.android.volley.toolbox.JsonArrayRequest
import com.android.volley.toolbox.Volley
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
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
        const val ERROR_NOTIFICATION_ID = NOTIFICATION_ID + 1
        const val LOCATION_MIN_TIME_MS = 1000L
        const val LOCATION_MIN_DISTANCE = 0f
        const val MIN_ACCURACY_DIFFERENCE = 5f
        const val LOCATION_TIMEOUT_MS = 10000L
        const val MAX_FAILED_REQUESTS = 1000
        const val PREFS_NAME = "telemetry_prefs"
        const val KEY_FAILED_REQUESTS = "failed_requests"
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
                val name =
                    preferencesManager!!.getPreferenceFlow(PreferencesManager.Keys.NAME, "").first()
                val endpoint =
                    preferencesManager!!.getPreferenceFlow(PreferencesManager.Keys.ENDPOINT, "")
                        .first()
                val interval =
                    preferencesManager!!.getPreferenceFlow(PreferencesManager.Keys.INTERVAL, 60)
                        .first()
                val cfAccessClientSecret = preferencesManager!!.getPreferenceFlow(
                    PreferencesManager.Keys.CF_ACCESS_CLIENT_SECRET,
                    ""
                ).first()
                val cfAccessClientId = preferencesManager!!.getPreferenceFlow(
                    PreferencesManager.Keys.CF_ACCESS_CLIENT_ID,
                    ""
                ).first()

                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val failedJson = prefs.getString(KEY_FAILED_REQUESTS, "[]")!!
                val failedArray = JSONArray(failedJson)
                prefs.edit { remove(KEY_FAILED_REQUESTS) }

                val startFixTime = System.currentTimeMillis()
                val freshLocation = getFreshLocation()
                val calculatedFixTime =
                    if (freshLocation?.provider == LocationManager.GPS_PROVIDER) System.currentTimeMillis() - startFixTime else null

                val jsonRequest = JSONArray().apply {
                    for (i in 0 until failedArray.length()) {
                        put(failedArray.getJSONObject(i))
                    }
                    put(JSONObject().apply {
                        put("date", getCurrentTimeAsISO8601())
                        put("location", JSONObject().apply {
                            put("latitude", freshLocation?.latitude ?: JSONObject.NULL)
                            put("longitude", freshLocation?.longitude ?: JSONObject.NULL)
                            put("speed", freshLocation?.speed ?: JSONObject.NULL)
                            put("accuracy", freshLocation?.accuracy ?: JSONObject.NULL)
                            put("altitude", freshLocation?.altitude ?: JSONObject.NULL)
                            put("provider", freshLocation?.provider ?: JSONObject.NULL)
                            put(
                                "timeToFix",
                                if (calculatedFixTime == null) JSONObject.NULL else calculatedFixTime / 1000.0
                            )
                            put("bearing", freshLocation?.bearing ?: JSONObject.NULL)
                        })
                        put("battery", JSONObject().apply {
                            put("voltage", getBatteryVoltage() ?: JSONObject.NULL)
                            put("current", getBatteryCurrent() ?: JSONObject.NULL)
                            put(
                                "temperature",
                                getBatteryTemperature().takeIf { it >= 0f } ?: JSONObject.NULL
                            )
                            put(
                                "level",
                                getBatteryPercentage().takeIf { it >= 0 } ?: JSONObject.NULL
                            )
                            put("charging", isCharging() ?: JSONObject.NULL)
                        })
                        put("sensors", JSONObject().apply {
                            put("barometer", getPressure() ?: JSONObject.NULL)
                            put("light", getLight() ?: JSONObject.NULL)
                            put("proximity", getProximity() ?: JSONObject.NULL)
                        })
                        put("cellular", JSONObject().apply {
                            put("networkType", getNetworkType() ?: JSONObject.NULL)
                            put("signalStrength", getSignalStrength() ?: JSONObject.NULL)
                            put("signalPower", getSignalPower() ?: JSONObject.NULL)
                            put("cellTower", getCellTower() ?: JSONObject.NULL)
                        })
                    })
                }

                Log.d("ForegroundService", "JSON Request: $jsonRequest")

                val request = object : JsonArrayRequest(
                    Method.POST,
                    endpoint,
                    jsonRequest,
                    { response ->
                        if (failedArray.length() > 0) {
                            Log.d(
                                "ForegroundService",
                                "Successfully sent ${failedArray.length()} failed requests"
                            )
                        }
                    },
                    { error ->
                        Log.e(
                            "ForegroundService",
                            "Error sending request: $error ${error.networkResponse?.statusCode}"
                        )
                        error.networkResponse?.data?.let {
                            Log.e("ForegroundService", "Response body: ${String(it)}")
                        }

                        // persist up to MAX_FAILED_REQUESTS failed entries
                        val toStore = if (jsonRequest.length() > MAX_FAILED_REQUESTS) {
                            JSONArray().apply {
                                for (i in jsonRequest.length() - MAX_FAILED_REQUESTS until jsonRequest.length()) {
                                    put(jsonRequest.get(i))
                                }
                            }
                        } else jsonRequest
                        Log.e("ForegroundService", "Saving ${toStore.length()} failed requests")
                        prefs.edit { putString(KEY_FAILED_REQUESTS, toStore.toString()) }

                        showErrorNotification("Error code: ${error.networkResponse?.statusCode ?: "Unknown"}")
                    }
                ) {
                    override fun parseNetworkResponse(response: com.android.volley.NetworkResponse): com.android.volley.Response<JSONArray> {
                        return try {
                            val responseString = String(response.data)
                            val jsonArray = JSONArray(responseString)
                            com.android.volley.Response.success(jsonArray, null)
                        } catch (e: org.json.JSONException) {
                            com.android.volley.Response.success(JSONArray(), null)
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

        return START_STICKY
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

    private fun showErrorNotification(message: String) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val errorNotification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("HTTP Request Failed")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(ERROR_NOTIFICATION_ID, errorNotification)
    }

    private suspend fun requestLocationByProvider(
        locationManager: LocationManager,
        provider: String
    ): Location? {
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

                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
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
        Log.d("ForegroundService", "Trying network")
        val networkLocation =
            requestLocationByProvider(locationManager, LocationManager.NETWORK_PROVIDER)
        if (networkLocation != null) return networkLocation
        // else if <= android 12 try fused
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.d("ForegroundService", "Trying Fused")
            val fusedLocation =
                requestLocationByProvider(locationManager, LocationManager.FUSED_PROVIDER)
            if (fusedLocation != null) return fusedLocation
        }
        // else try passive
        Log.d("ForegroundService", "Trying passive")
        return requestLocationByProvider(locationManager, LocationManager.PASSIVE_PROVIDER)
    }

    private fun getBatteryPercentage(): Int {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }

    private fun getBatteryVoltage(): Float? {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val mv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        return if (mv >= 0) mv / 1000f else null
    }

    private fun getBatteryCurrent(): Float? {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val currentNow = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return if (currentNow != Int.MIN_VALUE) currentNow / 1000f else null
    }

    private fun isCharging(): Boolean? {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL -> true

            BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            BatteryManager.BATTERY_STATUS_UNKNOWN -> false

            else -> null
        }
    }

    private fun getBatteryTemperature(): Float {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return if (temp != -1) temp / 10.0f else -1f
    }

    private suspend fun getLight(): Float? =
        readSingleSensor(Sensor.TYPE_LIGHT)

    private suspend fun getProximity(): Float? =
        readSingleSensor(Sensor.TYPE_PROXIMITY)

    private suspend fun readSingleSensor(type: Int): Float? =
        suspendCancellableCoroutine { cont ->
            val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val sensor = sm?.getDefaultSensor(type) ?: run {
                cont.resume(null); return@suspendCancellableCoroutine
            }
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    cont.resume(e.values[0])
                    sm.unregisterListener(this)
                }

                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            cont.invokeOnCancellation { sm.unregisterListener(listener) }
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
            sensorManager.registerListener(
                listener,
                pressureSensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            cont.invokeOnCancellation {
                sensorManager.unregisterListener(listener)
            }
        }
    }

    private fun getNetworkType(): String? {
        return null
    }

    private fun getSignalStrength(): Int? {
        return null
    }

    private fun getSignalPower(): Int? {
        return null
    }

    private fun getCellTower(): String? {
        return null
    }

    private fun getCurrentTimeAsISO8601(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.getDefault())
        dateFormat.timeZone = TimeZone.getDefault()
        val formattedDate = dateFormat.format(Date())
        return formattedDate.substring(0, formattedDate.length - 2) + ":" + formattedDate.substring(
            formattedDate.length - 2
        )
    }
}
