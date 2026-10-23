package com.humza.securetransmission

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.IBinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Foreground service, declared as foregroundServiceType="camera" in the
 * manifest. While this service is alive, Android requires — and this code
 * deliberately shows — a persistent, non-dismissible notification reading
 * "Secure Transmission is running". That requirement is not worked around
 * here; it is the thing that makes background camera access on this phone
 * legitimate in the first place.
 */
class CameraStreamService : LifecycleService() {

    private val channelId = "secure_transmission_channel"
    private val notificationId = 1001
    private val streamPort = 8080

    private var cameraProvider: ProcessCameraProvider? = null
    private var mjpegServer: MjpegServer? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(notificationId, notification)
        }

        try {
            // Bind on all interfaces; non-daemon so it survives while service lives.
            mjpegServer = MjpegServer(streamPort).also {
                it.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }
            android.util.Log.i("SecureTransmission", "HTTP server listening on :$streamPort")
        } catch (e: Exception) {
            android.util.Log.e("SecureTransmission", "HTTP server failed to start", e)
        }
        startCamera()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel_name),
                // LOW importance: silent, no sound/vibration, minimal visual
                // intrusion — but it cannot be set to a level that hides it,
                // since that would defeat the purpose of the requirement.
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindAnalysisUseCase()
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    private fun bindAnalysisUseCase() {
        val provider = cameraProvider ?: return

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
            try {
                val jpeg = imageProxyToJpeg(imageProxy)
                mjpegServer?.pushFrame(jpeg)
            } catch (e: Exception) {
                // drop frame on conversion error, keep stream alive
            } finally {
                imageProxy.close()
            }
        }

        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, analysis)
        } catch (e: Exception) {
            // camera bind failed; service stays alive, notification stays visible
        }
    }

    private fun imageProxyToJpeg(image: ImageProxy): ByteArray {
        // Stride-aware YUV_420_888 -> NV21 conversion (the old version ignored
        // row/pixel strides, which gives garbled/green frames on many phones).
        val w = image.width
        val h = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val nv21 = ByteArray(w * h * 3 / 2)
        var pos = 0
        for (row in 0 until h) {
            val base = row * yPlane.rowStride
            for (col in 0 until w) {
                nv21[pos++] = yBuf.get(base + col * yPlane.pixelStride)
            }
        }
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                nv21[pos++] = vBuf.get(row * vPlane.rowStride + col * vPlane.pixelStride)
                nv21[pos++] = uBuf.get(row * uPlane.rowStride + col * uPlane.pixelStride)
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
        return out.toByteArray()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        mjpegServer?.shutdownServer()
        analysisExecutor.shutdown()
    }
}
