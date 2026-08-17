package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.LaunchedEffect
import android.os.Build
import android.util.Log
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.example.myapplication.ui.theme.MyApplicationTheme
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
class BluetoothConnectionManager(private val context: Context) {
    private val bluetoothManager: BluetoothManager? = try {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    } catch (e: Throwable) {
        // Bluetooth service is not available in Layout Preview, which throws AssertionError
        null
    }
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDevice>> = _discoveredDevices

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // State flow to expose received data to your Compose UI
    private val _incomingData = MutableStateFlow<String>("")
    val incomingData: StateFlow<String> = _incomingData

    private var bluetoothSocket: BluetoothSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        // Standard SPP (Serial Port Profile) UUID for HC-05 / HC-06
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    fun isBluetoothSupported(): Boolean = bluetoothAdapter != null

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /**
     * Gets a list of already paired devices.
     * Note: The user must pair the HC-05 in Android's Bluetooth settings first.
     */
    fun getPairedDevices(): List<BluetoothDevice> {
        return try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList() // Handle missing permissions gracefully
        }
    }

    /**
     * Safely finds a paired device by name, handling permission issues.
     * Case-insensitive and trims whitespace for better matching.
     */
    fun findPairedDeviceByName(name: String): BluetoothDevice? {
        val target = name.trim().uppercase()
        return try {
            bluetoothAdapter?.bondedDevices?.find {
                try {
                    it.name?.trim()?.uppercase() == target
                } catch (e: SecurityException) {
                    false
                }
            }
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * Initiates connection to the HC-05 module in a background thread.
     * Uses a more robust method by trying insecure connection and reflection if secure fails.
     */
    suspend fun connectToDevice(device: BluetoothDevice) {
        withContext(Dispatchers.IO) {
            // Close any existing connection first to clear the state
            closeConnection()
            
            _connectionState.value = ConnectionState.Connecting

            // Always cancel discovery before connecting
            try {
                bluetoothAdapter?.cancelDiscovery()
            } catch (e: SecurityException) { }

            try {
                // Attempt 1: Standard Secure Socket
                bluetoothSocket = try {
                    device.createRfcommSocketToServiceRecord(SPP_UUID)
                } catch (e: IOException) {
                    // Attempt 2: Standard Insecure Socket
                    device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                }
                
                bluetoothSocket?.connect() 
                _connectionState.value = ConnectionState.Connected(device)
                listenForIncomingData()
            } catch (e: SecurityException) {
                _connectionState.value = ConnectionState.Error("Bluetooth permission denied (Connect).")
                closeConnection()
            } catch (e: IOException) {
                // Attempt 3: Reflection Fallback (Force connection to Port 1)
                // This is the "ultimate" fix for stubborn HC-05 modules
                try {
                    closeConnection()
                    val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    bluetoothSocket = m.invoke(device, 1) as BluetoothSocket
                    bluetoothSocket?.connect()
                    
                    _connectionState.value = ConnectionState.Connected(device)
                    listenForIncomingData()
                } catch (e2: Exception) {
                    _connectionState.value = ConnectionState.Error("Connection failed: ${e2.localizedMessage}")
                    closeConnection()
                }
            }
        }
    }

    /**
     * Loops in the background, listening for data sent from the Arduino via HC-05
     */
    private fun listenForIncomingData() {
        val socket = bluetoothSocket ?: return
        val inputStream: InputStream = try {
            socket.inputStream
        } catch (e: IOException) {
            return
        }

        scope.launch {
            val buffer = ByteArray(1024)
            var bytes: Int

            while (_connectionState.value is ConnectionState.Connected) {
                try {
                    bytes = inputStream.read(buffer)
                    val message = String(buffer, 0, bytes)
                    // Append incoming data instead of overwriting, so we can handle multi-byte responses
                    _incomingData.value = (_incomingData.value + message).takeLast(10)
                } catch (e: IOException) {
                    _connectionState.value = ConnectionState.Error("Connection lost.")
                    closeConnection()
                    break
                }
            }
        }
    }

    /**
     * Sends a string message to the HC-05 (e.g. "1" to turn on an LED)
     */
    fun sendData(message: String) {
        val socket = bluetoothSocket ?: return
        if (!socket.isConnected) return

        scope.launch {
            try {
                val outputStream: OutputStream = socket.outputStream
                outputStream.write(message.toByteArray())
            } catch (e: IOException) {
                _connectionState.value = ConnectionState.Error("Failed to send data.")
            }
        }
    }

    /**
     * Closes socket and resets state
     */
    fun closeConnection() {
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            // Safe to ignore on close
        } finally {
            bluetoothSocket = null
            _incomingData.value = "" // Reset data on disconnect
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    /**
     * Clears the incoming data buffer manually
     */
    fun clearIncomingData() {
        _incomingData.value = ""
    }
}
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MyApplicationApp()
            }
        }
    }
}

@PreviewScreenSizes
@Composable
fun MyApplicationApp() {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }

    NavigationSuiteScaffold(navigationSuiteItems = {
        AppDestinations.entries.forEach {
            item(
                icon = {
                    Icon(
                        painterResource(it.icon),
                        contentDescription = it.label
                    )
                },
                label = { Text(it.label) },
                selected = it == currentDestination,
                onClick = { currentDestination = it }
            )
        }

    }

    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Scaffold(
                name = "Kontroler wentylacji",
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

enum class AppDestinations(
    val label: String,
    val icon: Int,
) {
    HOME("sterowanie", R.drawable.ic_home),

}






sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val device: BluetoothDevice) : ConnectionState()
    data class Error(val message: String) : ConnectionState()}
@Composable
fun Scaffold(name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    
    // Runtime Permission Handling for Android 12+ (API 31+)
    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            Log.e("Bluetooth", "Permissions not granted")
        }
    }

    LaunchedEffect(Unit) {
        launcher.launch(permissionsToRequest)
    }

    var isWentylacja by remember { mutableStateOf(false) }
    var isKabina by remember { mutableStateOf(false) }
    var isgear by remember { mutableStateOf(false) }
    var isEngine by remember { mutableStateOf(false) }
    // 1. Initialize the Bluetooth manager & remember its instance
    val btManager = remember { BluetoothConnectionManager(context) }

    // 2. Collect StateFlows as Compose State so the UI auto-updates
    val connectionState by btManager.connectionState.collectAsState()
    val incomingData by btManager.incomingData.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Sync UI switches with Arduino state when "?" response is received
    LaunchedEffect(incomingData) {
        if (incomingData.length >= 2) {
            val s0 = incomingData[0] // state (Rack/Kabina)
            val s1 = incomingData[1] // state1 (Wentylacja/Klima)
            val s2= incomingData[2]
            val s3 = incomingData[3]
            if (s0.isDigit() && s1.isDigit()) {
                // If state == 0, it was Case '0' (kabina in code, user says rack)
                isKabina = (s0 == '0')
                // If state1 == 0, it was Case '2' (wentylacja)
                isWentylacja = (s1 == '0')
            }
            if (s2.isDigit() && s3.isDigit()){
                isgear= (s2 =='0')
                isEngine= (s3 =='0')
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Display current connection status with clear text
        val statusText = when (val state = connectionState) {
            is ConnectionState.Disconnected -> "Rozłączony"
            is ConnectionState.Connecting -> "Łączenie..."
            is ConnectionState.Connected -> {
                val deviceName = try { state.device.name } catch (e: SecurityException) { "Urządzenie" }
                "Połączono: $deviceName"
            }
            is ConnectionState.Error -> "Błąd: ${state.message}"
        }
        
        Text(
            text = "Status: $statusText",
            modifier = Modifier.padding(8.dp)
        )

            

        Spacer(modifier = Modifier.height(16.dp))

        // Button to trigger Connection
        Button(onClick = {
            // Find your HC-05 from paired list safely
            val hc05 = btManager.findPairedDeviceByName("HC-05")

            if (hc05 != null) {
                coroutineScope.launch {
                    btManager.connectToDevice(hc05)
                    // Request state immediately after connection
                    delay(2000) // Longer delay to ensure connection is ready
                    btManager.clearIncomingData() // Ensure buffer is empty
                    btManager.sendData("?")
                }
            } else {
                // HC-05 is not paired, handle error or show message
                println("HC-05 module not found in paired devices or permission denied.")
            }
        }) {
            Text("Podłącz do Arduino")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Button for Klimatyzacja / Wentylacja toggle
        Button(
            onClick = {
                if (isWentylacja) {
                    btManager.sendData("3") // Switch to Klimatyzacja
                } else {
                    btManager.sendData("2") // Switch to Wentylacja
                }
                isWentylacja = !isWentylacja
            },
            enabled = connectionState is ConnectionState.Connected
        ) {
            Text(if (isWentylacja) "wentylacja" else "Klimatyzacja")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Button for Rack / Kabina toggle
        Button(
            onClick = {
                if (isKabina) {
                    btManager.sendData("1") // Switch to Rack
                } else {
                    btManager.sendData("0") // Switch to Kabina
                }
                isKabina = !isKabina
            },
            enabled = connectionState is ConnectionState.Connected
        ) {
            Text(if (isKabina) "kabina" else "Rack")
        }

        Spacer(modifier = Modifier.height(8.dp))
        // Button to disconnect safely



        Button(
            onClick = {
                if (isgear) {
                    btManager.sendData("4") // Switch to high gear
                } else {
                    btManager.sendData("5") // Switch to low gear
                }
                isgear = !isgear
            },
            enabled = connectionState is ConnectionState.Connected
        ) {
            Text(if (isgear) "Wysoki" else "Niski")
        }
        Button(
            onClick = {
                if (isEngine) {
                    btManager.sendData("6") // Switch to wyłącz
                } else {
                    btManager.sendData("7") // Switch to włączony
                }
                isEngine = !isEngine
            },
            enabled = connectionState is ConnectionState.Connected
        ) {
            Text(if (isEngine) "Włączony" else "Wyłączony")
        }
        Button(
            onClick = {
                btManager.closeConnection()
                isWentylacja = false
                isKabina = false
            },
            enabled = connectionState is ConnectionState.Connected
        ) {
            Text("Rozłącz ")
        }
    }
}
