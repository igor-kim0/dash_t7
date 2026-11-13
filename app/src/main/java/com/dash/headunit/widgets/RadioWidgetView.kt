package com.dash.headunit.widgets

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dash.headunit.R
import com.dash.headunit.radio.RadioHub
import com.dash.headunit.radio.RadioStations
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.ThemeCard

/**
 * Виджет «Радио»: штатный FM/AM-тюнер ГУ через службу SVMedia ([RadioHub]).
 *
 * Слева диапазон, крупная частота и имя станции (RDS, только FM); справа
 * поиск станции, переключатель FM/AM и «избранное». Тап по частоте — сделать
 * радио источником и открыть штатный экран.
 *
 * Своё название станции и избранное (до [RadioStations.MAX_FAVORITES] штук)
 * виджет ведёт сам ([RadioStations]): список станций штатного радио наружу не
 * отдаётся, поэтому он собирается перебором диапазона — см.
 * [RadioStationsDialog]. Избранное показано рядом с частотой: тап по кнопке
 * переключает станцию, долгое нажатие — своё название.
 */
class RadioWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK,
    private val widgetId: String? = null,
    private val settingsStore: com.dash.headunit.dashboard.WidgetSettingsStore? = null
) : LinearLayout(context) {

    private val bandView = TextView(context)
    private val freqView = TextView(context)
    private val unitView = TextView(context)
    private val stationView = TextView(context)
    private val favRow = LinearLayout(context)
    private val favScroll = android.widget.HorizontalScrollView(context)
    private val btnBand = TextView(context)
    private val btnSearch = ImageButton(context)
    private val btnFav = ImageButton(context)
    private val btnPrev = ImageButton(context)
    private val btnPlay = ImageButton(context)
    private val btnNext = ImageButton(context)

    private val hubListener: () -> Unit = { render() }

    /** Список станций изменился — перерисовать избранное. */
    private val stationsListener: () -> Unit = { render() }

    /** Подпись избранных — чтобы не пересобирать кнопки при каждом событии. */
    private var favSignature = ""

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
        favRow.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
        // Избранных до 12 — ряд прокручивается.
        favScroll.isHorizontalScrollBarEnabled = false
        favScroll.visibility = View.GONE
        favScroll.addView(favRow)
        texts.addView(favScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        // Правая часть: поиск станции, FM/AM + избранное, под ними транспорт.
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
        styleButton(btnSearch, R.drawable.ic_search, theme.cardBorder, theme.secondaryText, 40, 8)
        btnSearch.setOnClickListener { RadioStationsDialog.show(context) }
        top.addView(btnSearch, LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(12) })
        styleButton(btnFav, R.drawable.ic_star_outline, theme.cardBorder, theme.secondaryText, 40, 8)
        btnFav.setOnClickListener { toggleFavorite() }
        btnFav.setOnLongClickListener {
            RadioStationsDialog.show(context)
            true
        }
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
        // ⏮ ⏭ — по режиму из настроек плитки, долгое нажатие — поиск тюнером.
        val skip = RadioStationCard.skipMode(settingsStore, widgetId)
        btnPrev.setOnClickListener { RadioStationCard.skip(context, skip, -1) }
        btnPrev.setOnLongClickListener {
            RadioStationCard.skip(context, RadioStations.SKIP_SEEK, -1)
            true
        }
        btnPlay.setOnClickListener { RadioHub.toggle() }
        btnNext.setOnClickListener { RadioStationCard.skip(context, skip, 1) }
        btnNext.setOnLongClickListener {
            RadioStationCard.skip(context, RadioStations.SKIP_SEEK, 1)
            true
        }
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

        val stations = RadioStations.load(context)
        val freq = RadioHub.currentFrequency()
        val current = freq?.let { RadioStations.find(stations, s.band, it) }
        stationView.text = when {
            RadioHub.unavailable -> context.getString(R.string.radio_unavailable)
            !RadioHub.connected && s.frequency == null -> context.getString(R.string.radio_connecting)
            current?.name != null -> current.name
            s.station != null -> s.station
            s.playing == false -> context.getString(R.string.radio_paused)
            else -> ""
        }

        btnPlay.setImageResource(if (s.playing == true) R.drawable.ic_pause else R.drawable.ic_play)
        val fav = current?.favorite == true
        btnFav.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_outline)
        btnFav.setColorFilter(if (fav) theme.accent else theme.secondaryText)
        btnFav.visibility = if (s.frequency != null) View.VISIBLE else View.INVISIBLE
        val enabled = !RadioHub.unavailable
        listOf<View>(btnPrev, btnPlay, btnNext, btnBand, btnFav).forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.4f
        }

        renderFavorites(stations)
    }

    /** Кнопки избранных станций под названием. */
    private fun renderFavorites(stations: List<RadioStations.Station>) {
        val favs = RadioStations.favorites(stations)
        val signature = favs.joinToString("|") {
            RadioStations.keyOf(it.band, it.freq) + "=" + it.title
        }
        if (signature == favSignature) return
        favSignature = signature
        favRow.removeAllViews()
        if (favs.isEmpty()) {
            favScroll.visibility = View.GONE
            return
        }
        favScroll.visibility = View.VISIBLE
        favs.forEach { favRow.addView(favoriteButton(it)) }
    }

    /** Кнопка избранной станции: тап — переключить, долгое нажатие — имя. */
    private fun favoriteButton(station: RadioStations.Station): View {
        val view = TextView(context).apply {
            text = station.title
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxWidth = dp(150)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = ThemeCard.square(theme.cardBackground, 8, context)
            setOnClickListener { tuneTo(station) }
            setOnLongClickListener {
                RadioStationsDialog.askName(context, station.name ?: station.rds) { name ->
                    RadioStations.rename(context, station.band, station.freq, name)
                    render()
                }
                true
            }
        }
        return view.apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            }
        }
    }

    // ---------- избранное ----------

    /** Добавить текущую станцию в избранное или убрать из него. */
    private fun toggleFavorite() {
        val s = RadioHub.state
        val freq = RadioHub.currentFrequency() ?: return
        val stations = RadioStations.load(context)
        val current = RadioStations.find(stations, s.band, freq)
        if (current?.favorite == true) {
            RadioStations.setFavorite(context, s.band, freq, false)
            render()
            return
        }
        if (RadioStations.favorites(stations).size >= RadioStations.MAX_FAVORITES) {
            Toast.makeText(
                    context,
                    context.getString(R.string.radio_favorites_full, RadioStations.MAX_FAVORITES),
                    Toast.LENGTH_SHORT
                ).show()
            return
        }
        // Своё название спросим сразу — так избранное и делается.
        RadioStationsDialog.askName(context, current?.name ?: s.station) { name ->
            RadioStations.upsert(context, s.band, freq, s.station)
            RadioStations.rename(context, s.band, freq, name)
            if (!RadioStations.setFavorite(context, s.band, freq, true)) {
                Toast.makeText(
                    context,
                    context.getString(R.string.radio_favorites_full, RadioStations.MAX_FAVORITES),
                    Toast.LENGTH_SHORT
                ).show()
            }
            render()
        }
    }

    private fun tuneTo(station: RadioStations.Station) {
        RadioHub.tuneTo(station.band, station.freq) { ok ->
            if (!ok) {
                Toast.makeText(
                    context, R.string.radio_station_not_found, Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        RadioHub.addListener(hubListener)
        RadioStations.addListener(stationsListener)
        RadioHub.init(context)
        RadioHub.refresh()
        render()
    }

    override fun onDetachedFromWindow() {
        RadioHub.removeListener(hubListener)
        RadioStations.removeListener(stationsListener)
        super.onDetachedFromWindow()
    }
}
