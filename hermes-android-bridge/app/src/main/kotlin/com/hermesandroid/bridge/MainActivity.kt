package com.hermesandroid.bridge

import android.app.Activity
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.BuildConfig
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.media.ScreenRecorder
import com.hermesandroid.bridge.overlay.StatusOverlay
import com.hermesandroid.bridge.permission.BridgePermissions
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import com.hermesandroid.bridge.usb.UsbDeviceManager
import java.net.NetworkInterface

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_SCREEN_RECORD = 1001
        private const val REQUEST_CODE_DEVICE_PERMISSIONS = 1002
    }

    private lateinit var tvA11yStatus: TextView
    private lateinit var tvServerStatus: TextView
    private lateinit var tvRelayAddr: TextView
    private lateinit var tvAuthCode: TextView
    private lateinit var indicatorA11y: View
    private lateinit var indicatorServer: View
    private lateinit var indicatorRelay: View
    private lateinit var indicatorAuth: View
    private lateinit var switchAccessibility: Switch
    private lateinit var switchOverlay: Switch
    private lateinit var switchScreenRecord: Switch
    private lateinit var tvDevicePermissions: TextView
    private lateinit var btnDevicePermissions: Button
    private lateinit var tvUsbStatus: TextView
    private lateinit var btnUsbAccess: Button
    private lateinit var tvPairingCode: TextView
    private lateinit var btnRegenerate: Button
    private lateinit var etServerUrl: EditText
    private lateinit var tvRelayStatus: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var tvAddress: TextView
    private lateinit var tvVersion: TextView
    private val usbStatusListener: () -> Unit = {
        runOnUiThread { updateUsbStatus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvA11yStatus = findViewById(R.id.tvA11yStatus)
        tvServerStatus = findViewById(R.id.tvServerStatus)
        tvRelayAddr = findViewById(R.id.tvRelayAddr)
        tvAuthCode = findViewById(R.id.tvAuthCode)
        indicatorA11y = findViewById(R.id.indicatorA11y)
        indicatorServer = findViewById(R.id.indicatorServer)
        indicatorRelay = findViewById(R.id.indicatorRelay)
        indicatorAuth = findViewById(R.id.indicatorAuth)
        switchAccessibility = findViewById(R.id.switchAccessibility)
        switchOverlay = findViewById(R.id.switchOverlay)
        switchScreenRecord = findViewById(R.id.switchScreenRecord)
        tvDevicePermissions = findViewById(R.id.tvDevicePermissions)
        btnDevicePermissions = findViewById(R.id.btnDevicePermissions)
        tvUsbStatus = findViewById(R.id.tvUsbStatus)
        btnUsbAccess = findViewById(R.id.btnUsbAccess)
        tvPairingCode = findViewById(R.id.tvPairingCode)
        btnRegenerate = findViewById(R.id.btnRegenerate)
        etServerUrl = findViewById(R.id.etServerUrl)
        tvRelayStatus = findViewById(R.id.tvRelayStatus)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        tvAddress = findViewById(R.id.tvAddress)
        tvVersion = findViewById(R.id.tvVersion)
        tvVersion.text = "v${BuildConfig.VERSION_NAME}"

        setupPairingCode()
        setupPermissions()
        setupUsbAccess()
        setupRelayConnection()

        UsbDeviceManager.addListener(usbStatusListener)

        updateConnectionInfo()
        updateStatus()
    }

    override fun onDestroy() {
        UsbDeviceManager.removeListener(usbStatusListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updatePermissionSwitches()
        updateDevicePermissionStatus()
        updateUsbStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_DEVICE_PERMISSIONS) return

        updateDevicePermissionStatus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            openAllFilesAccessSettings()
        } else {
            showPermissionResult()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SCREEN_RECORD) {
            if (resultCode == RESULT_OK && data != null) {
                val service = BridgeAccessibilityService.instance
                if (service == null) {
                    Toast.makeText(this, "Enable Accessibility Service before screen recording", Toast.LENGTH_LONG).show()
                } else {
                    ScreenRecorder.setProjectionPermission(resultCode, data)
                    Toast.makeText(this, "Screen recording permission granted", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Screen recording permission denied", Toast.LENGTH_SHORT).show()
            }
            updatePermissionSwitches()
        }
    }

    private fun setupPairingCode() {
        tvPairingCode.text = PairingManager.getCode()

        btnRegenerate.setOnClickListener {
            PairingManager.regenerateCode()
            tvPairingCode.text = PairingManager.getCode()
            updateStatus()
            Toast.makeText(this, "New pairing code generated", Toast.LENGTH_SHORT).show()
        }

        tvPairingCode.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Hermes pairing code", PairingManager.getCode()))
            Toast.makeText(this, "Pairing code copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPermissions() {
        switchAccessibility.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && BridgeAccessibilityService.instance == null) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        switchOverlay.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ))
                } else {
                    StatusOverlay.show(this)
                }
            } else {
                StatusOverlay.hide(this)
            }
        }

        switchScreenRecord.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !ScreenRecorder.hasPermission()) {
                val service = BridgeAccessibilityService.instance
                if (service == null) {
                    Toast.makeText(this, "Enable Accessibility Service before screen recording", Toast.LENGTH_LONG).show()
                    updatePermissionSwitches()
                } else {
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_RECORD)
                }
            }
        }

        btnDevicePermissions.setOnClickListener {
            requestDevicePermissions()
        }
    }

    private fun setupUsbAccess() {
        btnUsbAccess.setOnClickListener {
            val devices = UsbDeviceManager.listDevices()["devices"] as? List<*>
            val firstMissing = devices
                ?.mapNotNull { it as? Map<*, *> }
                ?.firstOrNull { it["permissionGranted"] != true }
            val deviceId = firstMissing?.get("deviceId") as? Int

            when {
                devices.isNullOrEmpty() -> Toast.makeText(
                    this,
                    "Connect a USB device through a USB-C OTG adapter",
                    Toast.LENGTH_LONG,
                ).show()

                deviceId == null -> Toast.makeText(
                    this,
                    "USB device access is already granted",
                    Toast.LENGTH_SHORT,
                ).show()

                else -> {
                    val result = UsbDeviceManager.requestPermission(deviceId)
                    if (result.status >= 400) {
                        Toast.makeText(
                            this,
                            result.body["error"]?.toString() ?: "Could not request USB access",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
        updateUsbStatus()
    }

    private fun updateUsbStatus() {
        val attached = UsbDeviceManager.attachedDeviceCount()
        val permitted = UsbDeviceManager.permittedDeviceCount()
        val open = UsbDeviceManager.connectionCount()

        tvUsbStatus.text = when {
            attached == 0 -> "No USB devices · connect with USB-C OTG"
            open > 0 -> "$attached attached · $permitted authorized · $open open"
            else -> "$attached attached · $permitted authorized"
        }
        tvUsbStatus.setTextColor(
            when {
                open > 0 -> getColor(R.color.glass_success)
                attached > 0 -> getColor(R.color.glass_warning)
                else -> getColor(R.color.glass_text_tertiary)
            },
        )
        btnUsbAccess.text = when {
            attached == 0 -> "USB device access"
            permitted < attached -> "Authorize USB device"
            else -> "USB access granted"
        }
        btnUsbAccess.isEnabled = attached == 0 || permitted < attached
    }

    private fun requestDevicePermissions() {
        val missing = BridgePermissions.runtimePermissionsForSdk(Build.VERSION.SDK_INT)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_CODE_DEVICE_PERMISSIONS)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            openAllFilesAccessSettings()
        } else {
            Toast.makeText(this, "Device access is already granted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAllFilesAccessSettings() {
        val appSettings = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        try {
            startActivity(appSettings)
        } catch (_: RuntimeException) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun updateDevicePermissionStatus() {
        val runtimePermissions = BridgePermissions.runtimePermissionsForSdk(Build.VERSION.SDK_INT)
        val grantedRuntime = runtimePermissions.count {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        val needsAllFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val allFilesGranted = !needsAllFiles || Environment.isExternalStorageManager()
        val total = runtimePermissions.size + if (needsAllFiles) 1 else 0
        val granted = grantedRuntime + if (allFilesGranted && needsAllFiles) 1 else 0

        tvDevicePermissions.text = "$granted/$total granted · camera, mic, location, files, contacts, calls, SMS"
        tvDevicePermissions.setTextColor(
            if (granted == total) {
                getColor(R.color.glass_success)
            } else {
                getColor(R.color.glass_text_tertiary)
            },
        )
        btnDevicePermissions.text = when {
            granted == total -> "Device access granted"
            grantedRuntime == runtimePermissions.size -> "Allow all files"
            else -> "Grant device access"
        }
        btnDevicePermissions.isEnabled = granted != total
    }

    private fun showPermissionResult() {
        val missingCount = BridgePermissions.runtimePermissionsForSdk(Build.VERSION.SDK_INT)
            .count { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        val message = if (missingCount == 0) {
            "Device access granted"
        } else {
            "$missingCount permissions remain off; tap Device Access again to retry"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun updatePermissionSwitches() {
        switchAccessibility.setOnCheckedChangeListener(null)
        switchOverlay.setOnCheckedChangeListener(null)
        switchScreenRecord.setOnCheckedChangeListener(null)

        switchAccessibility.isChecked = BridgeAccessibilityService.instance != null
        switchOverlay.isChecked = Settings.canDrawOverlays(this)
        switchScreenRecord.isChecked = ScreenRecorder.hasPermission()

        setupPermissions()
        updateDevicePermissionStatus()
    }

    private fun setupRelayConnection() {
        val savedUrl = RelayClient.serverUrl
        if (!savedUrl.isNullOrBlank()) {
            etServerUrl.setText(savedUrl)
        }

        RelayClient.onStatusChanged = { connected, message ->
            tvRelayStatus.text = message
            tvRelayStatus.setTextColor(
                if (connected) {
                    getColor(R.color.glass_success)
                } else {
                    getColor(R.color.glass_text_tertiary)
                }
            )
            btnDisconnect.visibility = if (connected || RelayClient.isConnected) View.VISIBLE else View.GONE
            btnConnect.text = if (RelayClient.isConnected) "Connected" else "Connect"
            btnConnect.background = getDrawable(
                if (RelayClient.isConnected) R.drawable.bg_input_dark else R.drawable.bg_button_orange
            )
            btnConnect.setTextColor(
                if (RelayClient.isConnected) {
                    getColor(R.color.glass_success)
                } else {
                    getColor(R.color.glass_on_accent)
                }
            )
            updateStatus()
        }

        btnConnect.setOnClickListener {
            if (RelayClient.isConnected) return@setOnClickListener
            val url = etServerUrl.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, "Enter a server URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val code = PairingManager.getCode()
            RelayClient.connect(url, code)
        }

        btnDisconnect.setOnClickListener {
            RelayClient.disconnect()
            btnDisconnect.visibility = View.GONE
            btnConnect.text = "Connect"
            btnConnect.background = getDrawable(R.drawable.bg_button_orange)
            btnConnect.setTextColor(getColor(R.color.glass_on_accent))
            updateStatus()
        }

        updateRelayButton()
    }

    private fun updateRelayButton() {
        if (RelayClient.isConnected) {
            btnDisconnect.visibility = View.VISIBLE
            btnConnect.text = "Connected"
            btnConnect.background = getDrawable(R.drawable.bg_input_dark)
            btnConnect.setTextColor(getColor(R.color.glass_success))
            tvRelayStatus.text = "Connected to ${RelayClient.serverUrl}"
            tvRelayStatus.setTextColor(getColor(R.color.glass_success))
        } else {
            btnDisconnect.visibility = View.GONE
            btnConnect.text = "Connect"
            btnConnect.background = getDrawable(R.drawable.bg_button_orange)
            btnConnect.setTextColor(getColor(R.color.glass_on_accent))
        }
    }

    private fun updateConnectionInfo() {
        val ip = getLocalIpAddress()
        tvAddress.text = "http://$ip:8765 (USB/LAN)"
    }

    private fun updateStatus() {
        val serviceRunning = BridgeAccessibilityService.instance != null
        val relayConnected = RelayClient.isConnected

        tvA11yStatus.text = if (serviceRunning) "active" else "inactive"
        tvA11yStatus.setTextColor(
            if (serviceRunning) {
                getColor(R.color.glass_success)
            } else {
                getColor(R.color.glass_text_tertiary)
            },
        )
        indicatorA11y.setBackgroundResource(
            if (serviceRunning) R.drawable.bg_status_dot_green else R.drawable.bg_status_dot_grey
        )

        tvServerStatus.text = "8765"
        tvServerStatus.setTextColor(getColor(R.color.glass_success))

        if (relayConnected) {
            tvRelayAddr.text = RelayClient.serverUrl
            tvRelayAddr.setTextColor(getColor(R.color.glass_success))
            indicatorRelay.setBackgroundResource(R.drawable.bg_status_dot_green)
        } else if (!RelayClient.serverUrl.isNullOrBlank()) {
            tvRelayAddr.text = "disconnected"
            tvRelayAddr.setTextColor(getColor(R.color.glass_danger))
            indicatorRelay.setBackgroundResource(R.drawable.bg_status_dot_red)
        } else {
            tvRelayAddr.text = "—"
            tvRelayAddr.setTextColor(getColor(R.color.glass_text_tertiary))
            indicatorRelay.setBackgroundResource(R.drawable.bg_status_dot_grey)
        }

        tvAuthCode.text = PairingManager.getCode()
        tvAuthCode.setTextColor(getColor(R.color.glass_success))
    }

    private fun getLocalIpAddress(): String {
        return NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress ?: "localhost"
    }
}
