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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream

class SubtitleService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var tvThai: TextView? = null
    private var tvOriginal: TextView? = null

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var translator: Translator? = null
    private var voskModel: Model? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var lastTranslatedText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val langCode = intent?.getStringExtra("LANG_CODE") ?: "en"
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
                updateOverlay("กำลังเตรียมตัวแปลภาษาไทย...", "")
                setupTranslator(langCode)

                updateOverlay("กำลังโหลดโมเดลเสียง ($langCode) ครั้งแรกอาจใช้เวลา 1-2 นาที...", "")
                val modelDir = prepareVoskModel(langCode)
                voskModel = Model(modelDir.absolutePath)

                updateOverlay("พร้อมแล้ว! กำลังรอเสียงพูด...", "")
                startAudioCapture(isInternal, resultCode, data)
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
            .setContentTitle("ซับไทยเรียลไทม์กำลังทำงาน")
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

    private fun updateOverlay(thai: String, original: String) {
        CoroutineScope(Dispatchers.Main).launch {
            tvThai?.text = thai
            tvOriginal?.text = original
        }
    }

    private suspend fun setupTranslator(langCode: String) {
        val sourceLang = when (langCode) {
            "ja" -> TranslateLanguage.JAPANESE
            "zh" -> TranslateLanguage.CHINESE
            "ko" -> TranslateLanguage.KOREAN
            else -> TranslateLanguage.ENGLISH
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLang)
            .setTargetLanguage(TranslateLanguage.THAI)
            .build()
        translator = Translation.getClient(options)

        val deferred = CompletableDeferred<Unit>()
        translator?.downloadModelIfNeeded(DownloadConditions.Builder().build())
            ?.addOnSuccessListener { deferred.complete(Unit) }
            ?.addOnFailureListener { deferred.completeExceptionally(it) }
        deferred.await()
    }

    private fun prepareVoskModel(langCode: String): File {
        val modelUrls = mapOf(
            "en" to "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            "ja" to "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip",
            "zh" to "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip",
            "ko" to "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip"
        )
        val urlStr = modelUrls[langCode] ?: modelUrls["en"]!!
        val targetDir = File(filesDir, "vosk_model_$langCode")

        if (targetDir.exists() && targetDir.listFiles()?.isNotEmpty() == true) {
            return targetDir.listFiles()!!.first { it.isDirectory }
        }

        targetDir.mkdirs()
        val zipFile = File(cacheDir, "model_$langCode.zip")
        URL(urlStr).openStream().use { input ->
            FileOutputStream(zipFile).use { output ->
                input.copyTo(output)
            }
        }

        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry
