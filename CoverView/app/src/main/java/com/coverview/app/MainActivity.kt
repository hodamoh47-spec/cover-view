package com.coverview.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("cover_view_prefs", Context.MODE_PRIVATE)

        val btnGrantOverlay = findViewById<android.widget.Button>(R.id.btnGrantOverlay)
        val btnIgnoreBattery = findViewById<android.widget.Button>(R.id.btnIgnoreBattery)
        val btnPreview = findViewById<android.widget.Button>(R.id.btnPreview)
        val seekTop = findViewById<SeekBar>(R.id.seekTop)
        val seekHeight = findViewById<SeekBar>(R.id.seekHeight)
        val switchService = findViewById<SwitchMaterial>(R.id.switchService)

        // load saved values (default: top=8%, height=12%)
        seekTop.progress = prefs.getInt("top_percent", 8)
        seekHeight.progress = prefs.getInt("height_percent", 12)

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

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
