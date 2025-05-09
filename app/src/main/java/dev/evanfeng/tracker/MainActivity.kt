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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import dev.evanfeng.tracker.ui.theme.TrackerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionsToRequest += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest += Manifest.permission.POST_NOTIFICATIONS
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

@Composable
fun MainScreen() {
    val selectedTab = remember { mutableStateOf("settings") }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab.value == "settings",
                    onClick = { selectedTab.value = "settings" },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
                NavigationBarItem(
                    selected = selectedTab.value == "logs",
                    onClick = { selectedTab.value = "logs" },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Logs") },
                    label = { Text("Logs") }
                )
            }
        }
    ) { innerPadding ->
        if (selectedTab.value == "settings") {
            SettingsPanel(modifier = Modifier.padding(innerPadding))
        } else {
            LogsPanel(modifier = Modifier.padding(innerPadding))
        }
    }
}

@Composable
fun SettingsPanel(modifier: Modifier = Modifier) {
    val mainActivity = LocalContext.current as? MainActivity
    val context = LocalContext.current
    val isRunning = remember {
        mutableStateOf(
            isForegroundServiceRunning(
                context,
                ForegroundService::class.java
            )
        )
    }
    Column(
        modifier = modifier.then(
            Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        )
    ) {
        Text(text = "Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        SettingItem(
            title = "Endpoint",
            key = PreferencesManager.Keys.ENDPOINT,
            default = "",
        )
        Spacer(modifier = Modifier.height(16.dp))
        SettingItem(
            title = "Session/Name",
            key = PreferencesManager.Keys.NAME,
            default = "",
        )
        Spacer(modifier = Modifier.height(16.dp))
        SettingItem(
            title = "CF Access Client ID",
            key = PreferencesManager.Keys.CF_ACCESS_CLIENT_ID,
            default = "",
        )
        Spacer(modifier = Modifier.height(16.dp))
        SettingItem(
            title = "CF Access Client Secret",
            key = PreferencesManager.Keys.CF_ACCESS_CLIENT_SECRET,
            default = "",
        )
        Spacer(modifier = Modifier.height(16.dp))
        SettingItemInt(
            title = "Interval (seconds)",
            key = PreferencesManager.Keys.INTERVAL,
            default = 60
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                mainActivity?.let {
                    if (isRunning.value) {
                        it.handleStopServiceButton(); isRunning.value = false
                    } else {
                        it.handleStartServiceButton(); isRunning.value = true
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isRunning.value) "Stop Service" else "Start Service")
        }
    }
}

@Composable
fun SettingItem(
    title: String,
    description: String? = null,
    key: Preferences.Key<String>,
    default: String
) {
    val context = LocalContext.current
    val preferencesManager = remember { PreferencesManager(context.dataStore) }
    val value by preferencesManager.getPreferenceFlow(key, default)
        .collectAsState(initial = default)
    val textState = remember(value) { mutableStateOf(value) }
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
            if (!description.isNullOrBlank()) {
                Text(text = description, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                text = textState.value.ifEmpty { "Tap to enter input" },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (showDialog.value) {
        val temp = remember { mutableStateOf(textState.value) }
        AlertDialog(
            onDismissRequest = { showDialog.value = false },
            title = { Text("Enter Value") },
            text = {
                Column {
                    OutlinedTextField(
                        value = temp.value,
                        onValueChange = { temp.value = it },
                        label = { Text("Input") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog.value = false
                    textState.value = temp.value
                    CoroutineScope(Dispatchers.IO).launch {
                        preferencesManager.savePreference(key, textState.value)
                    }
                }) {
                    Text("Ok")
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
fun SettingItemInt(title: String, key: Preferences.Key<Int>, default: Int) {
    val context = LocalContext.current
    val preferencesManager = remember { PreferencesManager(context.dataStore) }
    val value by preferencesManager.getPreferenceFlow(key, default)
        .collectAsState(initial = default)
    val textState = remember(value) { mutableStateOf(value.toString()) }
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
            Text(
                text = textState.value,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (showDialog.value) {
        val temp = remember { mutableStateOf(textState.value) }
        AlertDialog(
            onDismissRequest = { showDialog.value = false },
            title = { Text("Enter Value") },
            text = {
                Column {
                    OutlinedTextField(
                        value = temp.value,
                        onValueChange = { temp.value = it },
                        label = { Text("Input") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog.value = false
                    val newValue = try {
                        temp.value.toInt()
                    } catch (e: Exception) {
                        default
                    }
                    textState.value = newValue.toString()
                    CoroutineScope(Dispatchers.IO).launch {
                        preferencesManager.savePreference(key, newValue)
                    }
                }) {
                    Text("Ok")
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
fun LogsPanel(modifier: Modifier = Modifier) {
    val logs = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    val logsReader = remember { LogsReader() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        logs.addAll(logsReader.fetchInitialLogs(25))
        listState.scrollToItem(logs.size - 1)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Logs", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = {
                coroutineScope.launch {
                    val refreshed = logsReader.fetchInitialLogs(25)
                    logs.clear()
                    logs.addAll(refreshed)
                    listState.scrollToItem(logs.size - 1)
                }
            }) {
                Text("Refresh")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(state = listState) {
            items(logs) { log ->
                Text(text = log, style = MaterialTheme.typography.bodyMedium)
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
