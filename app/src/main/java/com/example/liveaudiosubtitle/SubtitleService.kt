package com.example.liveaudiosubtitle

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

class SubtitleService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var tvThai: TextView? = null
    private var tvOriginal: TextView? = null

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val apiKey = intent?.getStringExtra("API_KEY") ?: ""
        val langName = intent?.getStringExtra("LANG_NAME") ?: "any language"
        val isInternal = intent?.getBooleanExtra("IS_INTERNAL", false) ?: false
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        startForegroundNotification(isInternal)
        setupOverlay()

        isRunning = true
        serviceScope.launch {
            try {
                updateOverlay("พร้อมแล้ว! กำลังฟังเสียง ($langName)...", "")
                startAudioCapture(apiKey, langName, isInternal, resultCode, data)
            } catch (e: Exception) {
                updateOverlay("เกิดข้อผิดพลาด: ${e.message}", "")
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundNotification(isInternal: Boolean) {
        val channelId = "live_subtitle_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Live Subtitle", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ซับไทย AI กำลังทำงาน")
            .setContentText("กำลังฟังเสียงและแปลเป็นภาษาไทย")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (isInternal) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(1, notification, type)
        } else {
            startForeground(1, notification)
        }
    }

    private fun setupOverlay() {
        if (overlayView != null) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_subtitle, null)
        tvThai = overlayView?.findViewById(R.id.tvOverlayThai)
        tvOriginal = overlayView?.findViewById(R.id.tvOverlayOriginal)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = 100
        }

        windowManager?.addView(overlayView, params)
    }

    private fun updateOverlay(thai: String, sub: String) {
        CoroutineScope(Dispatchers.Main).launch {
            tvThai?.text = thai
            tvOriginal?.text = sub
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture(apiKey: String, langName: String, isInternal: Boolean, resultCode: Int, data: Intent?) {
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        audioRecord = if (isInternal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && data != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } else {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }

        val chunkSamples = sampleRate * 4 // อัดเสียงทีละ 4 วินาที
        val readBuffer = ShortArray(2048)
        val pcmBuffer = ShortArray(chunkSamples)
        var offset = 0

        audioRecord?.startRecording()

        while (isRunning) {
            val read = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: 0
            if (read > 0) {
                val copyLen = minOf(read, chunkSamples - offset)
                System.arraycopy(readBuffer, 0, pcmBuffer, offset, copyLen)
                offset += copyLen

                if (offset >= chunkSamples) {
                    val audioCopy = pcmBuffer.copyOf()
                    offset = 0

                    // เช็กความดังเสียง ถ้าเงียบสนิทจะข้ามไปไม่เปลืองโควตา API
                    var maxAmp = 0
                    for (s in audioCopy) {
                        val a = abs(s.toInt())
                        if (a > maxAmp) maxAmp = a
                    }

                    if (maxAmp > 400) {
                        serviceScope.launch {
                            val wavBytes = pcmToWav(audioCopy, sampleRate)
                            val result = callGeminiTranslate(apiKey, langName, wavBytes)
                            if (result.isNotBlank() && result != "-") {
                                updateOverlay(result, "")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun pcmToWav(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataSize = pcm.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        val byteRate = sampleRate * 2

        fun writeString(s: String) = out.write(s.toByteArray())
        fun writeInt(v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
            out.write((v shr 16) and 0xff)
            out.write((v shr 24) and 0xff)
        }
        fun writeShort(v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
        }

        writeString("RIFF")
        writeInt(36 + dataSize)
        writeString("WAVE")
        writeString("fmt ")
        writeInt(16)
        writeShort(1)
        writeShort(1)
        writeInt(sampleRate)
        writeInt(byteRate)
        writeShort(2)
        writeShort(16)
        writeString("data")
        writeInt(dataSize)

        for (s in pcm) {
            writeShort(s.toInt())
        }
        return out.toByteArray()
    }

    private fun callGeminiTranslate(apiKey: String, langName: String, wavBytes: ByteArray): String {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 15000

            val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
            val prompt = "Listen to the spoken dialogue in $langName from this audio. Ignore background music or noise. Translate the speech into natural Thai subtitles. Output ONLY the Thai translation. If there is no clear speech, output only a single dash: -"

            val inlineData = JSONObject().apply {
                put("mime_type", "audio/wav")
                put("data", base64Audio)
            }
            val parts = JSONArray().apply {
                put(JSONObject().put("text", prompt))
                put(JSONObject().put("inline_data", inlineData))
            }
            val body = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            }

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream.bufferedReader().use { it.readText() }

            if (responseCode in 200..299) {
                val json = JSONObject(responseText)
                val candidates = json.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val content = candidates.getJSONObject(0).getJSONObject("content")
                    val respParts = content.getJSONArray("parts")
                    respParts.getJSONObject(0).optString("text", "").trim()
                } else ""
            } else {
                "API Error ($responseCode)"
            }
        } catch (e: Exception) {
            "เชื่อมต่อไม่ได้: ${e.message}"
        }
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        mediaProjection?.stop()
        if (overlayView != null) {
            windowManager?.removeView(overlayView)
            overlayView = null
        }
        super.onDestroy()
    }
}

