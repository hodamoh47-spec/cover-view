package com.coverview.app

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences

    private val phonePermissions = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS
    )
    private val PHONE_PERMISSION_REQUEST_CODE = 501

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("cover_view_prefs", Context.MODE_PRIVATE)

        val btnGrantOverlay = findViewById<android.widget.Button>(R.id.btnGrantOverlay)
        val btnIgnoreBattery = findViewById<android.widget.Button>(R.id.btnIgnoreBattery)
        val btnCalibrate = findViewById<android.widget.Button>(R.id.btnCalibrate)
        val btnPreview = findViewById<android.widget.Button>(R.id.btnPreview)
        val seekTop = findViewById<SeekBar>(R.id.seekTop)
        val seekHeight = findViewById<SeekBar>(R.id.seekHeight)
        val switchService = findViewById<SwitchMaterial>(R.id.switchService)
        val switchClock = findViewById<SwitchMaterial>(R.id.switchClock)
        val switchCalls = findViewById<SwitchMaterial>(R.id.switchCalls)
        val switchDnd = findViewById<SwitchMaterial>(R.id.switchDnd)
        val switchMedia = findViewById<SwitchMaterial>(R.id.switchMedia)

        // load saved values (default: top=8%, height=12%)
        seekTop.progress = prefs.getInt("top_percent", 8)
        seekHeight.progress = prefs.getInt("height_percent", 12)
        switchClock.isChecked = prefs.getBoolean("feature_clock_enabled", true)
        switchCalls.isChecked = prefs.getBoolean("feature_calls_enabled", true)
        switchDnd.isChecked = prefs.getBoolean("feature_dnd_enabled", false)
        switchMedia.isChecked = prefs.getBoolean("feature_media_enabled", true)

        seekTop.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                prefs.edit().putInt("top_percent", value).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                prefs.edit().putInt("height_percent", value).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        switchClock.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("feature_clock_enabled", isChecked).apply()
        }

        switchCalls.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("feature_calls_enabled", isChecked).apply()
            if (isChecked && !hasPhonePermissions()) {
                ActivityCompat.requestPermissions(this, phonePermissions, PHONE_PERMISSION_REQUEST_CODE)
            }
        }

        switchDnd.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("feature_dnd_enabled", isChecked).apply()
            if (isChecked && !isNotificationPolicyAccessGranted()) {
                AlertDialog.Builder(this)
                    .setMessage("محتاج تدي التطبيق إذن الوصول لوضع عدم الإزعاج عشان الخاصية دي تشتغل")
                    .setPositiveButton("افتح الإعدادات") { _, _ ->
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    }
                    .setNegativeButton("لاحقًا", null)
                    .show()
            }
        }

        switchMedia.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("feature_media_enabled", isChecked).apply()
        }

        btnCalibrate.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }

        btnGrantOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                AlertDialog.Builder(this)
                    .setMessage("الإذن ده متاح بالفعل ✅")
                    .setPositiveButton("تمام", null).show()
            }
        }

        btnIgnoreBattery.setOnClickListener {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }

        btnPreview.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setMessage("لازم تدي إذن الـ Overlay الأول")
                    .setPositiveButton("تمام", null).show()
                return@setOnClickListener
            }
            val intent = Intent(this, CoverOverlayService::class.java)
            intent.action = CoverOverlayService.ACTION_PREVIEW
            startForegroundServiceCompat(intent)
        }

        switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    AlertDialog.Builder(this)
                        .setMessage("لازم تدي إذن الـ Overlay الأول")
                        .setPositiveButton("تمام", null).show()
                    switchService.isChecked = false
                    return@setOnCheckedChangeListener
                }
                val intent = Intent(this, CoverOverlayService::class.java)
                intent.action = CoverOverlayService.ACTION_START
                startForegroundServiceCompat(intent)
            } else {
                val intent = Intent(this, CoverOverlayService::class.java)
                intent.action = CoverOverlayService.ACTION_STOP
                startForegroundServiceCompat(intent)
            }
        }
    }

    private fun hasPhonePermissions(): Boolean {
        return phonePermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isNotificationPolicyAccessGranted(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
