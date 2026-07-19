package com.coverview.app

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class CoverOverlayService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "com.coverview.app.START"
        const val ACTION_STOP = "com.coverview.app.STOP"
        const val ACTION_PREVIEW = "com.coverview.app.PREVIEW"
        const val CHANNEL_ID = "cover_view_channel"
        const val NOTIF_ID = 1001
        // proximity distance below which we consider the case "closed"
        const val NEAR_THRESHOLD = 4.0f
        // منع الفليكر: مفيش تغيير في الشريط غير لو القرب فضل ثابت 400ms
        const val DEBOUNCE_MS = 400L
    }

    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null
    private lateinit var windowManager: WindowManager
    private var clockOverlayView: View? = null
    private var callOverlayView: View? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var prefs: android.content.SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var coverClosed = false
    private var listening = false
    private var debounceRunnable: Runnable? = null

    // آخر حالة معروفة للمكالمة
    private var currentCallState = TelephonyManager.CALL_STATE_IDLE
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null

    // عشان نرجّع وضع عدم الإزعاج لسابق عهده بعد ما نقفل الشريط
    private var previousInterruptionFilter: Int? = null

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
                registerCallStateListener()
            }
            ACTION_STOP -> {
                stopListening()
                unregisterCallStateListener()
                removeClockOverlay()
                removeCallOverlay()
                restoreDndIfNeeded()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_PREVIEW -> {
                startForeground(NOTIF_ID, buildNotification())
                showClockOverlay()
                handler.postDelayed({ removeClockOverlay() }, 6000)
            }
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------------
    // Proximity sensor + debounce
    // ---------------------------------------------------------------------

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
        val closeNow = distance < NEAR_THRESHOLD
        if (closeNow == coverClosed) return

        // إلغاء أي تغيير معلّق ولسه ما استقرش، وابدأ عدّاد جديد
        debounceRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            coverClosed = closeNow
            if (closeNow) onCoverClosed() else onCoverOpened()
        }
        debounceRunnable = runnable
        handler.postDelayed(runnable, DEBOUNCE_MS)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onCoverClosed() {
        // لو فيه مكالمة واردة (رنين) نظهر شريط المكالمة بدل الساعة
        if (currentCallState == TelephonyManager.CALL_STATE_RINGING && isFeatureEnabled("feature_calls_enabled")) {
            showCallOverlay()
            return
        }
        // تجاهل عرض الساعة وقت مكالمة فعلية شغالة (السماعة قريبة من الودن)
        if (currentCallState == TelephonyManager.CALL_STATE_OFFHOOK) {
            return
        }
        if (isFeatureEnabled("feature_clock_enabled")) {
            showClockOverlay()
        }
    }

    private fun onCoverOpened() {
        removeClockOverlay()
        removeCallOverlay()
    }

    // ---------------------------------------------------------------------
    // Clock overlay
    // ---------------------------------------------------------------------

    private fun showClockOverlay() {
        if (clockOverlayView != null) return

        acquireWakeLock()

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_clock, null)
        clockOverlayView = view

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

        val mediaEnabled = isFeatureEnabled("feature_media_enabled")
        val mediaRow = view.findViewById<View>(R.id.mediaControlRow)
        mediaRow.visibility = if (mediaEnabled) View.VISIBLE else View.GONE
        if (mediaEnabled) setupMediaButtons(view)

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // الشريط لازم يكون قابل للمس بس لو أزرار الموسيقى شغالة
        val params = buildOverlayParams(touchable = mediaEnabled)
        params.screenBrightness = 0.35f

        try {
            windowManager.addView(view, params)
            applyDndIfNeeded()
        } catch (e: Exception) {
            clockOverlayView = null
        }
    }

    private fun removeClockOverlay() {
        clockOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        clockOverlayView = null
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
        releaseWakeLock()
        restoreDndIfNeeded()
    }

    private fun updateBatteryText(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
            val pct = (level * 100) / scale
            clockOverlayView?.findViewById<TextView>(R.id.batteryText)?.text = "$pct%"
        }
    }

    // ---------------------------------------------------------------------
    // Call overlay (answer / reject)
    // ---------------------------------------------------------------------

    private fun showCallOverlay() {
        removeClockOverlay()
        if (callOverlayView != null) return

        acquireWakeLock()

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_call, null)
        callOverlayView = view

        view.findViewById<android.widget.Button>(R.id.btnAnswerCall).setOnClickListener {
            answerCall()
        }
        view.findViewById<android.widget.Button>(R.id.btnRejectCall).setOnClickListener {
            rejectCall()
        }

        val params = buildOverlayParams(touchable = true)
        params.screenBrightness = 0.6f

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            callOverlayView = null
        }
    }

    private fun removeCallOverlay() {
        callOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        callOverlayView = null
        releaseWakeLock()
    }

    private fun answerCall() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "محتاج إذن الرد على المكالمات من الإعدادات", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager.acceptRingingCall()
            }
        } catch (_: SecurityException) {
            Toast.makeText(this, "مش قادر أرد على المكالمة على الجهاز ده", Toast.LENGTH_SHORT).show()
        } finally {
            removeCallOverlay()
        }
    }

    private fun rejectCall() {
        // ملحوظة: بداية من أندرويد 9، أندرويد بيقيّد رفض المكالمة على تطبيق
        // الهاتف الافتراضي بس. لو التطبيق مش هو تطبيق الهاتف الافتراضي، الزرار
        // ده هيقفل شريط المكالمة بس المكالمة نفسها ممكن تفضل بترن.
        try {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager.endCall()
            }
        } catch (_: SecurityException) {
            Toast.makeText(this, "مش قادر أرفض المكالمة أوتوماتيك على الجهاز ده", Toast.LENGTH_SHORT).show()
        } finally {
            removeCallOverlay()
        }
    }

    // ---------------------------------------------------------------------
    // Call state listening
    // ---------------------------------------------------------------------

    private fun registerCallStateListener() {
        if (!isFeatureEnabled("feature_calls_enabled")) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        @Suppress("DEPRECATION")
        val listener = object : PhoneStateListener() {
            @Suppress("DEPRECATION")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                currentCallState = state
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        callOverlayView?.findViewById<TextView>(R.id.callerText)?.text =
                            phoneNumber ?: "رقم غير معروف"
                        if (coverClosed) showCallOverlay()
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        removeCallOverlay()
                        removeClockOverlay()
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        removeCallOverlay()
                        if (coverClosed && isFeatureEnabled("feature_clock_enabled")) {
                            showClockOverlay()
                        }
                    }
                }
            }
        }
        phoneStateListener = listener
        @Suppress("DEPRECATION")
        telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun unregisterCallStateListener() {
        phoneStateListener?.let {
            @Suppress("DEPRECATION")
            telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
        }
        phoneStateListener = null
    }

    // ---------------------------------------------------------------------
    // Do Not Disturb
    // ---------------------------------------------------------------------

    private fun applyDndIfNeeded() {
        if (!isFeatureEnabled("feature_dnd_enabled")) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return
        if (previousInterruptionFilter == null) {
            previousInterruptionFilter = nm.currentInterruptionFilter
        }
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    }

    private fun restoreDndIfNeeded() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return
        previousInterruptionFilter?.let {
            nm.setInterruptionFilter(it)
        }
        previousInterruptionFilter = null
    }

    // ---------------------------------------------------------------------
    // Media control
    // ---------------------------------------------------------------------

    private fun setupMediaButtons(view: View) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        view.findViewById<ImageButton>(R.id.btnMediaPlayPause).setOnClickListener {
            dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
        view.findViewById<ImageButton>(R.id.btnMediaNext).setOnClickListener {
            dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_NEXT)
        }
        view.findViewById<ImageButton>(R.id.btnMediaPrev).setOnClickListener {
            dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
    }

    private fun dispatchMediaKey(audioManager: AudioManager, keyCode: Int) {
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun isFeatureEnabled(key: String): Boolean {
        // الساعة، المكالمات، الموسيقى شغالين افتراضيًا. DND متقفل افتراضيًا
        // لحد ما المستخدم يديله إذن ويشغّله بنفسه من الإعدادات.
        val default = key != "feature_dnd_enabled"
        return prefs.getBoolean(key, default)
    }

    private fun buildOverlayParams(touchable: Boolean): WindowManager.LayoutParams {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD

        if (!touchable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            flags,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "CoverView::OverlayWakeLock"
        )
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire(10 * 60 * 1000L) // safety timeout, 10 min max
    }

    private fun releaseWakeLock() {
        // متسبّش الـ wake lock يتقفل لو لسه فيه أوفرلاي تاني شغال (مثلا شريط المكالمة)
        if (clockOverlayView != null || callOverlayView != null) return
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
        unregisterCallStateListener()
        removeClockOverlay()
        removeCallOverlay()
        restoreDndIfNeeded()
    }

    override fun onBind(intent: Intent?) = null
}
