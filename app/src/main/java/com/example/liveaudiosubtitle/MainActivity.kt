package com.example.liveaudiosubtitle

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etApiKey: EditText
    private lateinit var spinnerLanguage: Spinner
    private lateinit var radioInternal: RadioButton
    private lateinit var tvStatus: TextView

    private val languages = arrayOf("อัตโนมัติ (ทุกภาษา)", "ญี่ปุ่น (Japanese)", "อังกฤษ (English)", "จีน (Chinese)", "เกาหลี (Korean)")
    private val langNames = arrayOf("any language", "Japanese", "English", "Chinese", "Korean")

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startSubtitleService(true, result.resultCode, result.data)
        } else {
            tvStatus.text = "สถานะ: ยกเลิกการจับเสียงในเครื่อง"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etApiKey = findViewById(R.id.etApiKey)
        spinnerLanguage = findViewById(R.id.spinnerLanguage)
        radioInternal = findViewById(R.id.radioInternal)
        tvStatus = findViewById(R.id.tvStatus)

        val prefs = getSharedPreferences("subtitle_prefs", Context.MODE_PRIVATE)
        etApiKey.setText(prefs.getString("api_key", ""))

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages)
        spinnerLanguage.adapter = adapter

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            if (apiKey.isEmpty()) {
                Toast.makeText(this, "กรุณาวาง Gemini API Key ก่อนเริ่มใช้งาน", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            prefs.edit().putString("api_key", apiKey).apply()
            checkPermissionsAndStart()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, SubtitleService::class.java))
            tvStatus.text = "สถานะ: หยุดการทำงานแล้ว"
        }
    }

    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "กรุณาอนุญาตสิทธิ์ 'แสดงทับแอปอื่น'", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }

        if (radioInternal.isChecked) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } else {
            startSubtitleService(false, 0, null)
        }
    }

    private fun startSubtitleService(isInternal: Boolean, resultCode: Int, data: Intent?) {
        val selectedLang = langNames[spinnerLanguage.selectedItemPosition]
        val apiKey = etApiKey.text.toString().trim()

        val serviceIntent = Intent(this, SubtitleService::class.java).apply {
            putExtra("API_KEY", apiKey)
            putExtra("LANG_NAME", selectedLang)
            putExtra("IS_INTERNAL", isInternal)
            putExtra("RESULT_CODE", resultCode)
            putExtra("DATA", data)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        tvStatus.text = "สถานะ: กำลังทำงาน... (พับแอปไปเปิดดูคลิปได้เลย)"
    }
}

