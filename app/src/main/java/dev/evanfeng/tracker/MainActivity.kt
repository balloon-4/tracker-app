package dev.evanfeng.tracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.evanfeng.tracker.ui.theme.TrackerTheme

// New helper for compatibility
fun Context.startCompatibleForegroundService(intent: Intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
         ContextCompat.startForegroundService(this, intent)
    } else {
         this.startService(intent)
    }
}

class MainActivity : ComponentActivity() {

    // New field to store the pending action after permissions are granted
    private var pendingAction: (() -> Unit)? = null

    // Updated permissionLauncher: now calls pendingAction if all permissions are granted
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions: Map<String, Boolean> ->
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val notificationsGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: true
        if (locationGranted && notificationsGranted) {
            pendingAction?.invoke()
            pendingAction = null
        } else {
            Toast.makeText(this, "Location and notification permissions are required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrackerTheme {
                SettingsPanel()
            }
        }
    }

    fun checkPermissionsAndThen(action: () -> Unit) {
         pendingAction = action
         val permissionsToRequest = mutableListOf(
             Manifest.permission.ACCESS_FINE_LOCATION,
             Manifest.permission.ACCESS_COARSE_LOCATION
         )
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             permissionsToRequest += Manifest.permission.POST_NOTIFICATIONS
         }
         permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    fun handleStartServiceButton() {
         checkPermissionsAndThen {
             val intent = Intent(this, ForegroundService::class.java)
             startCompatibleForegroundService(intent)
         }
    }

    fun handleStopServiceButton() {
         checkPermissionsAndThen {
             stopService(Intent(this, ForegroundService::class.java))
         }
    }
}

@Composable
fun SettingsPanel() {
    val mainActivity = LocalContext.current as? MainActivity
    val toggleState = remember { mutableStateOf(false) }
    // Add context for Toast usage
    val context = LocalContext.current
    Scaffold(
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(16.dp)) {
            Text(text = "Tracker", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            SettingItem(
                title = "Setting 1",
                description = "This is the description for setting 1."
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingItem(
                title = "Setting 2",
                description = "Description for setting 2 goes here."
            )
            // Insert new toggle setting with description area
            Spacer(modifier = Modifier.height(8.dp))
            SettingToggleItem(
                title = "Toggle Setting",
                description = "Description for the toggle setting.",
                toggleState = toggleState
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { 
                    mainActivity?.handleStartServiceButton()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start Service")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { 
                    mainActivity?.handleStopServiceButton()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Stop Service")
            }
            // Insert dummy save settings button
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { 
                    Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Settings")
            }
        }
    }
}

@Composable
fun SettingItem(title: String, description: String) {
    val textState = remember { mutableStateOf("") }
    val showDialog = remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog.value = true },
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            // Display current input or a hint
            Text(
                text = if (textState.value.isEmpty()) "Tap to enter input" else textState.value,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    
    if (showDialog.value) {
        AlertDialog(
            onDismissRequest = { showDialog.value = false },
            title = { Text("Enter Value") },
            text = {
                Column {
                    OutlinedTextField(
                        value = textState.value,
                        onValueChange = { textState.value = it },
                        label = { Text("Input") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog.value = false }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog.value = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SettingToggleItem(title: String, description: String, toggleState: androidx.compose.runtime.MutableState<Boolean>) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = description, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(
                checked = toggleState.value,
                onCheckedChange = { toggleState.value = it }
            )
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
