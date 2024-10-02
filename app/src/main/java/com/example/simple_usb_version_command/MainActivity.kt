package com.example.simple_usb_version_command

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.simple_usb_version_command.ui.theme.SimpleusbversioncommandTheme
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

private const val TAG = "MainActivity"
private const val ACTION_USB_PERMISSION = "com.example.USB_PERMISSION"

// Enum to represent different types of USB devices
enum class DeviceType {
    HUB, IRON, UNKNOWN
}

class MainActivity : ComponentActivity(), SerialInputOutputManager.Listener {
    private lateinit var usbManager: UsbManager
    private lateinit var webView: WebView
    private var usbSerialPort: UsbSerialPort? = null
    private var usbIoManager: SerialInputOutputManager? = null
    private var isAttached = false
    private var isConnected = false
    private var currentDeviceType = DeviceType.UNKNOWN
    private val receivedString = StringBuilder()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    private val validDevices = listOf(
        UsbId(13420, 7936), UsbId(13420, 7937)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        setupUsbBroadcastReceiver()
        setupUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        disconnect()
        coroutineScope.cancel()
    }

    // Set up the UI using Jetpack Compose
    private fun setupUI() {
        enableEdgeToEdge()
        setContent {
            SimpleusbversioncommandTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                            WebView(context).apply {
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        checkForAttachedUsbDevices()
                                    }
                                }
                                settings.javaScriptEnabled = true
                                addJavascriptInterface(WebAppInterface(), "Android")
                                loadUrl("file:///android_asset/index.html")
                                webView = this
                            }
                        })
                    }
                }
            }
        }
    }

    // Set up the USB broadcast receiver
    private fun setupUsbBroadcastReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    // Broadcast receiver for USB events
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> handleUsbPermission(intent)
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> handleUsbAttached(intent)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> handleUsbDetached()
            }
        }
    }

    // Check for already attached USB devices
    private fun checkForAttachedUsbDevices() {
        val deviceList = usbManager.deviceList
        if (deviceList.isNotEmpty()) {
            for (device in deviceList.values) {
                if (isValidDevice(device)) {
                    Log.d(TAG, "Attached valid USB device: ${device.deviceName}")
                    handleUsbAttached(Intent().apply { putExtra(UsbManager.EXTRA_DEVICE, device) })
                    break
                }
            }
        } else {
            Log.d(TAG, "No USB devices are attached")
        }
    }

    // Handle USB device attachment
    private fun handleUsbAttached(intent: Intent) {
        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        device?.let {
            if (isValidDevice(it)) {
                isAttached = true
                currentDeviceType = getDeviceType(it)
                updateWebViewButtonStates()
                Log.d(TAG, "Valid USB device attached: ${it.deviceName}, Type: $currentDeviceType")
            } else {
                Log.d(TAG, "Unsupported USB device attached: ${it.deviceName}")
            }
        }
    }

    // Handle USB permission result
    private fun handleUsbPermission(intent: Intent) {
        synchronized(this) {
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                device?.let {
                    if (isValidDevice(it)) {
                        Log.d(TAG, "USB permission granted")
                        connect(it)
                    } else {
                        Log.d(TAG, "USB permission granted for unsupported device")
                    }
                }
            } else if (device != null && usbManager.hasPermission(device)) {
                connect(device)
            } else {
                Log.e(TAG, "USB permission denied")
            }
        }
    }

    // Handle USB device detachment
    private fun handleUsbDetached() {
        isAttached = false
        currentDeviceType = DeviceType.UNKNOWN
        updateWebViewButtonStates()
        disconnect()
        Log.d(TAG, "USB device detached")
    }

    // Request USB permission
    private fun requestUsbPermission(usbDevice: UsbDevice) {
        if (usbManager.hasPermission(usbDevice)) {
            connect(usbDevice)
        } else {
            val usbPermissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) },
                PendingIntent.FLAG_MUTABLE
            )
            usbManager.requestPermission(usbDevice, usbPermissionIntent)
        }
    }

    // Connect to the USB device
    private fun connect(device: UsbDevice) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                usbSerialPort = driver.ports[0]
                val usbConnection = usbManager.openDevice(driver.device)
                if (usbConnection == null) {
                    Log.e(TAG, "Failed to open device")
                    return@launch
                }
                Log.d(TAG, "USB connection opened")

                usbSerialPort?.apply {
                    open(usbConnection)
                    setParameters(115200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    dtr = true
                    rts = true
                }
                Log.d(TAG, "USB serial port opened and parameters set")

                usbIoManager = SerialInputOutputManager(usbSerialPort, this@MainActivity).apply { start() }
                Log.d(TAG, "SerialInputOutputManager started")

                isConnected = true
                updateWebViewButtonStates()
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to device", e)
            }
        }
    }

    // Disconnect from the USB device
    private fun disconnect() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                usbIoManager?.stop()
                usbSerialPort?.close()
                usbIoManager = null
                usbSerialPort = null
                isConnected = false
                updateWebViewButtonStates()
                Log.d(TAG, "USB disconnected")
            } catch (e: IOException) {
                Log.e(TAG, "Error disconnecting USB", e)
            }
        }
    }

    // Handle new data received from the USB device
    override fun onNewData(data: ByteArray?) {
        data?.let {
            val receivedData = String(it, Charsets.UTF_8)
            receivedString.append(receivedData)
            processReceivedData()
        }
    }

    // Process the received data and update the WebView
    private fun processReceivedData() {
        val delimiter = getDelimiter()
        val index = receivedString.indexOf(delimiter)
        if (index != -1) {
            val extractedString = receivedString.substring(0, index).trim()
            receivedString.delete(0, index + delimiter.length)
            coroutineScope.launch(Dispatchers.Main) {
                Log.d(TAG, "Received complete line: $extractedString")
                val jsCode = "javascript:displayCommandResult('${
                    extractedString.replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
                }');"
                webView.evaluateJavascript(jsCode, null)
            }
        }
    }

    // Get the appropriate delimiter based on the device type
    private fun getDelimiter(): String = when (currentDeviceType) {
        DeviceType.HUB -> "\u001B[1;32mifixitl5:~$ \u001B[m"
        DeviceType.IRON -> "\u001B[1;32mifixitiron:~$ \u001B[m"
        DeviceType.UNKNOWN -> throw IllegalArgumentException("Cannot get delimiter for unknown device type")
    }

    // Handle run errors
    override fun onRunError(e: Exception?) {
        Log.e(TAG, "Run error: ${e?.message}", e)
    }

    // Update the WebView button states
    private fun updateWebViewButtonStates() {
        runOnUiThread {
            val jsCode = "javascript:updateButtonStates($isAttached, $isConnected, '${currentDeviceType}');"
            webView.evaluateJavascript(jsCode, null)
        }
    }

    // Check if the USB device is valid
    private fun isValidDevice(device: UsbDevice): Boolean =
        validDevices.any { it.vendorId == device.vendorId && it.productId == device.productId }

    // Get the device type based on the product ID
    private fun getDeviceType(device: UsbDevice): DeviceType = when (device.productId) {
        7936 -> DeviceType.HUB
        7937 -> DeviceType.IRON
        else -> DeviceType.UNKNOWN
    }

    // WebAppInterface for JavaScript communication
    inner class WebAppInterface {
        @JavascriptInterface
        fun connectUsb() {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Connecting to USB...")
                    val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                    val validDriver = availableDrivers.find { isValidDevice(it.device) }
                    if (validDriver == null) {
                        Log.e(TAG, "No valid drivers found")
                        return@launch
                    }
                    Log.d(TAG, "Found valid driver: ${validDriver.javaClass.simpleName}")
                    requestUsbPermission(validDriver.device)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in connectUsb", e)
                }
            }
        }

        @JavascriptInterface
        fun disconnectUsb() {
            coroutineScope.launch(Dispatchers.IO) {
                disconnect()
            }
        }

        @JavascriptInterface
        fun sendCommand(command: String) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Sending command: $command")
                    usbSerialPort?.write((command).toByteArray(), 1000)
                    Log.d(TAG, "Command sent: $command")
                } catch (e: IOException) {
                    Log.e(TAG, "Error sending command", e)
                }
            }
        }
    }
}

// Data class to represent USB device IDs
data class UsbId(val vendorId: Int, val productId: Int)