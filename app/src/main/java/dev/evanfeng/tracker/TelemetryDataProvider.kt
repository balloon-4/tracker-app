package dev.evanfeng.tracker

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
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.math.abs

class TelemetryDataProvider(private val context: Context) {
    companion object {
        const val LOCATION_MIN_TIME_MS = 1000L
        const val LOCATION_MIN_DISTANCE = 0f
        const val MIN_ACCURACY_DIFFERENCE = 5f
        const val LOCATION_TIMEOUT_MS = 10000L
    }

    suspend fun getLocation(): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsLocation = requestLocationByProvider(locationManager, LocationManager.GPS_PROVIDER)
        if (gpsLocation != null) return gpsLocation
        val networkLocation = requestLocationByProvider(locationManager, LocationManager.NETWORK_PROVIDER)
        if (networkLocation != null) return networkLocation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val fusedLocation = requestLocationByProvider(locationManager, LocationManager.FUSED_PROVIDER)
            if (fusedLocation != null) return fusedLocation
        }
        return requestLocationByProvider(locationManager, LocationManager.PASSIVE_PROVIDER)
    }

    private suspend fun requestLocationByProvider(locationManager: LocationManager, provider: String): Location? {
        return withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
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
                } catch (_: SecurityException) {
                    cont.resume(null)
                }
                cont.invokeOnCancellation { locationManager.removeUpdates(listener) }
            }
        }
    }

    fun getBatteryPercentage(): Int {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }

    fun getBatteryVoltage(): Float? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val mv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        return if (mv >= 0) mv / 1000f else null
    }

    fun getBatteryCurrent(): Float? {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val currentNow = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return if (currentNow != Int.MIN_VALUE) currentNow / 1000f else null
    }

    fun isCharging(): Boolean? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
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

    fun getBatteryTemperature(): Float {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return if (temp != -1) temp / 10.0f else -1f
    }

    suspend fun getLight(): Float? = readSingleSensor(Sensor.TYPE_LIGHT)
    suspend fun getProximity(): Float? = readSingleSensor(Sensor.TYPE_PROXIMITY)
    suspend fun getPressure(): Float? = readSingleSensor(Sensor.TYPE_PRESSURE)

    private suspend fun readSingleSensor(type: Int): Float? = suspendCancellableCoroutine { cont ->
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(type) ?: run { cont.resume(null); return@suspendCancellableCoroutine }
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

    fun getNetworkType(): String? = null
    fun getSignalStrength(): Int? = null
    fun getSignalPower(): Int? = null
    fun getCellTower(): String? = null

    fun getCurrentTimeAsISO8601(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.getDefault())
        dateFormat.timeZone = TimeZone.getDefault()
        val formattedDate = dateFormat.format(Date())
        return formattedDate.substring(0, formattedDate.length - 2) + ":" + formattedDate.substring(formattedDate.length - 2)
    }

    suspend fun buildTelemetryJson(
        failedArray: org.json.JSONArray,
        freshLocation: Location?,
        calculatedFixTime: Long?
    ): org.json.JSONArray {
        return org.json.JSONArray().apply {
            for (i in 0 until failedArray.length()) {
                put(failedArray.getJSONObject(i))
            }
            put(JSONObject().apply {
                put("date", getCurrentTimeAsISO8601())
                put("location", buildLocationJson(freshLocation, calculatedFixTime))
                put("battery", buildBatteryJson())
                put("sensors", buildSensorsJson())
                put("cellular", buildCellularJson())
            })
        }
    }

    private fun buildLocationJson(location: Location?, calculatedFixTime: Long?): JSONObject {
        return JSONObject().apply {
            put("latitude", location?.latitude ?: JSONObject.NULL)
            put("longitude", location?.longitude ?: JSONObject.NULL)
            put("speed", location?.speed ?: JSONObject.NULL)
            put("accuracy", location?.accuracy ?: JSONObject.NULL)
            put("altitude", location?.altitude ?: JSONObject.NULL)
            put("provider", location?.provider ?: JSONObject.NULL)
            put("timeToFix", if (calculatedFixTime == null) JSONObject.NULL else calculatedFixTime / 1000.0)
            put("bearing", location?.bearing ?: JSONObject.NULL)
        }
    }

    private fun buildBatteryJson(): JSONObject {
        return JSONObject().apply {
            put("voltage", getBatteryVoltage() ?: JSONObject.NULL)
            put("current", getBatteryCurrent() ?: JSONObject.NULL)
            put("temperature", getBatteryTemperature().takeIf { it >= 0f } ?: JSONObject.NULL)
            put("level", getBatteryPercentage().takeIf { it >= 0 } ?: JSONObject.NULL)
            put("charging", isCharging() ?: JSONObject.NULL)
        }
    }

    private suspend fun buildSensorsJson(): JSONObject {
        return JSONObject().apply {
            put("barometer", getPressure() ?: JSONObject.NULL)
            put("light", getLight() ?: JSONObject.NULL)
            put("proximity", getProximity() ?: JSONObject.NULL)
        }
    }

    private fun buildCellularJson(): JSONObject {
        return JSONObject().apply {
            put("networkType", getNetworkType() ?: JSONObject.NULL)
            put("signalStrength", getSignalStrength() ?: JSONObject.NULL)
            put("signalPower", getSignalPower() ?: JSONObject.NULL)
            put("cellTower", getCellTower() ?: JSONObject.NULL)
        }
    }
}

