package com.coverview.app

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * شاشة معايرة تلقائية لمكان وارتفاع فتحة الجراب.
 * بدل ما المستخدم يخمّن بالسلايدر، هنا بيشوف شريط حي على الشاشة
 * ويحركه باستخدام زرار الصوت (فوق/تحت) لحد ما يطابق فتحة الجراب بالظبط.
 *
 * خطوة 1: ضبط "البعد من فوق" (top_percent)
 * خطوة 2: ضبط "ارتفاع الشريط" (height_percent)
 */
class CalibrationActivity : AppCompatActivity() {

    private companion object {
        const val STEP_POSITION = 1
        const val STEP_HEIGHT = 2
        const val MIN_TOP = 0
        const val MAX_TOP = 60
        const val MIN_HEIGHT = 4
        const val MAX_HEIGHT = 30
        const val STEP_AMOUNT = 1
    }

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var previewStrip: LinearLayout
    private lateinit var stepTitle: TextView
    private lateinit var stepInstructions: TextView
    private lateinit var valueText: TextView
    private lateinit var btnNextStep: Button

    private var currentStep = STEP_POSITION
    private var topPercent = 8
    private var heightPercent = 12

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        prefs = getSharedPreferences("cover_view_prefs", Context.MODE_PRIVATE)
        topPercent = prefs.getInt("top_percent", 8)
        heightPercent = prefs.getInt("height_percent", 12)

        previewStrip = findViewById(R.id.previewStrip)
        stepTitle = findViewById(R.id.stepTitle)
        stepInstructions = findViewById(R.id.stepInstructions)
        valueText = findViewById(R.id.valueText)
        btnNextStep = findViewById(R.id.btnNextStep)

        findViewById<Button>(R.id.btnCancelCalibration).setOnClickListener { finish() }

        btnNextStep.setOnClickListener {
            if (currentStep == STEP_POSITION) {
                goToHeightStep()
            } else {
                saveAndFinish()
            }
        }

        updatePreview()
    }

    private fun goToHeightStep() {
        currentStep = STEP_HEIGHT
        stepTitle.text = "الخطوة 2: اضبط ارتفاع الشريط"
        stepInstructions.text =
            "استخدم زرار الصوت لأعلى/لأسفل عشان تكبّر أو تصغّر ارتفاع الشريط لحد ما يغطي فتحة الجراب بالكامل"
        btnNextStep.text = "حفظ"
        updatePreview()
    }

    private fun saveAndFinish() {
        prefs.edit()
            .putInt("top_percent", topPercent)
            .putInt("height_percent", heightPercent)
            .apply()
        Toast.makeText(this, "تم حفظ المعايرة ✅", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            adjust(STEP_AMOUNT)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            adjust(-STEP_AMOUNT)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // منع صوت زرار الفوليوم من التغيير فعليًا أثناء المعايرة
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun adjust(delta: Int) {
        if (currentStep == STEP_POSITION) {
            topPercent = (topPercent + delta).coerceIn(MIN_TOP, MAX_TOP)
        } else {
            heightPercent = (heightPercent + delta).coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        }
        updatePreview()
    }

    private fun updatePreview() {
        val dm = resources.displayMetrics
        val topPx = (dm.heightPixels * (topPercent / 100.0)).toInt()
        val heightPx = (dm.heightPixels * (heightPercent / 100.0)).toInt()

        val lp = previewStrip.layoutParams as android.widget.FrameLayout.LayoutParams
        lp.topMargin = topPx
        lp.height = heightPx
        previewStrip.layoutParams = lp

        valueText.text = if (currentStep == STEP_POSITION) {
            "البعد من فوق: $topPercent%"
        } else {
            "ارتفاع الشريط: $heightPercent%"
        }
    }
}
