package com.dash.headunit.car

import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dash.headunit.diag.BrightnessProbe
import com.dash.headunit.diag.WheelHeatProbe
import com.dash.headunit.navi.cluster.ClusterOverlay
import com.dash.headunit.radio.RadioHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Скрытый debug-экран (открывается из dev-сборки с главного экрана).
 * На нём калибровочная рамка на приборке ([ClusterOverlay.toggleCalibration]),
 * проба «Подогрев руля» ([WheelHeatProbe]) и проба штатного радио
 * ([RadioHub]: команды + лог службы, он же в files/radio_log.txt), проба
 * яркости экрана ГУ ([BrightnessProbe], лог в files/brightness_log.txt); остальные
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
        // Калибровочная рамка и образец маршрута на приборке — видно,
        // какая часть дисплея 1 попадает в «Карты».
        val btnFrame = Button(this).apply {
            setOnClickListener {
                ClusterOverlay.init(this@CarDebugActivity)
                // init асинхронный (main.post) — переключаем следующим постом.
                post { ClusterOverlay.toggleCalibration(); text = frameLabel() }
            }
        }
        root.addView(btnFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(24) })
        // Яркость ГУ: три пути записи + живое чтение состояния.
        root.addView(TextView(this).apply {
            text = "Яркость ГУ (каждый ряд — свой путь; затем «Вернуть как было»)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        })
        fun row(title: String, items: List<Pair<String, () -> Unit>>) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(TextView(this).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                minWidth = dp(260)
            })
            items.forEach { (label, action) ->
                r.addView(Button(this).apply {
                    text = label
                    setOnClickListener { action() }
                })
            }
            root.addView(r)
        }
        row("Desay (штатный ползунок):", listOf(1, 3, 5, 8, 10).map { lv ->
            "$lv" to { BrightnessProbe.setDesay(this, lv) }
        })
        row("Android screen_brightness:", listOf(0, 4, 9, 102, 255).map { v ->
            "$v" to { BrightnessProbe.setSetting(this, v) }
        })
        row("Окно (пока открыт экран):", listOf(
            "0.05" to { BrightnessProbe.setWindow(this, 0.05f) },
            "0.5" to { BrightnessProbe.setWindow(this, 0.5f) },
            "1.0" to { BrightnessProbe.setWindow(this, 1f) },
            "сброс" to { BrightnessProbe.setWindow(this, -1f) },
        ))
        root.addView(Button(this).apply {
            text = "Вернуть как было"
            setOnClickListener { BrightnessProbe.restore(this@CarDebugActivity) }
        })
        val brightLog = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(brightLog, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(24) })

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
                btnFrame.text = frameLabel()
                BrightnessProbe.refresh(this@CarDebugActivity)
                brightLog.text = buildString {
                    append(BrightnessProbe.state).append("\n\n")
                    BrightnessProbe.logLines().asReversed().forEach { append(it).append('\n') }
                }
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

    private fun frameLabel() =
        "Рамка на приборке: ${if (ClusterOverlay.calibration) "выключить" else "включить"}"

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()
}
