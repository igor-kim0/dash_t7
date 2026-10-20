package com.dash.headunit.widgets

import android.content.Context
import android.text.SpannableString
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
import com.dash.headunit.theme.ThemeCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Виджет «Бортовой компьютер»: метрики СРЕДНИЙ РАСХОД / ПРОБЕГ / СР. СКОРОСТЬ
 * крупно (как в «Автомобиле»); тап по виджету открывает журнал поездок
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

        // Тап в любом месте виджета — журнал поездок.
        isClickable = true
        isFocusable = true
        setOnClickListener { showJournal() }

        val mainRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        content.orientation = HORIZONTAL
        content.gravity = Gravity.CENTER_VERTICAL
        mainRow.addView(content, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
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

    /**
     * Диалог со списком завершённых поездок, свежие сверху. Современный вид:
     * карточки — слева дата/время, справа пробег акцентом, ниже мини-статы
     * (время в пути / ср. скорость / расход).
     */
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
        val timeFmt = SimpleDateFormat("HH:mm", locale)
        val dateFmt = SimpleDateFormat("d MMMM, EEEE", locale)
        val cal = java.util.Calendar.getInstance()
        fun dayLabel(ts: Long): String {
            cal.timeInMillis = ts
            val day = cal.get(java.util.Calendar.DAY_OF_YEAR)
            val year = cal.get(java.util.Calendar.YEAR)
            cal.timeInMillis = System.currentTimeMillis()
            val today = cal.get(java.util.Calendar.DAY_OF_YEAR)
            val thisYear = cal.get(java.util.Calendar.YEAR)
            return when {
                day == today && year == thisYear ->
                    context.getString(R.string.trip_journal_today)
                isYesterday(day, year, cal) ->
                    context.getString(R.string.trip_journal_yesterday)
                else -> dateFmt.format(Date(ts))
                    .capitalizeFirst()
            }
        }
        fun duration(millis: Long): String = when {
            millis >= 3_600_000L -> "%d %s %d %s".format(
                locale, millis / 3_600_000L,
                context.getString(R.string.trip_journal_h),
                (millis % 3_600_000L) / 60_000,
                context.getString(R.string.trip_journal_min)
            )
            else -> "%d %s".format(
                millis / 60_000, context.getString(R.string.trip_journal_min)
            )
        }
        // Итоги по дням: группируем записи по календарному дню начала
        // поездки — [день: (поездок, км, время в пути)].
        val dayCal = java.util.Calendar.getInstance()
        fun dayKey(ts: Long): Long {
            dayCal.timeInMillis = ts
            dayCal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            dayCal.set(java.util.Calendar.MINUTE, 0)
            dayCal.set(java.util.Calendar.SECOND, 0)
            dayCal.set(java.util.Calendar.MILLISECOND, 0)
            return dayCal.timeInMillis
        }
        val dayAggs = LinkedHashMap<Long, IntArray>()
        for (e in entries) {
            val a = dayAggs.getOrPut(dayKey(e.startTs)) { IntArray(3) }
            a[0]++
            a[1] = (a[1] + e.distKm * 100).toInt()
            a[2] += e.driveMillis.toInt()
        }

        /** Полоса-итог дня: «Сегодня · 2 поездки [25,3 км · 51 мин]». */
        fun daySummary(dayTs: Long, agg: IntArray) {
            val (count, distCm, timeMs) = agg
            val dayText = dayLabel(dayTs)
            val rest = "  ·  " + ridesLabel(count)
            val head = SpannableString(dayText + rest)
            head.setSpan(
                android.text.style.StyleSpan(
                    android.graphics.Typeface.BOLD
                ), 0, dayText.length, 0
            )
            val band = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), theme.accent)
                }
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            band.addView(
                TextView(context).apply {
                    text = head
                    setTextColor(theme.accent)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(8)
                }
            )
            band.addView(TextView(context).apply {
                text = "%.1f %s · %s".format(
                    locale,
                    distCm / 100.0,
                    context.getString(R.string.trip_dist_unit),
                    duration(timeMs.toLong())
                )
                setTextColor(theme.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
            list.addView(band)
        }

        var currentDay: Long = -1L
        for (e in entries) {
            // Заголовок дня с итогами перед первой поездкой группы.
            val day = dayKey(e.startTs)
            if (day != currentDay) {
                currentDay = day
                daySummary(day, dayAggs.getValue(day))
            }
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = ThemeCard.card(context, theme)
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            val head = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(TextView(context).apply {
                    text = dayLabel(e.startTs)
                    setTextColor(theme.secondaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })
                addView(TextView(context).apply {
                    layoutParams =
                        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(4)
                        }
                    text = timeFmt.format(Date(e.startTs)) + " – " +
                        timeFmt.format(Date(e.endTs)) + "  •  " + duration(e.driveMillis)
                    setTextColor(theme.primaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                })
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            // Пробег — главный акцент карточки.
            head.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.BOTTOM
                addView(TextView(context).apply {
                    text = "%.1f".format(locale, e.distKm)
                    setTextColor(theme.accent)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                })
                addView(TextView(context).apply {
                    text = " " + context.getString(R.string.trip_dist_unit)
                    setTextColor(theme.secondaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setPadding(dp(2), 0, 0, dp(4))
                })
            })
            card.addView(head)
            // Мини-статы: три колонки, как в самом виджете.
            val stats = LinearLayout(context).apply {
                orientation = HORIZONTAL
            }
            fun stat(label: String, value: String) {
                stats.addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams =
                        LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                            topMargin = dp(12)
                        }
                    addView(TextView(context).apply {
                        text = label
                        setTextColor(theme.secondaryText)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    })
                    addView(TextView(context).apply {
                        text = value
                        setTextColor(theme.primaryText)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    })
                })
            }
            stat(
                context.getString(R.string.trip_journal_time),
                duration(e.driveMillis)
            )
            stat(
                context.getString(R.string.trip_journal_speed),
                e.avgSpeedKmh?.let {
                    "%.0f".format(locale, it) + " " +
                        context.getString(R.string.trip_avg_speed_unit)
                } ?: "—"
            )
            stat(
                context.getString(R.string.trip_journal_cons),
                e.avgCons?.let {
                    "%.1f".format(locale, it) + " " +
                        context.getString(R.string.trip_avg_cons_unit)
                } ?: "—"
            )
            card.addView(stats)
            list.addView(card)
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

    /** Завтрашний/вчерашний переход через границу года учитывает. */
    private fun isYesterday(day: Int, year: Int, nowCal: java.util.Calendar): Boolean {
        val now = nowCal.clone() as java.util.Calendar
        now.add(java.util.Calendar.DAY_OF_YEAR, -1)
        return now.get(java.util.Calendar.DAY_OF_YEAR) == day &&
            now.get(java.util.Calendar.YEAR) == year
    }

    private fun String.capitalizeFirst(): String =
        replaceFirstChar { it.uppercaseChar() }

    /**
     * «1 поездка / 2 поездки / 5 поездок»: формы из
     * [R.string.trip_journal_rides_fmt] («%d поездка|поездки|поездок»),
     * правила русского склонения выбираем руками — plurals ресурсов
     * зависит от локали системы, а объективно текст в приложении русский.
     */
    private fun ridesLabel(n: Int): String {
        val forms = context.getString(R.string.trip_journal_rides_fmt)
            .substringAfter("%d ").split("|")
        val i = when {
            n % 10 == 1 && n % 100 != 11 -> 0
            n % 10 in 2..4 && n % 100 !in 12..14 -> 1
            else -> 2
        }
        return "$n ${forms.getOrElse(i) { forms.last() }}"
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
