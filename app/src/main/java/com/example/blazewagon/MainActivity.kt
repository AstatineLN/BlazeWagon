package com.example.blazewagon

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import java.io.IOException
import java.io.OutputStream
import java.util.*
import kotlin.concurrent.thread
import android.widget.SeekBar

// Unique UUID for serial port service (standard SPP profile)
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

// This MUST match the name in your ESP32 Arduino code
private const val ESP32_DEVICE_NAME = "BlazeWagon_ESP32"

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var gpsTextView: TextView
    private lateinit var stopButton: Button

    // System State Tracker
    private var isStopped = false

    // Bluetooth
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    // GPS/Location
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    // Manual Mode
    private lateinit var modeToggle: androidx.appcompat.widget.SwitchCompat
    private lateinit var speedSeekBar: SeekBar
    private lateinit var speedValueText: TextView
    private lateinit var btnForward: Button
    private lateinit var btnBack: Button
    private lateinit var btnLeft: Button
    private lateinit var btnRight: Button
    private lateinit var btnHalt: Button

    private var isAutonomousMode = true
    private var currentManualPower = 45 // Default speed

    // Bluetooth Input Stream (for receiving RESET confirmation)
    private var isBluetoothThreadRunning = false

    // --- ACTIVITY LIFECYCLE ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI elements
        statusTextView = findViewById(R.id.statusTextView)
        gpsTextView = findViewById(R.id.gpsTextView)
        stopButton = findViewById(R.id.stopButton)
        // Initialize new UI elements
        modeToggle = findViewById(R.id.modeToggle)
        speedSeekBar = findViewById(R.id.speedSeekBar)
        speedValueText = findViewById(R.id.speedValueText)
        btnForward = findViewById(R.id.btnForward)
        btnBack = findViewById(R.id.btnBack)
        btnLeft = findViewById(R.id.btnLeft)
        btnRight = findViewById(R.id.btnRight)
        btnHalt = findViewById(R.id.btnHalt)
        // 1. Toggle between Following and Manual
        modeToggle.setOnCheckedChangeListener { _, isChecked ->
            isAutonomousMode = isChecked
            if (isChecked) {
                sendCommand("MODE:AUTO")
            } else {
                sendCommand("MODE:MANUAL")
                sendCommand("HALT") // Stop immediately when switching to manual for safety
            }
        }

// 2. Slider for Power/Speed (crucial for handling wagon load)
        speedSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                currentManualPower = progress
                speedValueText.text = "Manual Power: $currentManualPower%"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
// 3. Movement Listener (Press to Move, Release to Stop)
        @SuppressLint("ClickableViewAccessibility")
        val moveListener = android.view.View.OnTouchListener { view, event ->
            if (isAutonomousMode) return@OnTouchListener false // Ignore manual buttons in Auto mode

            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val command = when (view.id) {
                        R.id.btnForward -> "FORWARD:$currentManualPower"
                        R.id.btnBack -> "BACKWARD:$currentManualPower"
                        R.id.btnLeft -> "LEFT:$currentManualPower"
                        R.id.btnRight -> "RIGHT:$currentManualPower"
                        else -> "HALT"
                    }
                    sendCommand(command)
                }
                android.view.MotionEvent.ACTION_UP -> {
                    sendCommand("HALT") // Stop immediately when finger is lifted
                }
            }
            true
        }

        // Assign the listener to the buttons
        btnForward.setOnTouchListener(moveListener)
        btnBack.setOnTouchListener(moveListener)
        btnLeft.setOnTouchListener(moveListener)
        btnRight.setOnTouchListener(moveListener)

        // Simple click for the center Halt button
        btnHalt.setOnClickListener { sendCommand("HALT") }
        // Initialize Location clients
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()

        // Setup the initial button state
        setStoppedState(false) // Start in running state

        // The button now handles both STOP and RESET
        stopButton.setOnClickListener {
            if (!isStopped) {
                // RUNNING -> STOPPED
                sendCommand("STOP")
                setStoppedState(true) // Update UI immediately to STOPPED state
            } else {
                // STOPPED -> RUNNING (RESET)
                sendCommand("RESET")
                // We'll let the receive thread handle the state change if needed,
                // but for speed, we can reset locally:
                setStoppedState(false)
            }
        }

        // 1. Request Permissions and start connection process
        requestPermissionsAndConnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeBluetoothConnection()
    }

    // --- SYSTEM STATE MANAGEMENT ---

    // Updates the button UI and internal state
    @SuppressLint("ResourceAsColor")
    private fun setStoppedState(stopped: Boolean) {
        isStopped = stopped
        runOnUiThread {
            if (stopped) {
                stopButton.text = "SYSTEM RESET"
                stopButton.setBackgroundColor(Color.parseColor("#FF4CAF50")) // Green
                statusTextView.text = "Status: EMERGENCY STOP ACTIVE"
            } else {
                stopButton.text = resources.getString(R.string.emergency_stop) // "EMERGENCY STOP"
                stopButton.setBackgroundColor(Color.parseColor("#FFD32F2F")) // Red
                statusTextView.text = "Status: Connected & Tracking GPS"
            }
        }
    }

    // --- BLUETOOTH RECEIVE LOGIC ---

    // Separate thread to listen for data from the ESP32 (e.g., a RESET confirmation)
    @SuppressLint("MissingPermission")
    private fun beginBluetoothListener() {
        if (bluetoothSocket == null || isBluetoothThreadRunning) return
        isBluetoothThreadRunning = true

        val inputStream = bluetoothSocket?.inputStream
        val buffer = ByteArray(1024)
        var bytes: Int

        thread {
            while (isBluetoothThreadRunning) {
                try {
                    bytes = inputStream?.read(buffer) ?: -1
                    if (bytes > 0) {
                        val incomingMessage = String(buffer, 0, bytes).trim()

                        // Handle incoming commands on the UI thread
                        runOnUiThread {
                            receiveCommand(incomingMessage)
                        }
                    }
                } catch (e: IOException) {
                    Log.e("Bluetooth", "Input stream disconnected", e)
                    isBluetoothThreadRunning = false
                    closeBluetoothConnection()
                    break
                }
            }
        }
    }

    // Processes commands received FROM the ESP32
    private fun receiveCommand(command: String) {
        when {
            command == "RESET_OK" -> {
                setStoppedState(false)
                Toast.makeText(this, "System Reset Confirmed", Toast.LENGTH_SHORT).show()
            }
            command == "CONFIRM:AUTO" -> {
                statusTextView.text = "Status: Autonomous Following"
                statusTextView.setTextColor(Color.BLUE)
            }
            command == "CONFIRM:MANUAL" -> {
                statusTextView.text = "Status: Manual Control Active"
                statusTextView.setTextColor(Color.parseColor("#FFA500")) // Orange
            }
        }
    }

    // --- BLUETOOTH CONNECTION AND SEND LOGIC (REST OF THE FILE REMAINS SIMILAR) ---

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            connectToEsp32()
            startGpsUpdates()
        } else {
            statusTextView.text = "Status: Permissions Denied"
            Toast.makeText(this, "Location and Bluetooth permissions are required.", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestPermissionsAndConnect() {
        val permissionsToRequest = mutableListOf<String>()

        permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun connectToEsp32() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            statusTextView.text = "Status: BT Not Available/Enabled"
            return
        }

        statusTextView.text = "Status: Connecting to $ESP32_DEVICE_NAME..."

        thread {
            try {
                val device: BluetoothDevice? = bluetoothAdapter.bondedDevices.find { it.name == ESP32_DEVICE_NAME }

                if (device != null) {
                    bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    bluetoothSocket?.connect()
                    outputStream = bluetoothSocket?.outputStream

                    // Start listening for incoming data after connection is established
                    beginBluetoothListener()

                    runOnUiThread {
                        statusTextView.text = "Status: Connected to $ESP32_DEVICE_NAME"
                        Toast.makeText(this, "Connected successfully!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runOnUiThread {
                        statusTextView.text = "Status: Device Not Found"
                        Toast.makeText(this, "Paired device '$ESP32_DEVICE_NAME' not found. Did you pair it?", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: IOException) {
                Log.e("Bluetooth", "Connection failed", e)
                closeBluetoothConnection()
                runOnUiThread {
                    statusTextView.text = "Status: Connection Failed"
                    Toast.makeText(this, "Failed to connect to ESP32.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeBluetoothConnection() {
        isBluetoothThreadRunning = false
        try {
            outputStream?.close()
            bluetoothSocket?.close()
            outputStream = null
            bluetoothSocket = null
        } catch (e: IOException) {
            Log.e("Bluetooth", "Error closing socket/stream", e)
        }
    }

    private fun sendCommand(command: String) {
        if (outputStream == null) {
            statusTextView.text = "Status: Not Connected"
            // Toast.makeText(this, "Not connected to ESP32.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            outputStream?.write((command + "\n").toByteArray()) // Add newline delimiter
            Log.i("Bluetooth", "Sent command: $command")
        } catch (e: IOException) {
            Log.e("Bluetooth", "Error sending command", e)
            runOnUiThread {
                statusTextView.text = "Status: Disconnected"
                Toast.makeText(this, "Bluetooth Disconnected!", Toast.LENGTH_LONG).show()
            }
            closeBluetoothConnection()
        }
    }

    // --- GPS LOGIC ---

    private fun setupLocationCallback() {
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(500)
            .setMaxUpdateDelayMillis(1000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.locations.forEach { location ->
                    if (!isStopped) { // ONLY send GPS data if system is NOT stopped
                        sendGpsData(location)
                    } else {
                        gpsTextView.text = "Lat: --\nLon: --\nSpeed: -- m/s (System Stopped)"
                    }
                }
            }
        }
    }

    private fun isLocationEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(
            LocationManager.NETWORK_PROVIDER
        )
    }

    @SuppressLint("MissingPermission")
    private fun startGpsUpdates() {
        if (!isLocationEnabled(this)) {
            Toast.makeText(this, "Please turn on GPS location.", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(intent)
            return
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
        statusTextView.text = "Status: Connected & Tracking GPS"
    }

    private fun sendGpsData(location: Location) {
        // Only send if in Auto mode AND not emergency stopped
        if (!isAutonomousMode || isStopped) return

        val lat = String.format("%.6f", location.latitude)
        val lon = String.format("%.6f", location.longitude)
        val speed = String.format("%.2f", location.speed)

        val gpsData = "GPS:$lat,$lon,$speed"
        gpsTextView.text = "Lat: $lat\nLon: $lon\nSpeed: $speed m/s"

        sendCommand(gpsData)
    }
}