package dev.evanfeng.tracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.evanfeng.tracker.ui.theme.TrackerTheme
import android.Manifest
import android.content.Intent
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrackerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        requestRequiredPermissions()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions: Map<String, Boolean> ->

        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        val notificationsGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: true
        // Notifications permission is required only on Android 13+

        if (locationGranted && notificationsGranted) {
            startMyForegroundService()
        } else {
            Toast.makeText(this, "Location and notification permissions are required", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        // Only ask for POST_NOTIFICATIONS on Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest += Manifest.permission.POST_NOTIFICATIONS
        }

        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    private fun startMyForegroundService() {
        val intent = Intent(this, ForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Surface(color = Color.Red) {
        Text(
            text = "Hi, my name is $name!",
            modifier = modifier.padding(25.dp)
        )
    }

}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    TrackerTheme {
        Greeting("Test")
    }
}