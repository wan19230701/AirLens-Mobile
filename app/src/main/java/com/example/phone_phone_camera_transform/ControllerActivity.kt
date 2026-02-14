package com.example.phone_phone_camera_transform

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.*
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class ControllerActivity : AppCompatActivity() {

    private lateinit var etIp: EditText
    private lateinit var btnConnect: Button
    private lateinit var videoView: ImageView
    private lateinit var seekBarZoom: SeekBar
    private lateinit var btnSwitch: Button
    private lateinit var btnFlash: Button
    private lateinit var btnTalk: Button
    private lateinit var btnRotate: Button
    private lateinit var btnSnapshot: Button

    private var isConnected = false
    private var targetIp = ""
    private var videoSocket: Socket? = null
    private var commandOut: DataOutputStream? = null

    // --- 【修改点1】 记录旋转状态 ---
    private var currentRotation = 90f // 默认后置竖屏是90度
    private var isFrontCamera = false // 记录当前是不是前置

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controller)

        etIp = findViewById(R.id.etIpAddress)
        btnConnect = findViewById(R.id.btnConnect)
        videoView = findViewById(R.id.videoView)
        seekBarZoom = findViewById(R.id.seekBarZoom)
        btnSwitch = findViewById(R.id.btnSwitch)
        btnFlash = findViewById(R.id.btnFlash)
        btnTalk = findViewById(R.id.btnTalk)
        btnRotate = findViewById(R.id.btnRotate)
        btnSnapshot = findViewById(R.id.btnSnapshot)

        btnConnect.setOnClickListener { if (!isConnected) connect() else disconnect() }

        // --- 【修改点2】 切换镜头时自动旋转 ---
        btnSwitch.setOnClickListener {
            sendCmd("SWITCH_CAMERA")
            isFrontCamera = !isFrontCamera
            // 后置一般是90度，前置切换后通常会倒过来，所以设为270度
            currentRotation = if (isFrontCamera) 270f else 90f
            videoView.rotation = currentRotation
            // 重置变焦条
            seekBarZoom.progress = 0
        }

        btnFlash.setOnClickListener { sendCmd("TOGGLE_FLASH") }

        seekBarZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) sendCmd("ZOOM:${p / 100f}") }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        btnRotate.setOnClickListener {
            currentRotation = (currentRotation + 90f) % 360f
            videoView.rotation = currentRotation
            Toast.makeText(this, "旋转: $currentRotation°", Toast.LENGTH_SHORT).show()
        }

        btnTalk.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                startTalking()
                btnTalk.text = "🎙️ 发送中..."
            } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                stopTalking()
                btnTalk.text = "🎙️ 按住喊话"
            }
            true
        }

        btnSnapshot.setOnClickListener { saveSnapshot() }

        // 初始化旋转
        videoView.rotation = currentRotation
    }

    private fun connect() {
        targetIp = etIp.text.toString().trim()
        if (targetIp.isEmpty()) return
        isConnected = true
        btnConnect.text = "断开"
        lifecycleScope.launch(Dispatchers.IO) { receiveVideo() }
        lifecycleScope.launch(Dispatchers.IO) { receiveAudio() }
    }

    private fun disconnect() {
        isConnected = false
        btnConnect.text = "连接"
        try { videoSocket?.close() } catch (e: Exception){}
    }

    private suspend fun receiveVideo() {
        try {
            videoSocket = Socket()
            videoSocket?.connect(InetSocketAddress(targetIp, 6677), 3000)
            commandOut = DataOutputStream(videoSocket!!.getOutputStream())
            val inp = DataInputStream(videoSocket!!.getInputStream())

            while (isConnected) {
                if (inp.read() != 0xBE || inp.read() != 0xEF) continue
                val len = inp.readInt()
                if (len > 2000000 || len < 0) continue
                val data = ByteArray(len)
                inp.readFully(data)
                val bmp = BitmapFactory.decodeByteArray(data, 0, len)
                withContext(Dispatchers.Main) { if (bmp != null) videoView.setImageBitmap(bmp) }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ControllerActivity, "连接断开", Toast.LENGTH_SHORT).show()
                disconnect()
            }
        }
    }

    private fun sendCmd(cmd: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (commandOut != null) {
                    val b = cmd.toByteArray()
                    synchronized(commandOut!!) { commandOut?.writeShort(b.size); commandOut?.write(b); commandOut?.flush() }
                }
            } catch (e: Exception){}
        }
    }

    private fun receiveAudio() {
        try {
            val s = Socket()
            s.connect(InetSocketAddress(targetIp, 6678), 3000)
            val minBuf = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(minBuf).setTransferMode(AudioTrack.MODE_STREAM).build()

            track.play()
            val buf = ByteArray(1024)
            val inp = s.getInputStream()
            while (isConnected) {
                val r = inp.read(buf)
                if (r > 0) track.write(buf, 0, r) else break
            }
            track.release()
            s.close()
        } catch (e: Exception){}
    }

    private var isTalking = false
    private fun startTalking() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        isTalking = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(targetIp, 6679), 2000)
                val minBuf = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val rec = AudioRecord(MediaRecorder.AudioSource.MIC, 44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)
                rec.startRecording()
                val buf = ByteArray(minBuf)
                val out = s.getOutputStream()
                while (isTalking) {
                    val r = rec.read(buf, 0, buf.size)
                    if (r > 0) out.write(buf, 0, r)
                }
                rec.stop(); rec.release(); s.close()
            } catch (e: Exception){}
        }
    }

    private fun stopTalking() { isTalking = false }

    private fun saveSnapshot() {
        videoView.isDrawingCacheEnabled = true
        val bmp = (videoView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
        videoView.isDrawingCacheEnabled = false
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AirLens")
        }
        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.also { uri ->
            contentResolver.openOutputStream(uri)?.use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, it) }
            Toast.makeText(this, "已保存到相册", Toast.LENGTH_SHORT).show()
        }
    }
}