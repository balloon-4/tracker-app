package dev.evanfeng.tracker

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.android.volley.toolbox.JsonArrayRequest
import com.android.volley.toolbox.Volley
import org.json.JSONArray

interface TelemetrySender {
    suspend fun sendTelemetry(
        endpoint: String,
        jsonRequest: JSONArray,
        cfAccessClientId: String,
        cfAccessClientSecret: String,
        failedArray: JSONArray,
        prefs: SharedPreferences,
        onError: (String) -> Unit,
        onSuccess: () -> Unit
    )
}

class HttpTelemetrySender(private val context: Context) : TelemetrySender {
    override suspend fun sendTelemetry(
        endpoint: String,
        jsonRequest: JSONArray,
        cfAccessClientId: String,
        cfAccessClientSecret: String,
        failedArray: JSONArray,
        prefs: SharedPreferences,
        onError: (String) -> Unit,
        onSuccess: () -> Unit
    ) {
        val queue = Volley.newRequestQueue(context)
        val request = object : JsonArrayRequest(
            Method.POST,
            endpoint,
            jsonRequest,
            { response ->
                if (failedArray.length() > 0) {
                    Log.d("ForegroundService", "Successfully sent ${failedArray.length()} failed requests")
                }
                onSuccess()
            },
            { error ->
                Log.e("ForegroundService", "Error sending request: $error ${error.networkResponse?.statusCode}")
                error.networkResponse?.data?.let {
                    Log.e("ForegroundService", "Response body: ${String(it)}")
                }
                val toStore = if (jsonRequest.length() > ForegroundService.MAX_FAILED_REQUESTS) {
                    JSONArray().apply {
                        for (i in jsonRequest.length() - ForegroundService.MAX_FAILED_REQUESTS until jsonRequest.length()) {
                            put(jsonRequest.get(i))
                        }
                    }
                } else jsonRequest
                Log.e("ForegroundService", "Saving ${toStore.length()} failed requests")
                prefs.edit { putString(ForegroundService.KEY_FAILED_REQUESTS, toStore.toString()) }
                onError("Error code: ${error.networkResponse?.statusCode ?: "Unknown"}")
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
    }
}

