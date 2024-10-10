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
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
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
import android.util.Base64
import java.io.IOException

private const val TAG = "MainActivity"
private const val ACTION_USB_PERMISSION = "com.example.USB_PERMISSION"

class MainActivity : ComponentActivity(), SerialInputOutputManager.Listener {
    private lateinit var usbManager: UsbManager
    private lateinit var webView: WebView

    private var usbIoManager: SerialInputOutputManager? = null
    private var usbDevice: UsbDevice? = null
    private var usbSerialPort: UsbSerialPort? = null
    private var isAttached = false
    private var isConnected = false
    private val validDevices = listOf(UsbId(13420, 7936), UsbId(13420, 7937))

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
                                settings.javaScriptEnabled = true
                                WebView.setWebContentsDebuggingEnabled(true)
                                addJavascriptInterface(WebAppInterface(), "Android")
                                loadUrl("https://bc17d2d2201a.ngrok.app/fixhub/console")
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
                UsbManager.ACTION_USB_DEVICE_DETACHED -> handleUsbDetached()
            }
        }
    }

    // Handle USB permission result
    private fun handleUsbPermission(intent: Intent) {
        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        val isPermissionGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

        when {
            isPermissionGranted && device != null -> {
                if (isValidDevice(device)) {
                    Log.d(TAG, "USB permission granted")
                    val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                    usbDevice = driver.device
                    usbSerialPort = driver.ports[0]
                    webView.evaluateJavascript("javascript:resolvePortRequest();", null)
                } else {
                    Log.d(TAG, "USB permission granted for unsupported device")
                    webView.evaluateJavascript("javascript:rejectPortRequest();", null)
                }
            }

            device != null && usbManager.hasPermission(device) -> {
                val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                usbDevice = driver.device
                usbSerialPort = driver.ports[0]
                webView.evaluateJavascript("javascript:resolvePortRequest();", null)
            }

            else -> {
                Log.e(TAG, "USB permission denied")
                webView.evaluateJavascript("javascript:rejectPortRequest(\"Permission denied\");", null)
            }
        }
    }

    // Handle USB device detachment
    private fun handleUsbDetached() {
        isAttached = false
        disconnect()
        webView.evaluateJavascript("javascript:onNativeEvent(\"disconnect\");", null)
        Log.d(TAG, "USB device detached")
    }

    // Request USB permission
    private fun requestUsbPermission(usbDevice: UsbDevice) {
        val usbPermissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) },
            PendingIntent.FLAG_MUTABLE
        )
        usbManager.requestPermission(usbDevice, usbPermissionIntent)
    }

    // Disconnect from the USB device
    private fun disconnect() {
            try {
                usbIoManager?.stop()
                usbSerialPort?.close()
                usbIoManager = null
                usbSerialPort = null
                usbDevice = null
                isConnected = false
                Log.d(TAG, "USB disconnected")
            } catch (e: IOException) {
                Log.e(TAG, "Error disconnecting USB", e)
            }
    }

    // Handle new data received from the USB device
    override fun onNewData(data: ByteArray?) {
        data?.let {
            val base64Data = Base64.encodeToString(it, Base64.NO_WRAP)
            runOnUiThread {
                webView.evaluateJavascript("javascript:onNewData('$base64Data')", null)
            }
        }
    }

    // Handle run errors
    override fun onRunError(e: Exception?) {
        Log.e(TAG, "Run error: ${e?.message}", e)
    }

    // Check if the USB device is valid
    private fun isValidDevice(device: UsbDevice): Boolean =
        validDevices.any { it.vendorId == device.vendorId && it.productId == device.productId }

    // WebAppInterface for JavaScript communication
    inner class WebAppInterface {
        @JavascriptInterface
        fun hasValidDeviceConnected(): Boolean {
            try {
                val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                val validDriver = availableDrivers.find { isValidDevice(it.device) }
                if (validDriver != null) {
                    return true;
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in hasValidDeviceConnected", e)
            }
            return false;
        }

        @JavascriptInterface
        fun requestPort() {
            try {
                Log.d(TAG, "Connecting to USB...")
                val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                val validDriver = availableDrivers.find { isValidDevice(it.device) }
                if (validDriver == null) {
                    Log.e(TAG, "No valid drivers found")
                    return
                }
                Log.d(TAG, "Found valid driver: ${validDriver.javaClass.simpleName}")
                requestUsbPermission(validDriver.device)
            } catch (e: Exception) {
                Log.e(TAG, "Error in connectUsb", e)
            }
        }

        @JavascriptInterface
        fun getInfo(): String? {
            return usbDevice?.let { device ->
                val productId = device.productId
                val vendorId = device.vendorId
                return "{ \"usbProductId\": $productId, \"usbVendorId\": $vendorId }"
            }
        }

        @JavascriptInterface
        fun connectUsb() {
            try {
                val device = usbDevice ?: run {
                    Log.e(TAG, "No device stored")
                    return
                }
                val usbConnection = usbManager.openDevice(usbDevice)
                if (usbConnection == null) {
                    Log.e(TAG, "Failed to open device")
                    return
                }
                Log.d(TAG, "USB connection opened")

                usbSerialPort?.apply {
                    open(usbConnection)
                    setParameters(
                        115200,
                        UsbSerialPort.DATABITS_8,
                        UsbSerialPort.STOPBITS_1,
                        UsbSerialPort.PARITY_NONE
                    )
                    dtr = true
                    rts = true
                }
                Log.d(TAG, "USB serial port opened and parameters set")

                usbIoManager =
                    SerialInputOutputManager(usbSerialPort, this@MainActivity).apply { start() }
                Log.d(TAG, "SerialInputOutputManager started")

                isConnected = true
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to device", e)
            }
        }

        @JavascriptInterface
        fun writeToPort(command: String) {
            try {
                val decodedCommand = String(Base64.decode(command, Base64.DEFAULT))
                Log.d(TAG, "Sending command: $decodedCommand")
                usbSerialPort?.write(decodedCommand.toByteArray(), 1000)
                Log.d(TAG, "Command sent: $command")
            } catch (e: IOException) {
                Log.e(TAG, "Error sending command", e)
            }
        }
    }
}

// Data class to represent USB device IDs
data class UsbId(val vendorId: Int, val productId: Int)