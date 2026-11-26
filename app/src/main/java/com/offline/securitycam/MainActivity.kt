package com.offline.securitycam

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.*
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.abs

enum class AppMode { UNSELECTED, CAMERA, MONITOR }
enum class RecordingTarget { CAMERA_DEVICE, MONITOR_DEVICE }
enum class StreamQuality(val label: String, val width: Int, val height: Int, val jpegQuality: Int) {
    LOW("480p (Battery Saver)", 640, 480, 45),
    MEDIUM("720p (Balanced)", 1280, 720, 65),
    HIGH("1080p (High Detail)", 1920, 1080, 80)
}

data class CameraConfig(
    val isArmed: Boolean = true,
    val motionDetectionEnabled: Boolean = true,
    val preMotionBufferSeconds: Int = 10,
    val recordingTarget: RecordingTarget = RecordingTarget.CAMERA_DEVICE,
    val useFrontCamera: Boolean = false,
    val dualCameraEnabled: Boolean = false,
    val flashlightOn: Boolean = false,
    val nightVisionEnabled: Boolean = false,
    val micEnabled: Boolean = true,
    val quality: StreamQuality = StreamQuality.MEDIUM,
    val scheduleEnabled: Boolean = false,
    val scheduleStartHour: Int = 22,
    val scheduleStartMinute: Int = 0,
    val scheduleEndHour: Int = 6,
    val scheduleEndMinute: Int = 0,
    val isRecordingNow: Boolean = false,
    val motionDetectedNow: Boolean = false,
    val batteryLevel: Int = 100
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("isArmed", isArmed)
        put("motionDetectionEnabled", motionDetectionEnabled)
        put("preMotionBufferSeconds", preMotionBufferSeconds)
        put("recordingTarget", recordingTarget.name)
        put("useFrontCamera", useFrontCamera)
        put("dualCameraEnabled", dualCameraEnabled)
        put("flashlightOn", flashlightOn)
        put("nightVisionEnabled", nightVisionEnabled)
        put("micEnabled", micEnabled)
        put("quality", quality.name)
        put("scheduleEnabled", scheduleEnabled)
        put("scheduleStartHour", scheduleStartHour)
        put("scheduleStartMinute", scheduleStartMinute)
        put("scheduleEndHour", scheduleEndHour)
        put("scheduleEndMinute", scheduleEndMinute)
        put("isRecordingNow", isRecordingNow)
        put("motionDetectedNow", motionDetectedNow)
        put("batteryLevel", batteryLevel)
    }

    companion object {
        fun fromJson(json: JSONObject): CameraConfig = CameraConfig(
            isArmed = json.optBoolean("isArmed", true),
            motionDetectionEnabled = json.optBoolean("motionDetectionEnabled", true),
            preMotionBufferSeconds = json.optInt("preMotionBufferSeconds", 10),
            recordingTarget = RecordingTarget.valueOf(json.optString("recordingTarget", RecordingTarget.CAMERA_DEVICE.name)),
            useFrontCamera = json.optBoolean("useFrontCamera", false),
            dualCameraEnabled = json.optBoolean("dualCameraEnabled", false),
            flashlightOn = json.optBoolean("flashlightOn", false),
            nightVisionEnabled = json.optBoolean("nightVisionEnabled", false),
            micEnabled = json.optBoolean("micEnabled", true),
            quality = StreamQuality.valueOf(json.optString("quality", StreamQuality.MEDIUM.name)),
            scheduleEnabled = json.optBoolean("scheduleEnabled", false),
            scheduleStartHour = json.optInt("scheduleStartHour", 22),
            scheduleStartMinute = json.optInt("scheduleStartMinute", 0),
            scheduleEndHour = json.optInt("scheduleEndHour", 6),
            scheduleEndMinute = json.optInt("scheduleEndMinute", 0),
            isRecordingNow = json.optBoolean("isRecordingNow", false),
            motionDetectedNow = json.optBoolean("motionDetectedNow", false),
            batteryLevel = json.optInt("batteryLevel", 100)
        )
    }
}

data class RecordedFileItem(
    val name: String,
    val sizeBytes: Long,
    val timestamp: Long,
    val isLocalToMonitor: Boolean = false
)

data class DiscoveredCamera(
    val serviceName: String,
    val host: String,
    val port: Int,
    val deviceId: String,
    val isTrusted: Boolean
)

class SecurityPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("offline_sec_prefs", Context.MODE_PRIVATE)

    var appMode: AppMode
        get() = AppMode.valueOf(prefs.getString("app_mode", AppMode.UNSELECTED.name)!!)
        set(value) = prefs.edit().putString("app_mode", value.name).apply()

    val deviceId: String
        get() {
            var id = prefs.getString("device_id", null)
            if (id == null) {
                id = UUID.randomUUID().toString().take(8).uppercase()
                prefs.edit().putString("device_id", id).apply()
            }
            return id
        }

    fun saveConfig(config: CameraConfig) {
        prefs.edit().putString("camera_config", config.toJson().toString()).apply()
    }

    fun loadConfig(): CameraConfig {
        val str = prefs.getString("camera_config", null) ?: return CameraConfig()
        return try { CameraConfig.fromJson(JSONObject(str)) } catch (e: Exception) { CameraConfig() }
    }

    fun trustDevice(deviceId: String, lastKnownIp: String) {
        val trusted = getTrustedDevices().toMutableMap()
        trusted[deviceId] = lastKnownIp
        val json = JSONObject()
        trusted.forEach { (k, v) -> json.put(k, v) }
        prefs.edit().putString("trusted_devices", json.toString()).apply()
    }

    fun untrustDevice(deviceId: String) {
        val trusted = getTrustedDevices().toMutableMap()
        trusted.remove(deviceId)
        val json = JSONObject()
        trusted.forEach { (k, v) -> json.put(k, v) }
        prefs.edit().putString("trusted_devices", json.toString()).apply()
    }

    fun getTrustedDevices(): Map<String, String> {
        val str = prefs.getString("trusted_devices", "{}") ?: "{}"
        val json = JSONObject(str)
        val map = mutableMapOf<String, String>()
        json.keys().forEach { key -> map[key] = json.getString(key) }
        return map
    }

    fun isTrusted(deviceId: String): Boolean = getTrustedDevices().containsKey(deviceId)
}

data class BufferedFrame(
    val jpegBytes: ByteArray,
    val timestampMs: Long,
    val width: Int,
    val height: Int
)

class PreMotionRingBuffer {
    private val frames = ArrayDeque<BufferedFrame>()
    private val lock = Any()

    fun pushFrame(frame: BufferedFrame, maxDurationSeconds: Int) {
        synchronized(lock) {
            frames.addLast(frame)
            val cutoff = frame.timestampMs - (maxDurationSeconds * 1000L)
            while (frames.isNotEmpty() && frames.first().timestampMs < cutoff) {
                frames.removeFirst()
            }
        }
    }

    fun drainFrames(): List<BufferedFrame> {
        synchronized(lock) {
            val copy = frames.toList()
            frames.clear()
            return copy
        }
    }

    fun clear() {
        synchronized(lock) { frames.clear() }
    }
}

object SecurityStateHolder {
    val configFlow = MutableStateFlow(CameraConfig())
    val isMonitorConnected = MutableStateFlow(false)
    val connectedMonitorId = MutableStateFlow<String?>(null)
    val latestLiveFrame = MutableStateFlow<Bitmap?>(null)
    val remoteFilesList = MutableStateFlow<List<RecordedFileItem>>(emptyList())
    val connectionStatusText = MutableStateFlow("Standalone Mode (Offline Ready)")
}

class CameraNetworkServer(
    private val context: Context,
    private val prefs: SecurityPrefs,
    private val onConfigUpdatedFromRemote: (CameraConfig) -> Unit,
    private val onDownloadFileRequested: (String, DataOutputStream) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var clientOutput: DataOutputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeLock = Any()

    companion object {
        const val SERVICE_TYPE = "_offlinesec._tcp."
        const val PKT_HANDSHAKE: Byte = 0x01
        const val PKT_CONFIG_SYNC: Byte = 0x02
        const val PKT_VIDEO_FRAME: Byte = 0x03
        const val PKT_MOTION_CLIP_FRAME: Byte = 0x04
        const val PKT_FILE_LIST_REQ: Byte = 0x05
        const val PKT_FILE_LIST_RES: Byte = 0x06
        const val PKT_FILE_DOWNLOAD_REQ: Byte = 0x07
        const val PKT_FILE_DOWNLOAD_DATA: Byte = 0x08
        const val PKT_HEARTBEAT: Byte = 0x09
    }

    fun start() {
        scope.launch {
            try {
                serverSocket = ServerSocket(0).apply { reuseAddress = true }
                val localPort = serverSocket!!.localPort
                registerNsdService(localPort)
                SecurityStateHolder.connectionStatusText.value = "Listening on LAN Port $localPort"

                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    handleClientConnection(socket)
                }
            } catch (e: Exception) {
                Log.e("CameraServer", "Server loop error: ${e.message}")
            }
        }
    }

    private fun registerNsdService(port: Int) {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "SecCam_${prefs.deviceId}"
                serviceType = SERVICE_TYPE
                setPort(port)
            }
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.d("CameraServer", "NSD Registered: ${info.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e("CameraServer", "NSD error: ${e.message}")
        }
    }

    private suspend fun handleClientConnection(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 15000
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            val pktType = input.readByte()
            if (pktType != PKT_HANDSHAKE) {
                socket.close()
                return@withContext
            }
            val monitorId = input.readUTF()
            val trustRequested = input.readBoolean()

            synchronized(writeLock) {
                output.writeByte(PKT_HANDSHAKE.toInt())
                output.writeUTF(prefs.deviceId)
                output.flush()
            }

            if (trustRequested) {
                prefs.trustDevice(monitorId, socket.inetAddress.hostAddress ?: "")
            }

            clientOutput = output
            SecurityStateHolder.isMonitorConnected.value = true
            SecurityStateHolder.connectedMonitorId.value = monitorId
            SecurityStateHolder.connectionStatusText.value = "Connected to Monitor ($monitorId)"

            sendConfigSync(SecurityStateHolder.configFlow.value)

            while (!socket.isClosed) {
                val type = input.readByte()
                when (type) {
                    PKT_CONFIG_SYNC -> {
                        val jsonStr = input.readUTF()
                        val updated = CameraConfig.fromJson(JSONObject(jsonStr))
                        onConfigUpdatedFromRemote(updated)
                    }
                    PKT_FILE_LIST_REQ -> {
                        sendRecordingsList()
                    }
                    PKT_FILE_DOWNLOAD_REQ -> {
                        val fileName = input.readUTF()
                        synchronized(writeLock) {
                            onDownloadFileRequested(fileName, output)
                        }
                    }
                    PKT_HEARTBEAT -> {
                        synchronized(writeLock) {
                            output.writeByte(PKT_HEARTBEAT.toInt())
                            output.flush()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("CameraServer", "Monitor disconnected: ${e.message}")
        } finally {
            clientOutput = null
            SecurityStateHolder.isMonitorConnected.value = false
            SecurityStateHolder.connectedMonitorId.value = null
            SecurityStateHolder.connectionStatusText.value = "Standalone Mode (Auto-Recording Fallback Active)"
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun sendConfigSync(config: CameraConfig) {
        val out = clientOutput ?: return
        scope.launch {
            try {
                synchronized(writeLock) {
                    out.writeByte(PKT_CONFIG_SYNC.toInt())
                    out.writeUTF(config.toJson().toString())
                    out.flush()
                }
            } catch (e: Exception) {
                Log.e("CameraServer", "Failed config sync: ${e.message}")
            }
        }
    }

    fun streamLiveFrame(jpegBytes: ByteArray, isMotionRecordingForMonitor: Boolean) {
        val out = clientOutput ?: return
        try {
            synchronized(writeLock) {
                out.writeByte(if (isMotionRecordingForMonitor) PKT_MOTION_CLIP_FRAME.toInt() else PKT_VIDEO_FRAME.toInt())
                out.writeInt(jpegBytes.size)
                out.write(jpegBytes)
                out.flush()
            }
        } catch (_: Exception) {}
    }

    private fun sendRecordingsList() {
        val out = clientOutput ?: return
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "SecCamRecordings")
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        val arr = JSONArray()
        files.forEach { file ->
            arr.put(JSONObject().apply {
                put("name", file.name)
                put("size", file.length())
                put("time", file.lastModified())
            })
        }
        synchronized(writeLock) {
            out.writeByte(PKT_FILE_LIST_RES.toInt())
            out.writeUTF(arr.toString())
            out.flush()
        }
    }

    fun stop() {
        try {
            nsdManager?.unregisterService(registrationListener)
            serverSocket?.close()
        } catch (_: Exception) {}
        scope.cancel()
    }
}

class SecurityCameraService : LifecycleService() {

    private lateinit var prefs: SecurityPrefs
    private lateinit var networkServer: CameraNetworkServer
    private val preMotionBuffer = PreMotionRingBuffer()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraControl: CameraControl? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var previousLumaFrame: ByteArray? = null
    private var lastMotionTimestamp = 0L
    private var isCurrentlyRecordingClip = false
    private var currentClipOutputStream: FileOutputStream? = null
    private var currentClipFile: File? = null
    private var clipFrameCount = 0

    private var scheduleJob: Job? = null

    companion object {
        const val CHANNEL_ID = "offline_sec_cam_channel"
        const val NOTIF_ID = 1001
        const val ACTION_UPDATE_CONFIG = "ACTION_UPDATE_CONFIG"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        prefs = SecurityPrefs(this)
        SecurityStateHolder.configFlow.value = prefs.loadConfig()

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Security Camera Active (Screen-Off Ready)"))

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OfflineSecCam::CameraWakeLock").apply {
            acquire()
        }

        networkServer = CameraNetworkServer(
            context = this,
            prefs = prefs,
            onConfigUpdatedFromRemote = { newConfig ->
                applyNewConfig(newConfig, broadcastToMonitor = false)
            },
            onDownloadFileRequested = { fileName, outputStream ->
                streamFileToMonitor(fileName, outputStream)
            }
        )
        networkServer.start()

        startScheduleAndBatteryMonitor()
        bindCameraUseCases()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_CONFIG -> {
                val json = intent.getStringExtra("config_json")
                if (json != null) {
                    applyNewConfig(CameraConfig.fromJson(JSONObject(json)), broadcastToMonitor = true)
                }
            }
        }
        return START_STICKY
    }

    private fun applyNewConfig(newConfig: CameraConfig, broadcastToMonitor: Boolean) {
        val oldConfig = SecurityStateHolder.configFlow.value
        val battery = getBatteryPercentage()
        val finalConfig = newConfig.copy(batteryLevel = battery)

        prefs.saveConfig(finalConfig)
        SecurityStateHolder.configFlow.value = finalConfig

        try {
            cameraControl?.enableTorch(finalConfig.flashlightOn)
        } catch (_: Exception) {}

        if (oldConfig.useFrontCamera != finalConfig.useFrontCamera ||
            oldConfig.dualCameraEnabled != finalConfig.dualCameraEnabled ||
            oldConfig.quality != finalConfig.quality
        ) {
            ContextCompat.getMainExecutor(this).execute {
                bindCameraUseCases()
            }
        }

        if (broadcastToMonitor) {
            networkServer.sendConfigSync(finalConfig)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                cameraProvider?.unbindAll()

                val config = SecurityStateHolder.configFlow.value
                val lensFacing = if (config.useFrontCamera) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

                val targetRes = Size(config.quality.width, config.quality.height)

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(targetRes)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    processCameraFrame(imageProxy)
                }

                val camera = cameraProvider?.bindToLifecycle(this, cameraSelector, imageAnalysis)
                cameraControl = camera?.cameraControl
                cameraControl?.enableTorch(config.flashlightOn)

            } catch (e: Exception) {
                Log.e("SecCamService", "Camera bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processCameraFrame(imageProxy: ImageProxy) {
        try {
            val config = SecurityStateHolder.configFlow.value
            val now = System.currentTimeMillis()

            val yBuffer = imageProxy.planes[0].buffer
            val ySize = yBuffer.remaining()
            val yBytes = ByteArray(ySize)
            yBuffer.get(yBytes)

            val motionTriggered = if (config.isArmed && config.motionDetectionEnabled) {
                detectMotion(yBytes)
            } else {
                false
            }

            if (motionTriggered) {
                lastMotionTimestamp = now
            }

            val isInMotionWindow = config.isArmed && config.motionDetectionEnabled &&
                    (now - lastMotionTimestamp < 8000L)

            val jpegBytes = yuvToJpegWithNightVision(imageProxy, config.nightVisionEnabled, config.quality.jpegQuality)

            val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            SecurityStateHolder.latestLiveFrame.value = bmp

            if (config.isArmed && config.motionDetectionEnabled) {
                preMotionBuffer.pushFrame(
                    BufferedFrame(jpegBytes, now, imageProxy.width, imageProxy.height),
                    config.preMotionBufferSeconds
                )
            }

            val monitorConnected = SecurityStateHolder.isMonitorConnected.value
            val effectiveTarget = if (config.recordingTarget == RecordingTarget.MONITOR_DEVICE && monitorConnected) {
                RecordingTarget.MONITOR_DEVICE
            } else {
                RecordingTarget.CAMERA_DEVICE
            }

            if (isInMotionWindow) {
                if (!isCurrentlyRecordingClip) {
                    startMotionRecording(effectiveTarget)
                }
                writeMotionFrame(jpegBytes, effectiveTarget)
            } else if (isCurrentlyRecordingClip) {
                stopMotionRecording()
            }

            if (monitorConnected) {
                val sendAsClip = isInMotionWindow && effectiveTarget == RecordingTarget.MONITOR_DEVICE
                networkServer.streamLiveFrame(jpegBytes, sendAsClip)
            }

            if (config.isRecordingNow != isCurrentlyRecordingClip || config.motionDetectedNow != isInMotionWindow) {
                val updated = config.copy(
                    isRecordingNow = isCurrentlyRecordingClip,
                    motionDetectedNow = isInMotionWindow
                )
                SecurityStateHolder.configFlow.value = updated
                networkServer.sendConfigSync(updated)
            }

        } catch (e: Exception) {
            Log.e("SecCamService", "Frame processing error: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    private fun detectMotion(currentY: ByteArray): Boolean {
        val prev = previousLumaFrame
        previousLumaFrame = currentY
        if (prev == null || prev.size != currentY.size) return false

        var changedPixels = 0
        var sampledPixels = 0
        val threshold = 32

        var i = 0
        while (i < currentY.size) {
            val diff = abs((currentY[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF))
            if (diff > threshold) changedPixels++
            sampledPixels++
            i += 16
        }

        val changeRatio = changedPixels.toFloat() / sampledPixels.coerceAtLeast(1)
        return changeRatio > 0.035f
    }

    private fun yuvToJpegWithNightVision(image: ImageProxy, nightVision: Boolean, quality: Int): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        if (nightVision) {
            for (i in 0 until ySize) {
                val luma = (nv21[i].toInt() and 0xFF)
                val boosted = (luma * 2.4f).toInt().coerceIn(0, 255)
                nv21[i] = boosted.toByte()
            }
            for (i in ySize until nv21.size step 2) {
                nv21[i] = 75.toByte()
                nv21[i + 1] = 85.toByte()
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
        return out.toByteArray()
    }

    private fun startMotionRecording(target: RecordingTarget) {
        isCurrentlyRecordingClip = true
        clipFrameCount = 0
        val bufferedFrames = preMotionBuffer.drainFrames()

        if (target == RecordingTarget.CAMERA_DEVICE) {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "SecCamRecordings")
            if (!dir.exists()) dir.mkdirs()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            currentClipFile = File(dir, "MOTION_$timestamp.mjpeg")
            currentClipOutputStream = FileOutputStream(currentClipFile)

            bufferedFrames.forEach { frame ->
                writeMjpegFrameToFile(frame.jpegBytes)
            }
        } else {
            bufferedFrames.forEach { frame ->
                networkServer.streamLiveFrame(frame.jpegBytes, isMotionRecordingForMonitor = true)
            }
        }
    }

    private fun writeMotionFrame(jpegBytes: ByteArray, target: RecordingTarget) {
        if (target == RecordingTarget.CAMERA_DEVICE) {
            writeMjpegFrameToFile(jpegBytes)
        }
    }

    private fun writeMjpegFrameToFile(jpegBytes: ByteArray) {
        try {
            val out = currentClipOutputStream ?: return
            val header = ByteBuffer.allocate(4).putInt(jpegBytes.size).array()
            out.write(header)
            out.write(jpegBytes)
            clipFrameCount++
        } catch (e: Exception) {
            Log.e("SecCamService", "Error writing motion frame: ${e.message}")
        }
    }

    private fun stopMotionRecording() {
        isCurrentlyRecordingClip = false
        try {
            currentClipOutputStream?.flush()
            currentClipOutputStream?.close()
        } catch (_: Exception) {}
        currentClipOutputStream = null
        currentClipFile = null
    }

    private fun streamFileToMonitor(fileName: String, outputStream: DataOutputStream) {
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "SecCamRecordings")
        val file = File(dir, fileName)
        if (!file.exists()) return

        outputStream.writeByte(CameraNetworkServer.PKT_FILE_DOWNLOAD_DATA.toInt())
        outputStream.writeUTF(file.name)
        outputStream.writeLong(file.length())

        FileInputStream(file).use { fis ->
            val buffer = ByteArray(16384)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
        }
        outputStream.flush()
    }

    private fun startScheduleAndBatteryMonitor() {
        scheduleJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val cfg = SecurityStateHolder.configFlow.value
                val battery = getBatteryPercentage()
                var updated = cfg.copy(batteryLevel = battery)

                if (cfg.scheduleEnabled) {
                    val cal = Calendar.getInstance()
                    val nowMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                    val startMins = cfg.scheduleStartHour * 60 + cfg.scheduleStartMinute
                    val endMins = cfg.scheduleEndHour * 60 + cfg.scheduleEndMinute

                    val shouldBeArmed = if (startMins <= endMins) {
                        nowMins in startMins until endMins
                    } else {
                        nowMins >= startMins || nowMins < endMins
                    }

                    if (updated.isArmed != shouldBeArmed) {
                        updated = updated.copy(isArmed = shouldBeArmed)
                    }
                }

                if (updated != cfg) {
                    applyNewConfig(updated, broadcastToMonitor = true)
                }
                delay(15_000L)
            }
        }
    }

    private fun getBatteryPercentage(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Offline Security Camera Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Offline Security Guard")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scheduleJob?.cancel()
        stopMotionRecording()
        networkServer.stop()
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }
}

class MonitorNetworkClient(
    private val context: Context,
    private val prefs: SecurityPrefs
) {
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var activeSocket: Socket? = null
    private var outputStream: DataOutputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeLock = Any()

    val discoveredCameras = MutableStateFlow<List<DiscoveredCamera>>(emptyList())
    val isConnected = MutableStateFlow(false)
    val connectedCameraId = MutableStateFlow<String?>(null)
    val statusMessage = MutableStateFlow("Scanning offline LAN for Cameras...")

    private var monitorClipOut: FileOutputStream? = null
    private var lastMonitorClipFrameTime = 0L

    fun startDiscoveryAndAutoReconnect() {
        startNsdDiscovery()
        scope.launch {
            while (isActive) {
                if (!isConnected.value) {
                    val trustedMap = prefs.getTrustedDevices()
                    val matchingDiscovered = discoveredCameras.value.firstOrNull { it.isTrusted }
                    if (matchingDiscovered != null) {
                        connectToCamera(matchingDiscovered.host, matchingDiscovered.port, trustDevice = true)
                    } else if (trustedMap.isNotEmpty()) {
                        for ((devId, lastIp) in trustedMap) {
                            if (lastIp.isNotBlank() && !isConnected.value) {
                                statusMessage.value = "Waiting for Trusted Camera ($devId)..."
                            }
                        }
                    }
                } else {
                    sendHeartbeat()
                }
                delay(4000L)
            }
        }
    }

    private fun startNsdDiscovery() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}
                override fun onServiceFound(service: NsdServiceInfo) {
                    if (service.serviceType.contains("_offlinesec")) {
                        nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val host = info.host?.hostAddress ?: return
                                val port = info.port
                                val devId = info.serviceName.removePrefix("SecCam_")
                                val trusted = prefs.isTrusted(devId)

                                val entry = DiscoveredCamera(info.serviceName, host, port, devId, trusted)
                                val current = discoveredCameras.value.toMutableList()
                                current.removeAll { it.deviceId == devId }
                                current.add(entry)
                                discoveredCameras.value = current

                                if (trusted && !isConnected.value) {
                                    connectToCamera(host, port, trustDevice = true)
                                }
                            }
                        })
                    }
                }
                override fun onServiceLost(service: NsdServiceInfo) {
                    val devId = service.serviceName.removePrefix("SecCam_")
                    discoveredCameras.value = discoveredCameras.value.filterNot { it.deviceId == devId }
                }
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            nsdManager?.discoverServices(CameraNetworkServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e("MonitorClient", "Discovery error: ${e.message}")
        }
    }

    fun connectToCamera(host: String, port: Int, trustDevice: Boolean) {
        scope.launch {
            if (isConnected.value) return@launch
            try {
                statusMessage.value = "Connecting to $host:$port..."
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.tcpNoDelay = true
                socket.soTimeout = 15000

                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

                out.writeByte(CameraNetworkServer.PKT_HANDSHAKE.toInt())
                out.writeUTF(prefs.deviceId)
                out.writeBoolean(trustDevice)
                out.flush()

                val replyType = input.readByte()
                if (replyType != CameraNetworkServer.PKT_HANDSHAKE) {
                    socket.close()
                    return@launch
                }
                val camId = input.readUTF()
                if (trustDevice) {
                    prefs.trustDevice(camId, host)
                }

                activeSocket = socket
                outputStream = out
                isConnected.value = true
                connectedCameraId.value = camId
                statusMessage.value = "Connected to Camera ($camId)"

                requestRemoteFiles()

                while (!socket.isClosed) {
                    val pkt = input.readByte()
                    when (pkt) {
                        CameraNetworkServer.PKT_CONFIG_SYNC -> {
                            val jsonStr = input.readUTF()
                            SecurityStateHolder.configFlow.value = CameraConfig.fromJson(JSONObject(jsonStr))
                        }
                        CameraNetworkServer.PKT_VIDEO_FRAME -> {
                            val len = input.readInt()
                            val bytes = ByteArray(len)
                            input.readFully(bytes)
                            closeMonitorLocalClipIfIdle()
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, len)
                            SecurityStateHolder.latestLiveFrame.value = bmp
                        }
                        CameraNetworkServer.PKT_MOTION_CLIP_FRAME -> {
                            val len = input.readInt()
                            val bytes = ByteArray(len)
                            input.readFully(bytes)
                            saveMotionFrameLocallyOnMonitor(bytes)
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, len)
                            SecurityStateHolder.latestLiveFrame.value = bmp
                        }
                        CameraNetworkServer.PKT_FILE_LIST_RES -> {
                            val arr = JSONArray(input.readUTF())
                            val list = mutableListOf<RecordedFileItem>()
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                list.add(
                                    RecordedFileItem(
                                        name = obj.getString("name"),
                                        sizeBytes = obj.getLong("size"),
                                        timestamp = obj.getLong("time"),
                                        isLocalToMonitor = false
                                    )
                                )
                            }
                            SecurityStateHolder.remoteFilesList.value = list
                        }
                        CameraNetworkServer.PKT_FILE_DOWNLOAD_DATA -> {
                            val fileName = input.readUTF()
                            val fileSize = input.readLong()
                            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "MonitorDownloads")
                            if (!dir.exists()) dir.mkdirs()
                            val outFile = File(dir, fileName)
                            FileOutputStream(outFile).use { fos ->
                                var remaining = fileSize
                                val buf = ByteArray(16384)
                                while (remaining > 0) {
                                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                                    val read = input.read(buf, 0, toRead)
                                    if (read == -1) break
                                    fos.write(buf, 0, read)
                                    remaining -= read
                                }
                            }
                            statusMessage.value = "Downloaded $fileName wirelessly!"
                        }
                        CameraNetworkServer.PKT_HEARTBEAT -> { }
                    }
                }
            } catch (e: Exception) {
                Log.w("MonitorClient", "Connection lost: ${e.message}")
            } finally {
                closeMonitorLocalClipIfIdle(force = true)
                isConnected.value = false
                connectedCameraId.value = null
                statusMessage.value = "Disconnected. Auto-reconnecting..."
                try { activeSocket?.close() } catch (_: Exception) {}
                activeSocket = null
                outputStream = null
            }
        }
    }

    private fun saveMotionFrameLocallyOnMonitor(jpegBytes: ByteArray) {
        try {
            val now = System.currentTimeMillis()
            if (monitorClipOut == null) {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "MonitorLocalRecordings")
                if (!dir.exists()) dir.mkdirs()
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                monitorClipOut = FileOutputStream(File(dir, "MONITOR_CLIP_$ts.mjpeg"))
            }
            val header = ByteBuffer.allocate(4).putInt(jpegBytes.size).array()
            monitorClipOut?.write(header)
            monitorClipOut?.write(jpegBytes)
            lastMonitorClipFrameTime = now
        } catch (e: Exception) {
            Log.e("MonitorClient", "Local clip write error: ${e.message}")
        }
    }

    private fun closeMonitorLocalClipIfIdle(force: Boolean = false) {
        if (monitorClipOut != null && (force || System.currentTimeMillis() - lastMonitorClipFrameTime > 2000L)) {
            try {
                monitorClipOut?.flush()
                monitorClipOut?.close()
            } catch (_: Exception) {}
            monitorClipOut = null
        }
    }

    fun sendConfigUpdate(newConfig: CameraConfig) {
        SecurityStateHolder.configFlow.value = newConfig
        val out = outputStream ?: return
        scope.launch {
            try {
                synchronized(writeLock) {
                    out.writeByte(CameraNetworkServer.PKT_CONFIG_SYNC.toInt())
                    out.writeUTF(newConfig.toJson().toString())
                    out.flush()
                }
            } catch (e: Exception) {
                Log.e("MonitorClient", "Config send failed: ${e.message}")
            }
        }
    }

    fun requestRemoteFiles() {
        val out = outputStream ?: return
        scope.launch {
            try {
                synchronized(writeLock) {
                    out.writeByte(CameraNetworkServer.PKT_FILE_LIST_REQ.toInt())
                    out.flush()
                }
            } catch (_: Exception) {}
        }
    }

    fun downloadRemoteFile(fileName: String) {
        val out = outputStream ?: return
        scope.launch {
            try {
                statusMessage.value = "Downloading $fileName..."
                synchronized(writeLock) {
                    out.writeByte(CameraNetworkServer.PKT_FILE_DOWNLOAD_REQ.toInt())
                    out.writeUTF(fileName)
                    out.flush()
                }
            } catch (_: Exception) {}
        }
    }

    private fun sendHeartbeat() {
        val out = outputStream ?: return
        try {
            synchronized(writeLock) {
                out.writeByte(CameraNetworkServer.PKT_HEARTBEAT.toInt())
                out.flush()
            }
        } catch (_: Exception) {
            try { activeSocket?.close() } catch (_: Exception) {}
        }
    }

    fun disconnectAndForget(deviceId: String) {
        prefs.untrustDevice(deviceId)
        try { activeSocket?.close() } catch (_: Exception) {}
    }

    fun stop() {
        try {
            nsdManager?.stopServiceDiscovery(discoveryListener)
            activeSocket?.close()
        } catch (_: Exception) {}
        scope.cancel()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF38BDF8),
                secondary = Color(0xFF34D399),
                background = Color(0xFF0F172A),
                surface = Color(0xFF1E293B),
                onBackground = Color.White,
                onSurface = Color.White
            )) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SecurityAppRoot()
                }
            }
        }
    }
}

@Composable
fun SecurityAppRoot() {
    val context = LocalContext.current
    val prefs = remember { SecurityPrefs(context) }
    var selectedMode by remember { mutableStateOf(prefs.appMode) }

    val requiredPermissions = remember {
        mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    var permissionsGranted by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
            launcher.launch(requiredPermissions)
        }
    }

    if (!permissionsGranted) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("Permissions Required", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Camera, Microphone, and Notification access are required for offline security monitoring and background operation.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.LightGray,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            Button(onClick = { launcher.launch(requiredPermissions) }) {
                Text("Grant Permissions")
            }
        }
        return
    }

    when (selectedMode) {
        AppMode.UNSELECTED -> ModeSelectionScreen(
            deviceId = prefs.deviceId,
            onSelectMode = { mode ->
                prefs.appMode = mode
                selectedMode = mode
            }
        )
        AppMode.CAMERA -> CameraDeviceScreen(
            prefs = prefs,
            onSwitchRole = {
                context.startService(Intent(context, SecurityCameraService::class.java).apply {
                    action = SecurityCameraService.ACTION_STOP_SERVICE
                })
                prefs.appMode = AppMode.UNSELECTED
                selectedMode = AppMode.UNSELECTED
            }
        )
        AppMode.MONITOR -> MonitorDeviceScreen(
            prefs = prefs,
            onSwitchRole = {
                prefs.appMode = AppMode.UNSELECTED
                selectedMode = AppMode.UNSELECTED
            }
        )
    }
}

@Composable
fun ModeSelectionScreen(deviceId: String, onSelectMode: (AppMode) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("Offline Security System", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Device ID: $deviceId • 100% Offline LAN", color = Color(0xFF94A3B8), fontSize = 14.sp)
        Spacer(Modifier.height(36.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectMode(AppMode.CAMERA) },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Videocam, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Camera Device", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Turn this phone into an autonomous security camera. Works with screen off & auto-records on motion.",
                        fontSize = 13.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectMode(AppMode.MONITOR) },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tv, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Monitor Device", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "View live stream, control all camera settings remotely, and download wireless recordings.",
                        fontSize = 13.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        }
    }
}

@Composable
fun CameraDeviceScreen(prefs: SecurityPrefs, onSwitchRole: () -> Unit) {
    val context = LocalContext.current
    val config by SecurityStateHolder.configFlow.collectAsState()
    val isMonitorConnected by SecurityStateHolder.isMonitorConnected.collectAsState()
    val statusText by SecurityStateHolder.connectionStatusText.collectAsState()
    val liveFrame by SecurityStateHolder.latestLiveFrame.collectAsState()
    var blackScreenSaver by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val intent = Intent(context, SecurityCameraService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun updateLocalConfig(updated: CameraConfig) {
        val intent = Intent(context, SecurityCameraService::class.java).apply {
            action = SecurityCameraService.ACTION_UPDATE_CONFIG
            putExtra("config_json", updated.toJson().toString())
        }
        context.startService(intent)
    }

    if (blackScreenSaver) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { blackScreenSaver = false },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("POWER SAVER ACTIVE", color = Color(0xFF334155), fontSize = 12.sp)
                Text("Tap anywhere to wake UI", color = Color(0xFF1E293B), fontSize = 11.sp)
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("CAMERA NODE (${prefs.deviceId})", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(statusText, fontSize = 12.sp, color = if (isMonitorConnected) Color(0xFF34D399) else Color(0xFF38BDF8))
            }
            TextButton(onClick = onSwitchRole) {
                Text("Change Mode")
            }
        }

        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (liveFrame != null) {
                Image(
                    bitmap = liveFrame!!.asImageBitmap(),
                    contentDescription = "Camera Feed",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("Initializing Camera Sensor...", color = Color.Gray)
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusPill(
                    text = if (config.isArmed) "ARMED" else "DISARMED",
                    color = if (config.isArmed) Color(0xFFEF4444) else Color(0xFF64748B)
                )
                if (config.isRecordingNow) {
                    StatusPill(text = "REC MOTION", color = Color(0xFFDC2626))
                }
                StatusPill(text = "BAT ${config.batteryLevel}%", color = Color(0xFF0F172A))
            }
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { blackScreenSaver = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
        ) {
            Icon(Icons.Default.Bedtime, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("OLED Screen Off (Keep Recording in Background)")
        }

        Spacer(Modifier.height(16.dp))

        ControlPanelSection(config = config, onConfigChange = { updateLocalConfig(it) })
    }
}

@Composable
fun MonitorDeviceScreen(prefs: SecurityPrefs, onSwitchRole: () -> Unit) {
    val context = LocalContext.current
    val client = remember { MonitorNetworkClient(context, prefs) }
    val discoveredCameras by client.discoveredCameras.collectAsState()
    val isConnected by client.isConnected.collectAsState()
    val connectedCamId by client.connectedCameraId.collectAsState()
    val statusMsg by client.statusMessage.collectAsState()
    val liveFrame by SecurityStateHolder.latestLiveFrame.collectAsState()
    val config by SecurityStateHolder.configFlow.collectAsState()
    val remoteFiles by SecurityStateHolder.remoteFilesList.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        client.startDiscoveryAndAutoReconnect()
        onDispose { client.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("MONITOR STATION", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(statusMsg, fontSize = 12.sp, color = if (isConnected) Color(0xFF34D399) else Color(0xFFFBBF24))
            }
            TextButton(onClick = onSwitchRole) {
                Text("Change Mode")
            }
        }

        if (!isConnected) {
            Spacer(Modifier.height(16.dp))
            Text("Available Offline Cameras on LAN:", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            if (discoveredCameras.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                ) {
                    Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Searching local Wi-Fi / Hotspot for Camera devices...", fontSize = 13.sp, color = Color.LightGray)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(discoveredCameras) { cam ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Camera ${cam.deviceId}", fontWeight = FontWeight.Bold)
                                    Text("${cam.host}:${cam.port}", fontSize = 12.sp, color = Color.Gray)
                                    if (cam.isTrusted) {
                                        Text("★ Trusted Device (Auto-Reconnect)", fontSize = 11.sp, color = Color(0xFF34D399))
                                    }
                                }
                                Button(onClick = { client.connectToCamera(cam.host, cam.port, trustDevice = true) }) {
                                    Text("Connect & Trust")
                                }
                            }
                        }
                    }
                }
            }
            return
        }

        Spacer(Modifier.height(8.dp))
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Live & Controls") })
            Tab(selected = selectedTab == 1, onClick = {
                selectedTab = 1
                client.requestRemoteFiles()
            }, text = { Text("Recordings (${remoteFiles.size})") })
        }

        Spacer(Modifier.height(12.dp))

        if (selectedTab == 0) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (liveFrame != null) {
                        Image(
                            bitmap = liveFrame!!.asImageBitmap(),
                            contentDescription = "Live Camera Stream",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text("Waiting for video stream...", color = Color.Gray)
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusPill("LIVE", Color(0xFF10B981))
                        if (config.isRecordingNow) {
                            StatusPill("MOTION REC", Color(0xFFEF4444))
                        }
                        StatusPill("CAM BAT ${config.batteryLevel}%", Color(0xFF0F172A))
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Trusted Camera: $connectedCamId", fontSize = 13.sp, color = Color(0xFF34D399))
                    TextButton(onClick = { connectedCamId?.let { client.disconnectAndForget(it) } }) {
                        Text("Untrust Device", color = Color(0xFFF87171), fontSize = 12.sp)
                    }
                }

                ControlPanelSection(
                    config = config,
                    onConfigChange = { client.sendConfigUpdate(it) }
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Camera Motion Clips", fontWeight = FontWeight.Bold)
                IconButton(onClick = { client.requestRemoteFiles() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Files")
                }
            }

            if (remoteFiles.isEmpty()) {
                Text("No motion recordings found on camera yet.", color = Color.Gray, modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(remoteFiles) { file ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(file.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text("${file.sizeBytes / 1024} KB", fontSize = 12.sp, color = Color.Gray)
                                }
                                Button(onClick = { client.downloadRemoteFile(file.name) }) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Save")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ControlPanelSection(config: CameraConfig, onConfigChange: (CameraConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingSwitchRow(
                    title = "System Armed",
                    subtitle = "Enable security monitoring & triggers",
                    checked = config.isArmed,
                    onCheckedChange = { onConfigChange(config.copy(isArmed = it)) }
                )
                HorizontalDivider(color = Color(0xFF334155))
                SettingSwitchRow(
                    title = "Motion Detection Recording",
                    subtitle = "Record only when movement is detected",
                    checked = config.motionDetectionEnabled,
                    onCheckedChange = { onConfigChange(config.copy(motionDetectionEnabled = it)) }
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Pre-Motion Recording Buffer", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Captures footage before motion starts", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 30).forEach { sec ->
                        FilterChip(
                            selected = config.preMotionBufferSeconds == sec,
                            onClick = { onConfigChange(config.copy(preMotionBufferSeconds = sec)) },
                            label = { Text("${sec}s") }
                        )
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Recording Storage Target", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Auto-falls back to Camera if Monitor disconnects", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = config.recordingTarget == RecordingTarget.CAMERA_DEVICE,
                        onClick = { onConfigChange(config.copy(recordingTarget = RecordingTarget.CAMERA_DEVICE)) },
                        label = { Text("Camera Storage") }
                    )
                    FilterChip(
                        selected = config.recordingTarget == RecordingTarget.MONITOR_DEVICE,
                        onClick = { onConfigChange(config.copy(recordingTarget = RecordingTarget.MONITOR_DEVICE)) },
                        label = { Text("Monitor Storage") }
                    )
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Hardware & Optics", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    FilterChip(
                        selected = config.useFrontCamera,
                        onClick = { onConfigChange(config.copy(useFrontCamera = !config.useFrontCamera)) },
                        label = { Text(if (config.useFrontCamera) "Front Cam" else "Rear Cam") }
                    )
                    FilterChip(
                        selected = config.dualCameraEnabled,
                        onClick = { onConfigChange(config.copy(dualCameraEnabled = !config.dualCameraEnabled)) },
                        label = { Text("Dual Cam") }
                    )
                    FilterChip(
                        selected = config.flashlightOn,
                        onClick = { onConfigChange(config.copy(flashlightOn = !config.flashlightOn)) },
                        label = { Text("Flashlight") }
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = config.nightVisionEnabled,
                        onClick = { onConfigChange(config.copy(nightVisionEnabled = !config.nightVisionEnabled)) },
                        label = { Text("Night Vision") }
                    )
                    FilterChip(
                        selected = config.micEnabled,
                        onClick = { onConfigChange(config.copy(micEnabled = !config.micEnabled)) },
                        label = { Text(if (config.micEnabled) "Mic ON" else "Mic Muted") }
                    )
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Stream & Recording Quality", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StreamQuality.entries.forEach { q ->
                        FilterChip(
                            selected = config.quality == q,
                            onClick = { onConfigChange(config.copy(quality = q)) },
                            label = { Text(q.label) }
                        )
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
            Column(modifier = Modifier.padding(14.dp)) {
                SettingSwitchRow(
                    title = "Scheduled Auto-Arm / Disarm",
                    subtitle = "Automatically arms from ${String.format("%02d:%02d", config.scheduleStartHour, config.scheduleStartMinute)} to ${String.format("%02d:%02d", config.scheduleEndHour, config.scheduleEndMinute)}",
                    checked = config.scheduleEnabled,
                    onCheckedChange = { onConfigChange(config.copy(scheduleEnabled = it)) }
                )
            }
        }
    }
}

@Composable
fun SettingSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, fontSize = 12.sp, color = Color.Gray)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun StatusPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.85f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
