package com.dash.headunit.car

import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dash.headunit.diag.WheelHeatProbe
import com.dash.headunit.radio.RadioHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Скрытый debug-экран (открывается из dev-сборки с главного экрана).
 * На нём проба «Подогрев руля» ([WheelHeatProbe]) и проба штатного радио
 * ([RadioHub]: команды + лог службы, он же в files/radio_log.txt); остальные
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

        // Радио: команды службе SVMedia и лог её ответов.
        val radioRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "Радио: обновить" to { RadioHub.init(this); RadioHub.refresh() },
            "◀◀" to { RadioHub.seekDown() },
            "⏯" to { RadioHub.toggle() },
            "▶▶" to { RadioHub.seekUp() },
            "FM/AM" to { RadioHub.switchBand(this) },
            "★" to { RadioHub.toggleFavorite() },
            "Открыть" to { RadioHub.open(this) },
        ).forEach { (label, action) ->
            radioRow.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            })
        }
        val radioLog = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(radioRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(24) })
        root.addView(radioLog)
        setContentView(android.widget.ScrollView(this).apply { addView(root) })
        RadioHub.init(this)

        scope.launch {
            while (true) {
                status.text = "Подогрев руля: ${WheelHeatProbe.status}"
                btnWheel.isEnabled = !WheelHeatProbe.running
                val st = RadioHub.state
                radioLog.text = buildString {
                    append("Радио: connected=${RadioHub.connected} unavailable=${RadioHub.unavailable}\n")
                    append("$st\n\n")
                    RadioHub.logLines().asReversed().forEach { append(it).append('\n') }
                }
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
