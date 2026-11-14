package com.dash.headunit.car

import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dash.headunit.diag.BrightnessProbe
import com.dash.headunit.navi.cluster.ClusterOverlay
import com.dash.headunit.radio.RadioHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Скрытый debug-экран (открывается из dev-сборки с главного экрана).
 * На нём калибровочная рамка на приборке ([ClusterOverlay.toggleCalibration]),
 * проба штатного радио
 * ([RadioHub]: команды + лог службы), проба
 * яркости экрана ГУ ([BrightnessProbe], лог в files/brightness_log.txt), сырые
 * байты TPMS (Desay 4,10 / 4,8, приборка a005) для сверки с экраном шин; остальные
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
        // Шины: сырые байты TPMS и расчёт — сверить с экраном шин приборки
        // (формула raw×2−28 кПа и порядок колёс подобраны по двум снимкам).
        val tireInfo = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextIsSelectable(true)
        }
        root.addView(tireInfo, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(24) })
        CarHub.init(this)
        CarHub.ensurePolling()
        val desay = DesayCarReader(applicationContext)
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
                tireInfo.text = withContext(Dispatchers.IO) { tireText(desay) }
                btnFrame.text = frameLabel()
                BrightnessProbe.refresh(this@CarDebugActivity)
                brightLog.text = buildString {
                    append(BrightnessProbe.state).append("\n\n")
                    BrightnessProbe.logLines().asReversed().forEach { append(it).append('\n') }
                }
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

    /** Сырые давления/температура шин и расчёт по [tiresFromRaw]. Блокирующий. */
    private fun tireText(desay: DesayCarReader): String {
        fun calc(raw: IntArray?) = tiresFromRaw(raw)?.let { t ->
            Wheel.entries.joinToString("  ") { w -> "${w.short} ${t.of(w)?.let { "%.2f".format(it) } ?: "—"}" }
        } ?: "—"
        val d = desay.readRaw(4, 10, logErrors = false)
        val temp = desay.readRaw(4, 8, logErrors = false)
        val pps = ClusterPps.tireRaw()
        return "Шины (TPMS). Порядок байтов: ${TIRE_RAW_ORDER.joinToString(",") { it.short }}\n" +
            "Desay 4,10 = ${d?.joinToString(",") ?: "нет"} → ${calc(d)}\n" +
            "Desay 4,8 [колесо, t raw] = ${temp?.joinToString(",") ?: "нет"}\n" +
            "Приборка a005 b4–7 = ${pps?.joinToString(",") ?: "нет"} → ${calc(pps)}"
    }

    private fun frameLabel() =
        "Рамка на приборке: ${if (ClusterOverlay.calibration) "выключить" else "включить"}"

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()
}
