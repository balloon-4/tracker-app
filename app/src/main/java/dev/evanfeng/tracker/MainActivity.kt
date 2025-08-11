package dev.evanfeng.tracker

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import dev.evanfeng.tracker.ui.theme.TrackerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.sentry.Sentry

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

fun isForegroundServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    for (service in activityManager.getRunningServices(Int.MAX_VALUE)) {
        if (serviceClass.name == service.service.className) {
            return true
        }
    }
    return false
}

fun Context.startCompatibleForegroundService(intent: Intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ContextCompat.startForegroundService(this, intent)
    } else {
        this.startService(intent)
    }
}

class MainActivity : ComponentActivity() {

    private val preferencesManager by lazy { PreferencesManager(dataStore) }

    private var pendingAction: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions: Map<String, Boolean> ->
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val backgroundGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions[Manifest.permission.ACCESS_BACKGROUND_LOCATION] == true
        } else true
        val notificationsGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: true
        if (locationGranted && backgroundGranted && notificationsGranted) {
            pendingAction?.invoke()
            pendingAction = null
        } else {
            Toast.makeText(
                this,
                "Location and notification permissions are required",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrackerTheme {
                MainScreen()
            }
        }
        lifecycleScope.launch {
            preferencesManager.initializeDefaultPreferences()
        }
    }

    fun checkPermissionsAndThen(action: () -> Unit) {
        pendingAction = action
        val permissionsToRequest = mutableSetOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionsToRequest.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    fun handleStartServiceButton() {
        checkPermissionsAndThen {
            val intent = Intent(this, ForegroundService::class.java)
            ForegroundService.preferencesManager = preferencesManager
            startCompatibleForegroundService(intent)
            lifecycleScope.launch(Dispatchers.IO) {
                preferencesManager.savePreference(PreferencesManager.Keys.IS_SERVICE_RUNNING, true)
            }
        }
    }

    fun handleStopServiceButton() {
        checkPermissionsAndThen {
            stopService(Intent(this, ForegroundService::class.java))
            lifecycleScope.launch(Dispatchers.IO) {
                preferencesManager.savePreference(PreferencesManager.Keys.IS_SERVICE_RUNNING, false)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsPanelPreview() {
    TrackerTheme {
        SettingsPanel()
    }
}
