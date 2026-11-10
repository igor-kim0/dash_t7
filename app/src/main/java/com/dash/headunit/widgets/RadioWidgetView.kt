package com.dash.headunit.widgets

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dash.headunit.R
import com.dash.headunit.radio.RadioHub
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.ThemeCard

/**
 * Виджет «Радио»: штатный FM/AM-тюнер ГУ через службу SVMedia ([RadioHub]).
 *
 * Слева диапазон, крупная частота и имя станции (RDS, только FM); справа
 * поиск назад / play-pause / поиск вперёд (акцент), над ними — переключатель
 * FM/AM и «избранное». Тап по частоте — сделать радио источником и открыть
 * штатный экран.
 */
class RadioWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK
) : LinearLayout(context) {

    private val bandView = TextView(context)
    private val freqView = TextView(context)
    private val unitView = TextView(context)
    private val stationView = TextView(context)
    private val btnBand = TextView(context)
    private val btnFav = ImageButton(context)
    private val btnPrev = ImageButton(context)
    private val btnPlay = ImageButton(context)
    private val btnNext = ImageButton(context)

    private val hubListener: () -> Unit = { render() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        // Тексты: диапазон / частота + единица / станция.
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(12), 0)
            setOnClickListener { RadioHub.open(context) }
        }
        bandView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            letterSpacing = 0.08f
        }
        freqView.apply {
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            isSingleLine = true
        }
        unitView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(6), 0, 0, 0)
        }
        stationView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            isSingleLine = true
        }
        val freqRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        freqRow.addView(freqView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        freqRow.addView(unitView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        texts.addView(bandView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        texts.addView(freqRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        texts.addView(stationView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        // Правая часть: FM/AM + избранное, под ними транспорт.
        val right = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.END
        }
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        btnBand.apply {
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = ThemeCard.square(theme.cardBorder, 10, context)
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { RadioHub.switchBand(context) }
        }
        top.addView(btnBand, LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(12) })
        styleButton(btnFav, R.drawable.ic_star_outline, theme.cardBorder, theme.secondaryText, 40, 8)
        btnFav.setOnClickListener { RadioHub.toggleFavorite() }
        top.addView(btnFav)
        right.addView(top, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        val controls = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        styleButton(btnPrev, R.drawable.ic_prev, theme.cardBorder, theme.secondaryText, 52)
        styleButton(btnPlay, R.drawable.ic_play, theme.cardBorder, theme.primaryText, 56)
        styleButton(btnNext, R.drawable.ic_next, theme.accent, theme.background, 66)
        btnPrev.setOnClickListener { RadioHub.seekDown() }
        btnPlay.setOnClickListener { RadioHub.toggle() }
        btnNext.setOnClickListener { RadioHub.seekUp() }
        controls.addView(btnPrev)
        controls.addView(btnPlay)
        controls.addView(btnNext)
        right.addView(controls, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(right, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        render()
    }

    private fun styleButton(btn: ImageButton, iconRes: Int, bg: Int, tint: Int, sizeDp: Int, padDp: Int = 10) {
        val s = dp(sizeDp)
        btn.apply {
            setImageResource(iconRes)
            background = ThemeCard.square(bg, 12, context)
            setColorFilter(tint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
            layoutParams = LayoutParams(s, s).apply { marginEnd = dp(12) }
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    // ---------- отрисовка ----------

    private fun render() {
        val s = RadioHub.state
        val band = if (s.band == RadioHub.AM) "AM" else "FM"
        bandView.text = context.getString(R.string.radio_band_label, band)
        btnBand.text = if (s.band == RadioHub.AM) "FM" else "AM"
        val (num, unit) = RadioHub.Json.splitFrequency(s.frequency)
        freqView.text = num
        unitView.text = unit
        stationView.text = when {
            RadioHub.unavailable -> context.getString(R.string.radio_unavailable)
            !RadioHub.connected && s.frequency == null -> context.getString(R.string.radio_connecting)
            s.station != null -> s.station
            s.playing == false -> context.getString(R.string.radio_paused)
            else -> ""
        }
        btnPlay.setImageResource(if (s.playing == true) R.drawable.ic_pause else R.drawable.ic_play)
        val fav = s.favorite == true
        btnFav.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_outline)
        btnFav.setColorFilter(if (fav) theme.accent else theme.secondaryText)
        btnFav.visibility = if (s.frequency != null) View.VISIBLE else View.INVISIBLE
        val enabled = !RadioHub.unavailable
        listOf<View>(btnPrev, btnPlay, btnNext, btnBand, btnFav).forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.4f
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        RadioHub.addListener(hubListener)
        RadioHub.init(context)
        RadioHub.refresh()
        render()
    }

    override fun onDetachedFromWindow() {
        RadioHub.removeListener(hubListener)
        super.onDetachedFromWindow()
    }
}
