package com.humza.securetransmission

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "secure_transmission_prefs"
        const val KEY_URL = "relay_url"
        const val KEY_KEY = "relay_key"
    }

    private lateinit var relayUrlInput: android.widget.EditText
    private lateinit var relayKeyInput: android.widget.EditText

    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private lateinit var urlText: TextView
    private lateinit var toggleButton: Button

    private var isStreaming = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusPoller = object : Runnable {
        override fun run() {
            if (isStreaming) {
                statusText.text = CameraStreamService.status + "\n" + CameraStreamService.relayStatus
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cameraGranted = results[Manifest.permission.CAMERA] == true
        if (cameraGranted) {
            startStreamingService()
        } else {
            statusText.text = "Camera permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        ipText = findViewById(R.id.ipText)
        urlText = findViewById(R.id.urlText)
        toggleButton = findViewById(R.id.toggleButton)
        relayUrlInput = findViewById(R.id.relayUrlInput)
        relayKeyInput = findViewById(R.id.relayKeyInput)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        relayUrlInput.setText(prefs.getString(KEY_URL, ""))
        relayKeyInput.setText(prefs.getString(KEY_KEY, ""))
        val baked = BuildConfig.RELAY_URL.isNotBlank() && BuildConfig.STREAM_KEY.isNotBlank()
        if (baked) {
            // Relay details are built into the APK, so hide the input boxes.
            relayUrlInput.visibility = android.view.View.GONE
            relayKeyInput.visibility = android.view.View.GONE
        }

        updateIpDisplay()
        handler.post(statusPoller)

        // Auto-start: if the relay is built in and camera permission is already
        // granted, begin streaming as soon as the app opens (no tapping needed).
        if (baked && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startStreamingService()
        }

        toggleButton.setOnClickListener {
            if (isStreaming) {
                stopStreamingService()
            } else {
                requestPermissionsAndStart()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(statusPoller)
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startStreamingService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startStreamingService() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_URL, relayUrlInput.text.toString().trim())
            .putString(KEY_KEY, relayKeyInput.text.toString().trim())
            .apply()
        val intent = Intent(this, CameraStreamService::class.java)
        ContextCompat.startForegroundService(this, intent)
        isStreaming = true
        CameraStreamService.status = "Starting…"
        statusText.text = "Starting…"
        toggleButton.text = getString(R.string.stop_streaming)
        updateIpDisplay()
    }

    private fun stopStreamingService() {
        val intent = Intent(this, CameraStreamService::class.java)
        stopService(intent)
        isStreaming = false
        statusText.text = "Stopped"
        toggleButton.text = getString(R.string.start_streaming)
    }

    private fun updateIpDisplay() {
        val ip = getDeviceIpAddress()
        if (ip != null) {
            ipText.text = "Device IP: $ip"
            urlText.text = "Stream URL: http://$ip:8080/stream"
        } else {
            ipText.text = "Device IP: not found — check network connection"
            urlText.text = "Stream URL: —"
        }
    }

    /**
     * Returns the first non-loopback IPv4 address found on any active interface
     * (WiFi, mobile data, or a Tailscale tunnel interface if Tailscale is installed
     * and running). On mobile data alone this address is usually behind carrier
     * NAT and NOT reachable from your laptop — install Tailscale on both devices
     * so each gets a stable, routable 100.x address instead. See README.md.
     */
    private fun getDeviceIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // Prefer a Tailscale interface (commonly named "tailscale0") if present.
            val sorted = interfaces.sortedByDescending { it.name.contains("tailscale") }
            for (intf in sorted) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            // fall through
        }
        return null
    }
}
