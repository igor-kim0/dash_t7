package com.dash.headunit.widgets

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dash.headunit.R
import com.dash.headunit.car.CarHub
import com.dash.headunit.car.ClusterPps
import com.dash.headunit.car.ClusterTrip
import com.dash.headunit.car.DemoData
import com.dash.headunit.car.TripTracker
import com.dash.headunit.ui.DashDialog
import com.dash.headunit.theme.DashTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Виджет «Бортовой компьютер»: метрики СРЕДНИЙ РАСХОД / ПРОБЕГ / СР. СКОРОСТЬ
 * крупно (как в «Автомобиле»), в шапке справа — иконка «Журнал поездок»
 * (завершённые поездки: глушение двигателя дописывает запись).
 *
 * Данные считает [TripTracker] из [CarHub] (одометр, топливо, моточасы).
 */
class TripWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK
) : LinearLayout(context) {

    private var view: TripTracker.TripView? = null
    private val locale = Locale("ru")

    private val content = LinearLayout(context)
    private val footerView = TextView(context)

    private val hubListener: () -> Unit = {
        // Счётчик качается в цикле опроса CarHub (журнал не зависит от вида
        // виджета); отсюда только читаем последний «вид поездки».
        view = CarHub.tripView
        render()
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL

        // Ряд: метрики (на всё свободное место) | тонкий разделитель | узкая
        // зона иконки журнала (фикс = 72dp под иконку), иконка по центру зоны.
        val mainRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val journalBtn = android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_journal)
            setColorFilter(theme.accent)
            val size = dp(72)
            layoutParams = LayoutParams(size, size)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = context.getString(R.string.trip_journal)
            isClickable = true
            isFocusable = true
            setOnClickListener { showJournal() }
        }
        val iconBox = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
        }
        iconBox.addView(journalBtn)
        val divider = android.view.View(context).apply {
            setBackgroundColor(theme.cardBorder)
        }
        content.orientation = HORIZONTAL
        content.gravity = Gravity.CENTER_VERTICAL
        mainRow.addView(content, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        mainRow.addView(divider, LayoutParams(dp(1), LayoutParams.MATCH_PARENT).apply {
            leftMargin = dp(6)
            rightMargin = dp(6)
        })
        mainRow.addView(iconBox, LayoutParams(dp(72), LayoutParams.WRAP_CONTENT))
        addView(
            mainRow,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )
        footerView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        addView(footerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        render()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    private fun render() {
        val real = view
        // Нет данных авто и накопленного пробега (эмулятор) — демо-поездка.
        val v = if (CarHub.data == null && real?.distKm == null) {
            DemoData.trip()
        } else {
            real
        }

        footerView.text = v?.cluster?.let { footer(it) }.orEmpty()
        footerView.visibility = if (footerView.text.isEmpty()) GONE else VISIBLE

        content.removeAllViews()
        content.addView(
            metric(
                context.getString(R.string.trip_avg_cons),
                v?.avgCons?.let { "%.1f".format(locale, it) },
                context.getString(R.string.trip_avg_cons_unit)
            ),
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        content.addView(
            metric(
                context.getString(R.string.trip_dist),
                v?.distKm?.let { "%.1f".format(locale, it) },
                context.getString(R.string.trip_dist_unit)
            ),
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        content.addView(
            metric(
                context.getString(R.string.trip_avg_speed),
                // Приборка даёт десятые — показываем как она.
                v?.avgSpeedKmh?.let { (if (v.cluster != null) "%.1f" else "%.0f").format(locale, it) },
                context.getString(R.string.trip_avg_speed_unit)
            ),
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
    }

    /** «После сброса 5036,6 км • Одометр 19 544 км • Запас 418 км». */
    private fun footer(c: ClusterTrip): String {
        val km = context.getString(R.string.trip_dist_unit)
        return listOfNotNull(
            c.tripSinceResetKm?.let {
                context.getString(R.string.trip_since_reset) + " " + "%.1f".format(locale, it) + " " + km
            },
            c.odometerKm?.let {
                context.getString(R.string.trip_odometer) + " " + "%,d".format(locale, it) + " " + km
            },
            c.rangeKm?.let { context.getString(R.string.trip_range) + " $it $km" }
        ).joinToString(" • ")
    }

    /** Колонка метрики: подпись / значение+единица (центрированы). */
    private fun metric(title: String, value: String?, unit: String): LinearLayout {
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        // Подпись генерируется во всю ширину колонки (дефолт вертикального
        // LinearLayout) — центрируем текст внутри, иначе она прижата влево.
        col.addView(TextView(context).apply {
            text = title
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        }
        row.addView(TextView(context).apply {
            text = value ?: "—"
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
        })
        row.addView(TextView(context).apply {
            text = unit
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(4), 0, 0, 0)
        })
        col.addView(row)
        return col
    }

    /** Диалог со списком завершённых поездок, свежие сверху. */
    private fun showJournal() {
        val entries = TripTracker.journal(context)
        val pad = dp(16)
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        if (entries.isEmpty()) {
            list.addView(TextView(context).apply {
                text = context.getString(R.string.trip_journal_empty)
                setTextColor(theme.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
        }
        val dateFmt = SimpleDateFormat("d MMMM, HH:mm", locale)
        for (e in entries) {
            val stats = listOf(
                "%.1f".format(locale, e.distKm) + " " + context.getString(R.string.trip_dist_unit),
                "%d:%02d".format(e.driveMillis / 3600000, (e.driveMillis % 3600000) / 60000),
                e.avgCons?.let {
                    "%.1f".format(locale, it) + " " + context.getString(R.string.trip_avg_cons_unit)
                } ?: "—"
            ).joinToString(" • ")
            list.addView(TextView(context).apply {
                text = dateFmt.format(Date(e.startTs))
                    .replaceFirstChar { it.uppercaseChar() }
                setTextColor(theme.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            list.addView(TextView(context).apply {
                text = stats
                setTextColor(theme.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            })
            list.addView(android.view.View(context).apply {
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(8)
                )
            })
        }
        val scroll = android.widget.ScrollView(context).apply { addView(list) }
        DashDialog.Builder(context)
            .setTitle(R.string.trip_journal)
            .setView(scroll)
            .setPositiveButton(R.string.dialog_ok, null)
            .setNeutralButton(R.string.trip_journal_clear) { _, _ ->
                TripTracker.clearJournal(context)
                Toast.makeText(context, R.string.trip_journal_cleared, Toast.LENGTH_SHORT).show()
            }
            .showFullScreen()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        CarHub.init(context)
        CarHub.addListener(hubListener)
        CarHub.ensurePolling()
    }

    override fun onDetachedFromWindow() {
        CarHub.removeListener(hubListener)
        super.onDetachedFromWindow()
    }
}
