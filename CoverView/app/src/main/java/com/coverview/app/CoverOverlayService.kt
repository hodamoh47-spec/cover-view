package com.coverview.app

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat

class CoverOverlayService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "com.coverview.app.START"
        const val ACTION_STOP = "com.coverview.app.STOP"
        const val ACTION_PREVIEW = "com.coverview.app.PREVIEW"
        const val CHANNEL_ID = "cover_view_channel"
        const val NOTIF_ID = 1001
        // proximity distance below which we consider the case "closed"
        const val NEAR_THRESHOLD = 4.0f
    }

    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var prefs: android.content.SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var isOverlayShown = false
    private var listening = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateBatteryText(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("cover_view_prefs", Context.MODE_PRIVATE)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification())
                startListening()
            }
            ACTION_STOP -> {
                stopListening()
                removeOverlay()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_PREVIEW -> {
                startForeground(NOTIF_ID, buildNotification())
                showOverlay()
                handler.postDelayed({ hideOverlay() }, 6000)
            }
        }
        return START_STICKY
    }

    private fun startListening() {
        if (listening) return
        proximitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            listening = true
        }
    }

    private fun stopListening() {
        if (!listening) return
        sensorManager.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_PROXIMITY) return
        val distance = event.values[0]
        if (distance < NEAR_THRESHOLD) {
            // case is closed / covered
            showOverlay()
        } else {
            hideOverlay()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun showOverlay() {
        if (isOverlayShown) return
        isOverlayShown = true

        acquireWakeLock()

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_clock, null)
        overlayView = view

        val strip = view.findViewById<View>(R.id.stripContainer)
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        val topPercent = prefs.getInt("top_percent", 8)
        val heightPercent = prefs.getInt("height_percent", 12)
        val topMarginPx = (dm.heightPixels * (topPercent / 100.0)).toInt()
        val stripHeightPx = (dm.heightPixels * (heightPercent / 100.0)).toInt()

        val lp = strip.layoutParams as android.widget.FrameLayout.LayoutParams
        lp.topMargin = topMarginPx
        lp.height = stripHeightPx
        strip.layoutParams = lp

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            PixelFormat.TRANSLUCENT
        )
        params.screenBrightness = 0.35f

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            isOverlayShown = false
        }
    }

    private fun hideOverlay() {
        handler.postDelayed({
            if (isOverlayShown) removeOverlay()
        }, 300)
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
        isOverlayShown = false
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
        releaseWakeLock()
    }

    private fun updateBatteryText(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
            val pct = (level * 100) / scale
            overlayView?.findViewById<TextView>(R.id.batteryText)?.text = "$pct%"
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "CoverView::OverlayWakeLock"
        )
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire(10 * 60 * 1000L) // safety timeout, 10 min max
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cover View شغال")
            .setContentText("بيراقب حساس القرب عشان يظهر الساعة على الجراب")
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Cover View Service", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        removeOverlay()
    }

    override fun onBind(intent: Intent?) = null
}
