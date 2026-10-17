package com.dash.headunit.widgets

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.dash.headunit.R
import com.dash.headunit.car.CarHub
import com.dash.headunit.car.DemoData
import com.dash.headunit.theme.DashTheme
import java.util.Locale

/**
 * Индекс подсвеченной ячейки селектора: 0=R, 1=N, 2=P, 3..9=цифры 1..7,
 * -1 — ничего не подсвечивать.
 *
 * Карта Desay (4,26) выведена из логов ГУ: 1=P и 2=R строго (точные
 * переходы туда-обратно); дальше значение = 4 + передача в D:
 * реальная 1-я читается как 5 (жалоба «показывает 3-ю» при старом
 * маппинге raw-1), 8 на 42 км/ч = 4-я (а не «8-я на 42» — прошлая
 * теория требовала невозможного), дауншифты 8→5 к остановке и
 * 5 при 0 км/ч = D1 на месте (а не «5-я на месте»).
 * N=3 — вывод по порядку (в записях не встречалась!): включить N —
 * должна загореться синяя N, иначе сообщить сырое значение (тестовый
 * виджет). 4 и 12+ — неизвестно, не подсвечиваем.
 */
internal fun gearIndex(raw: Int?): Int = when (raw) {
    2 -> 0 // R
    3 -> 1 // N (вывод, требует проверки на ГУ)
    1 -> 2 // P (D1 = 5, неоднозначности с тормозом больше нет)
    // Цифра raw-4 в списке [R,N,P,1..7]: индекс = 3 + (raw-4-1) = raw-2.
    else -> if (raw != null && raw in 5..11) raw - 2 else -1 // 5→«1» … 11→«7»
}

/**
 * Виджет «Спидометр»: скорость крупно и обороты (×1000) мельче — в одну
 * строку по базовой линии; внизу блока ряд селектора R N P 1–7 — текущая
 * подсвечена крупно (R жёлтая, N синяя, P зелёная, цифры белые).
 *
 * Скорость — VHAL, при отказе прав — приборка (PPS); обороты — Desay
 * (4,62/4), при пропуске чтения — приборка; селектор — Desay (4,26).
 * Тап — обновить данные авто.
 */
class SpeedWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK
) : LinearLayout(context) {

    private val locale = Locale("ru")
    private val hubListener: () -> Unit = { render() }

    private val speedValue = TextView(context)
    private val rpmValue = TextView(context)
    private val gearCells = mutableListOf<TextView>()

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER

        // BOTTOM (не CENTER) — иначе LinearLayout не выравнивает по базовой линии.
        val speedRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        }
        speedValue.apply {
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
        }
        val speedUnit = TextView(context).apply {
            text = context.getString(R.string.speed_unit)
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(8), 0, 0, 0)
            gravity = Gravity.BOTTOM
        }
        speedRow.addView(speedValue, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        speedRow.addView(speedUnit, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        rpmValue.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        }
        val rpmUnit = TextView(context).apply {
            text = context.getString(R.string.speed_rpm_unit)
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(6), 0, 0, 0)
            gravity = Gravity.BOTTOM
        }
        speedRow.addView(rpmValue, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(28)
        })
        speedRow.addView(rpmUnit, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        // Строка скорости занимает всё над селектором и стоит по центру.
        addView(FrameLayout(context).apply {
            addView(speedRow, FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ))
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // Селектор — у нижнего края блока, ячейки по базовой линии.
        val gearRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            setPadding(0, 0, 0, dp(10))
        }
        for (label in GEAR_LABELS) {
            val cell = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(theme.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, GEAR_SMALL_SP)
            }
            gearCells.add(cell)
            gearRow.addView(cell, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(gearRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        setOnClickListener { CarHub.refresh() }
        render()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    private fun render() {
        val d = CarHub.data ?: DemoData.car()
        speedValue.text = d.speedKmh?.let { "%.0f".format(locale, it) } ?: "—"
        rpmValue.text = d.rpm?.let { "%.1f".format(locale, it / 1000f) } ?: "—"

        val current = gearIndex(d.gearRaw)
        gearCells.forEachIndexed { i, cell ->
            if (i == current) {
                cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, GEAR_BIG_SP)
                cell.setTextColor(
                    when (i) {
                        0 -> GEAR_R
                        1 -> GEAR_N
                        2 -> theme.success
                        else -> theme.primaryText
                    }
                )
            } else {
                cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, GEAR_SMALL_SP)
                cell.setTextColor(theme.secondaryText)
            }
        }
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

    companion object {
        private val GEAR_LABELS = listOf("R", "N", "P", "1", "2", "3", "4", "5", "6", "7")

        private const val GEAR_SMALL_SP = 16f
        private const val GEAR_BIG_SP = 30f

        private val GEAR_R = 0xFFFDD835.toInt()
        private val GEAR_N = 0xFF42A5F5.toInt()
    }
}
