package com.dash.headunit.car

import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dash.headunit.diag.WheelHeatProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Скрытый debug-экран (открывается из dev-сборки с главного экрана).
 * Сейчас на нём только проба «Подогрев руля» ([WheelHeatProbe]); остальные
 * пробы и сканеры убраны — их классы в `diag/` и история в git.
 */
class CarDebugActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val btnWheel = Button(this).apply {
            text = "Подогрев руля: что меняется при нажатии кнопки (~70 с)"
            setOnClickListener { WheelHeatProbe.start(this@CarDebugActivity) }
        }
        val status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(btnWheel)
        root.addView(status)
        setContentView(root)

        scope.launch {
            while (true) {
                status.text = "Подогрев руля: ${WheelHeatProbe.status}"
                btnWheel.isEnabled = !WheelHeatProbe.running
                delay(1000)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()
}
