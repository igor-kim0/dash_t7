package com.dash.headunit.car

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран диагностики (кнопка в колонке дашборда). Сейчас на нём только
 * лог борткомпьютера ([TripLog]): поездки, моточасы, жизнь процесса —
 * разбор «иногда не считается» — и проба вида приборки
 * ([com.dash.headunit.diag.ClusterViewTest]: «Карты» → AR → «Карты»).
 * Прежние пробы (шины, яркость, радио) убраны — их классы в `diag/` и история в git.
 */
class CarDebugActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarHub.init(this)
        CarHub.ensurePolling()
        TripLog.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "Лог поездок и моточасов"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnToggle = Button(this)
        val btnClear = Button(this).apply { text = "Очистить" }
        buttons.addView(btnToggle)
        buttons.addView(btnClear)
        root.addView(buttons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        val info = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextIsSelectable(true)
        }
        root.addView(info)
        val logView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(logView)

        root.addView(TextView(this).apply {
            text = "Вид приборки"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, dp(24), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "Рамка калибровки, затем область навигации 1 («Карты») → 2 (AR) → 1, " +
                "по 15 с. На каждом шаге сфотографируйте приборку. " +
                "Отчёт — files/cluster/view_*.txt."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        root.addView(Button(this).apply {
            text = "Проверить вид приборки"
            setOnClickListener { com.dash.headunit.diag.ClusterViewTest.start(this@CarDebugActivity) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(TextView(this).apply {
            text = "Только на стоянке: прячет штатные скорость и обороты (модель данных приборки " +
                "через qconn, без записи в MCU), через 30 с возвращает сами. Сфотографируйте приборку. " +
                "Если что-то пошло не так — шкалы вернутся при следующем включении зажигания. " +
                "Отчёт — files/cluster/gauges_*.txt."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(12), 0, 0)
        })
        val gaugesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        gaugesRow.addView(Button(this).apply {
            text = "Спрятать шкалы на 30 с"
            setOnClickListener { com.dash.headunit.diag.ClusterGaugesTest.start(this@CarDebugActivity) }
        })
        gaugesRow.addView(Button(this).apply {
            text = "Вернуть сейчас"
            setOnClickListener { com.dash.headunit.diag.ClusterGaugesTest.restoreNow() }
        })
        root.addView(gaugesRow)
        root.addView(TextView(this).apply {
            text = "Только на стоянке, зажигание включено, тема приборки «Штатная»: прячет ВСЁ " +
                "штатное на приборке, кроме ламп, — затемнения по краям, верхнюю и нижнюю строки, " +
                "приборы и текстовые предупреждения. Через 30 с возвращает. Если что-то пошло не так — " +
                "вернётся при следующем включении зажигания. Отчёт — files/cluster/gauges_all_*.txt."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(12), 0, 0)
        })
        val allRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        allRow.addView(Button(this).apply {
            text = "Спрятать всё на 30 с"
            setOnClickListener {
                com.dash.headunit.diag.ClusterGaugesTest.start(
                    this@CarDebugActivity, com.dash.headunit.diag.ClusterGaugesTest.Mode.ALL
                )
            }
        })
        allRow.addView(Button(this).apply {
            text = "Вернуть сейчас"
            setOnClickListener { com.dash.headunit.diag.ClusterGaugesTest.restoreNow() }
        })
        root.addView(allRow)
        root.addView(TextView(this).apply {
            text = "То же «всё штатное», но без таймера: «Спрятать всё» прячет, пока не нажмёте " +
                "«Показать всё» (или до следующего включения зажигания). Своя тема приборки может " +
                "спрятать/вернуть штатное сама — для проверки выберите «Штатную». " +
                "Отчёт — files/cluster/gauges_set_*.txt."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(12), 0, 0)
        })
        val setRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        setRow.addView(Button(this).apply {
            text = "Спрятать всё"
            setOnClickListener { com.dash.headunit.diag.ClusterGaugesTest.setAll(this@CarDebugActivity, hide = true) }
        })
        setRow.addView(Button(this).apply {
            text = "Показать всё"
            setOnClickListener { com.dash.headunit.diag.ClusterGaugesTest.setAll(this@CarDebugActivity, hide = false) }
        })
        root.addView(setRow)

        root.addView(TextView(this).apply {
            text = "GPS"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, dp(24), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "Можно ли поправлять встроенный GPS и на сколько он отстаёт. Сначала ~15 с " +
                "проверок (на это время подмена GPS снимается), затем ${com.dash.headunit.location.GpsProbe.RECORD_MS / 1000} с " +
                "записи — едьте с разгонами и торможениями. Отчёт — files/gps_probe/gps_*.txt."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        val gpsStatus = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
        val gpsButton = Button(this).apply {
            text = "Проверить GPS"
            setOnClickListener {
                com.dash.headunit.location.GpsProbe.start(this@CarDebugActivity)
                isEnabled = false
            }
        }
        root.addView(gpsButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(gpsStatus)
        gpsRefresh = {
            gpsButton.isEnabled = !com.dash.headunit.location.GpsProbe.running
            gpsStatus.text = com.dash.headunit.location.GpsProbe.status
        }
        setContentView(android.widget.ScrollView(this).apply { addView(root) })

        fun toggleLabel() {
            btnToggle.text = if (TripLog.enabled) "Лог поездок: выключить" else "Лог поездок: включить"
        }
        toggleLabel()
        val refresh: () -> Unit = {
            scope.launch {
                val (sum, lines) = withContext(Dispatchers.IO) {
                    TripLog.summary(this@CarDebugActivity) to TripLog.tail(this@CarDebugActivity, TAIL_LINES)
                }
                info.text = (if (TripLog.enabled) "Пишется. " else "Выключен. ") + sum
                gpsRefresh()
                logView.text = lines.asReversed().joinToString("\n")
            }
        }
        btnToggle.setOnClickListener {
            val on = !TripLog.enabled
            scope.launch {
                withContext(Dispatchers.IO) { TripLog.setEnabled(this@CarDebugActivity, on) }
                toggleLabel()
                refresh()
            }
        }
        btnClear.setOnClickListener {
            TripLog.clear(this)
            scope.launch { delay(200); refresh() }
        }

        scope.launch {
            while (true) {
                refresh()
                delay(2000)
            }
        }
    }

    private var gpsRefresh: () -> Unit = {}

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()

    private companion object {
        const val TAIL_LINES = 150
    }
}
