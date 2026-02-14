package com.example.phone_phone_camera_transform // 【保持你的包名】

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.*
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private val VIDEO_PORT = 6677
    private val AUDIO_PORT = 6678
    private val TALK_PORT = 6679

    private var videoServerSocket: ServerSocket? = null
    private var videoOutputStream: DataOutputStream? = null

    private var isAudioRunning = false
    private var isTalkRunning = false

    @Volatile private var isStreaming = false
    private var isFlashOn = false

    private lateinit var ipTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val rootLayout = FrameLayout(this)
        setContentView(rootLayout)

        ipTextView = TextView(this).apply {
            text = "正在获取 IP..."
            textSize = 24f
            setTextColor(Color.GREEN)
            setShadowLayer(5f, 2f, 2f, Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                topMargin = 150
            }
        }
        rootLayout.addView(ipTextView)

        val ip = getIpAddress()
        ipTextView.text = "本机 IP: $ip\n端口: 6677"

        cameraExecutor = Executors.newSingleThreadExecutor()

        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (allPermissionsGranted()) startServices() else requestPermissions.launch(permissions)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(3, 4)).build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        ipTextView.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    private fun getIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val addr = interfaces.nextElement().inetAddresses
                while (addr.hasMoreElements()) {
                    val a = addr.nextElement()
                    if (!a.isLoopbackAddress && a is Inet4Address) return a.hostAddress ?: "未知"
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return "请连接 Wi-Fi"
    }

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.all { p -> p.value }) startServices() }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startServices() {
        startVideoServer()
        startAudioServer()
        startTalkServer()
        startCamera()
    }

    // --- 1. 视频发送 ---
    private fun startVideoServer() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                videoServerSocket = ServerSocket(VIDEO_PORT)
                while (true) {
                    val socket = videoServerSocket?.accept() ?: break
                    videoOutputStream = DataOutputStream(socket.getOutputStream())
                    isStreaming = true
                    listenForCommands(socket)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun listenForCommands(socket: Socket) {
        Thread {
            try {
                val input = DataInputStream(socket.getInputStream())
                while (socket.isConnected && !socket.isClosed) {
                    val length = input.readShort().toInt()
                    val buffer = ByteArray(length)
                    input.readFully(buffer)
                    val cmd = String(buffer, Charsets.UTF_8)

                    runOnUiThread {
                        if (cmd == "SWITCH_CAMERA") switchCamera()
                        else if (cmd == "TOGGLE_FLASH") toggleFlash()
                        else if (cmd.startsWith("ZOOM:")) {
                            try { camera?.cameraControl?.setLinearZoom(cmd.split(":")[1].toFloat()) } catch (e: Exception){}
                        }
                    }
                }
            } catch (e: Exception) { isStreaming = false }
        }.start()
    }

    // --- 2. 音频发送 ---
    private fun startAudioServer() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ss = ServerSocket(AUDIO_PORT)
                while (true) {
                    val socket = ss.accept()
                    isAudioRunning = true
                    Thread { streamAudio(socket) }.start()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun streamAudio(socket: Socket) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val minBuf = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)
        val buf = ByteArray(minBuf)
        val out = socket.getOutputStream()
        try {
            recorder.startRecording()
            while (isAudioRunning && socket.isConnected) {
                val read = recorder.read(buf, 0, buf.size)
                if (read > 0) out.write(buf, 0, read)
            }
        } catch (e: Exception) { e.printStackTrace() } finally {
            try { recorder.stop(); recorder.release(); socket.close() } catch (e: Exception){}
        }
    }

    // --- 3. 喊话接收 ---
    private fun startTalkServer() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ss = ServerSocket(TALK_PORT)
                while (true) {
                    val socket = ss.accept()
                    isTalkRunning = true
                    Thread { receiveTalk(socket) }.start()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun receiveTalk(socket: Socket) {
        val minBuf = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val tracker = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(minBuf).setTransferMode(AudioTrack.MODE_STREAM).build()

        val buf = ByteArray(minBuf)
        val inp = socket.getInputStream()
        try {
            tracker.play()
            while (isTalkRunning && socket.isConnected) {
                val read = inp.read(buf)
                if (read > 0) tracker.write(buf, 0, read) else break
            }
        } catch (e: Exception) { e.printStackTrace() } finally {
            try { tracker.stop(); tracker.release(); socket.close() } catch (e: Exception){}
        }
    }

    // --- 4. 摄像头核心 ---
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        // 使用 640x480 分辨率，兼容性最好，传输最流畅
        val strategy = ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)

        imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(strategy).build())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        imageAnalysis?.setAnalyzer(cameraExecutor) { image ->
            try {
                if (isStreaming && videoOutputStream != null) {
                    // 使用增强版转换函数，解决绿屏问题
                    val nv21Bytes = yuv420ToNv21(image)

                    val yuvImage = YuvImage(nv21Bytes, ImageFormat.NV21, image.width, image.height, null)
                    val out = ByteArrayOutputStream()
                    // 压缩质量 60
                    yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 60, out)
                    val bytes = out.toByteArray()

                    synchronized(videoOutputStream!!) {
                        videoOutputStream?.writeByte(0xBE); videoOutputStream?.writeByte(0xEF)
                        videoOutputStream?.writeInt(bytes.size); videoOutputStream?.write(bytes)
                        videoOutputStream?.flush()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                image.close()
            }
        }

        try { provider.unbindAll(); camera = provider.bindToLifecycle(this, selector, imageAnalysis) } catch (e: Exception) {}
    }

    // --- 【终极兼容算法】YUV_420_888 转 NV21 ---
    // 这个函数能够完美处理荣耀/华为等手机的内存对齐问题
    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4

        val nv21 = ByteArray(ySize + uvSize * 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val rowStrideY = yPlane.rowStride
        val rowStrideUV = uPlane.rowStride
        val pixelStrideUV = uPlane.pixelStride

        // 1. 复制 Y 分量 (处理填充字节)
        if (rowStrideY == width) {
            yBuffer.get(nv21, 0, ySize)
        } else {
            var pos = 0
            for (row in 0 until height) {
                yBuffer.position(row * rowStrideY)
                yBuffer.get(nv21, pos, width)
                pos += width
            }
        }

        // 2. 复制 UV 分量 (处理 PixelStride 和 RowStride)
        // NV21 格式是 V, U, V, U...
        val uvHeight = height / 2
        val uvWidth = width / 2
        var pos = ySize

        // 这种双重循环虽然看起来慢，但对于 640x480 的图像，现代 CPU 处理只需几毫秒
        // 它是保证兼容性的唯一方法
        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val uvPos = row * rowStrideUV + col * pixelStrideUV

                // 提取 V
                if (uvPos < vBuffer.limit()) {
                    nv21[pos++] = vBuffer.get(uvPos)
                } else {
                    pos++ // 容错，防止数组越界
                }

                // 提取 U
                if (uvPos < uBuffer.limit()) {
                    nv21[pos++] = uBuffer.get(uvPos)
                } else {
                    pos++
                }
            }
        }

        return nv21
    }

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        bindCamera()
    }

    private fun toggleFlash() {
        if (camera?.cameraInfo?.hasFlashUnit() == true) {
            isFlashOn = !isFlashOn
            camera?.cameraControl?.enableTorch(isFlashOn)
        }
    }
}