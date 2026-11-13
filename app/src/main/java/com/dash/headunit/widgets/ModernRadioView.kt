package com.dash.headunit.widgets

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.dash.headunit.R
import com.dash.headunit.radio.RadioHub
import com.dash.headunit.radio.RadioStations
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.ThemeCard

/**
 * «Радио», вид «Современный».
 *
 * Сверху значок станции, крупное название, частота и «в эфире»; рядом
 * звезда, мини-шкала диапазона ([BandScaleView]) и транспорт. Снизу ряд
 * карточек избранного (до [RadioStations.MAX_FAVORITES]), переключатель
 * FM/AM и список станций.
 *
 * Тап по названию — карточка «О станции» ([RadioStationCard]), долгое
 * нажатие — штатный экран радио. Карточка избранного: тап — настроиться,
 * долгое нажатие — меню (имя, цвет, место, убрать).
 *
 * Переключение на станцию идёт перебором (у службы нет «настроиться на
 * частоту»), поэтому пока оно идёт, строка состояния пишет «Переключаю
 * на…», на шкале пульсирует цель, а тап по названию или шкале отменяет.
 *
 * Раскладка следует за размером блока: низкий — без ряда избранного,
 * высокий — избранное в два ряда, узкий — без шкалы, звезды и «назад».
 */
class ModernRadioView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK
) : LinearLayout(context) {

    private val avatar = StationAvatarView(context)
    private val titleView = TextView(context)
    private val subView = TextView(context)
    private val onAir = OnAirView(context, theme)
    private val statusView = TextView(context)
    private val btnStar = ImageButton(context)
    private val scale = BandScaleView(context, theme)
    private val btnPrev = ImageButton(context)
    private val btnPlay = ImageButton(context)
    private val btnNext = ImageButton(context)
    private val bandFm = TextView(context)
    private val bandAm = TextView(context)
    private val bandToggle = LinearLayout(context)
    private val btnList = ImageButton(context)
    private val tools = LinearLayout(context)

    private val topRow = LinearLayout(context)
    private val bottomRow = LinearLayout(context)
    private val presetsScroll = HorizontalScrollView(context)
    private val presetsBox = LinearLayout(context)

    private val hubListener: () -> Unit = { render() }
    private val stationsListener: () -> Unit = { render() }

    /** Подпись ряда избранного — пересобираем карточки только при изменении. */
    private var presetsSignature = ""

    /** Раскладка под размер блока. */
    private var showPresets = true
    private var presetRows = 1
    private var showScale = true
    private var showPrev = true
    private var showStar = true
    private var showBand = true
    private var avatarDp = 64

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL

        // ---------- верхний ряд ----------
        topRow.orientation = HORIZONTAL
        topRow.gravity = Gravity.CENTER_VERTICAL

        val info = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        titleView.apply {
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        subView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        statusView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, 0, 0)
        }
        val statusRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, 0)
            addView(onAir, LayoutParams(dp(16), dp(13)))
            addView(statusView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        info.addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        info.addView(subView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        info.addView(statusRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Значок и тексты — одна зона нажатия.
        val station = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(avatar, LayoutParams(dp(avatarDp), dp(avatarDp)))
            addView(info, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { onStationTap() }
            setOnLongClickListener {
                RadioHub.open(context)
                true
            }
        }
        topRow.addView(station, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.15f))

        styleIcon(btnStar, R.drawable.ic_star_outline, theme.secondaryText, null, 46, 9)
        btnStar.setOnClickListener { toggleFavorite() }
        topRow.addView(btnStar)

        scale.onTune = { RadioStationCard.tune(context, it) }
        scale.onSeek = { if (it > 0) RadioHub.seekUp() else RadioHub.seekDown() }
        scale.onCancel = { RadioHub.cancelWalk() }
        topRow.addView(scale, LayoutParams(0, dp(58), 1f).apply {
            marginStart = dp(10)
            marginEnd = dp(14)
        })

        styleIcon(btnPrev, R.drawable.ic_prev, theme.primaryText, theme.cardBorder, 52, 14)
        styleIcon(btnPlay, R.drawable.ic_play, theme.background, theme.accent, 62, 16)
        styleIcon(btnNext, R.drawable.ic_next, theme.primaryText, theme.cardBorder, 52, 14)
        btnPrev.setOnClickListener { RadioHub.seekDown() }
        btnPlay.setOnClickListener { RadioHub.toggle() }
        btnNext.setOnClickListener { RadioHub.seekUp() }
        topRow.addView(btnPrev)
        topRow.addView(btnPlay)
        topRow.addView(btnNext)
        (btnNext.layoutParams as MarginLayoutParams).marginEnd = 0

        addView(topRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ---------- нижний ряд ----------
        bottomRow.orientation = HORIZONTAL
        bottomRow.gravity = Gravity.CENTER_VERTICAL
        presetsBox.orientation = VERTICAL
        presetsScroll.isHorizontalScrollBarEnabled = false
        presetsScroll.addView(presetsBox)
        bottomRow.addView(presetsScroll, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        // FM | AM и список станций — справа внизу (или вверху, если низа нет).
        bandToggle.apply {
            orientation = HORIZONTAL
            background = ThemeCard.square(theme.cardBorder, 12, context)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        listOf(bandFm to RadioHub.FM, bandAm to RadioHub.AM).forEach { (v, band) ->
            v.text = band.uppercase()
            v.gravity = Gravity.CENTER
            v.typeface = Typeface.DEFAULT_BOLD
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            v.setOnClickListener { if (RadioHub.state.band != band) RadioHub.switchBand(context) }
            bandToggle.addView(v, LayoutParams(dp(50), dp(40)))
        }
        styleIcon(btnList, R.drawable.ic_list, theme.primaryText, theme.cardBorder, 46, 10)
        btnList.setOnClickListener { RadioStationsDialog.show(context) }
        tools.orientation = HORIZONTAL
        tools.gravity = Gravity.CENTER_VERTICAL
        tools.addView(bandToggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
        })
        tools.addView(btnList)
        (btnList.layoutParams as MarginLayoutParams).marginEnd = 0
        bottomRow.addView(tools, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        addView(bottomRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        render()
    }

    // ---------- раскладка под размер ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val d = resources.displayMetrics.density
        val wDp = w / d
        val hDp = h / d
        val presets = hDp >= 140
        val rows = if (hDp >= 270) 2 else 1
        val scaleOn = wDp >= 720
        val prev = wDp >= 430
        val star = wDp >= 500
        val band = wDp >= 380
        val av = if (hDp < 110 || wDp < 430) 48 else 64
        if (presets == showPresets && rows == presetRows && scaleOn == showScale &&
            prev == showPrev && star == showStar && band == showBand && av == avatarDp
        ) return
        showPresets = presets
        presetRows = rows
        showScale = scaleOn
        showPrev = prev
        showStar = star
        showBand = band
        avatarDp = av
        // Менять раскладку внутри layout-прохода нельзя — после него.
        post { applyLayout() }
    }

    private fun applyLayout() {
        scale.visibility = if (showScale) View.VISIBLE else View.GONE
        btnPrev.visibility = if (showPrev) View.VISIBLE else View.GONE
        btnStar.visibility = if (showStar) View.VISIBLE else View.GONE
        bandToggle.visibility = if (showBand) View.VISIBLE else View.GONE
        avatar.layoutParams = avatar.layoutParams.apply {
            width = dp(avatarDp)
            height = dp(avatarDp)
        }
        // Без ряда избранного FM/AM и список переезжают наверх.
        (tools.parent as? ViewGroup)?.removeView(tools)
        if (showPresets) {
            bottomRow.addView(tools)
            bottomRow.visibility = View.VISIBLE
        } else {
            topRow.addView(tools)
            bottomRow.visibility = View.GONE
        }
        presetsSignature = ""
        render()
    }

    // ---------- отрисовка ----------

    private fun render() {
        val s = RadioHub.state
        val freq = RadioHub.currentFrequency()
        val stations = RadioStations.load(context)
        val current = freq?.let { RadioStations.find(stations, s.band, it) }
        val walking = RadioHub.walking
        val target = RadioHub.walkTarget

        val freqText = freq?.let { RadioStations.formatFreq(s.band, it) } ?: s.frequency
        val name = current?.name ?: s.station
        titleView.text = name ?: freqText ?: context.getString(R.string.widget_radio)
        subView.text = buildList {
            if (name != null && freqText != null) add(freqText)
            add(s.band.uppercase())
            // Своё имя заслонило RDS — покажем его рядом.
            val rds = s.station
            if (current?.name != null && rds != null && rds != current.name) add(rds)
        }.joinToString("  ·  ")

        statusView.text = when {
            RadioHub.unavailable -> context.getString(R.string.radio_unavailable)
            walking && target != null -> context.getString(
                R.string.radio_tuning_to,
                RadioStations.find(stations, target.band, target.freq)?.title
                    ?: RadioStations.formatFreq(target.band, target.freq)
            )
            walking -> context.getString(R.string.radio_scanning)
            !RadioHub.connected && s.frequency == null -> context.getString(R.string.radio_connecting)
            s.playing == true -> context.getString(R.string.radio_on_air)
            s.playing == false -> context.getString(R.string.radio_paused)
            else -> ""
        }
        statusView.setTextColor(if (walking) theme.accent else theme.secondaryText)
        val playing = s.playing == true && !walking
        onAir.playing = playing
        onAir.visibility = if (RadioHub.unavailable) View.GONE else View.VISIBLE

        avatar.bind(s.band, freq, name, current?.color)
        avatar.ringColor = if (playing) theme.accent else null

        val fav = current?.favorite == true
        btnStar.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_outline)
        btnStar.setColorFilter(if (fav) theme.accent else theme.secondaryText)
        btnStar.isEnabled = freq != null

        btnPlay.setImageResource(if (s.playing == true) R.drawable.ic_pause else R.drawable.ic_play)
        styleBand(bandFm, s.band == RadioHub.FM)
        styleBand(bandAm, s.band == RadioHub.AM)

        scale.bind(s.band, stations, freq, target?.freq, walking)

        val enabled = !RadioHub.unavailable
        listOf<View>(btnPrev, btnPlay, btnNext, btnStar, bandToggle, scale).forEach {
            it.alpha = if (enabled) 1f else 0.4f
        }
        listOf<View>(btnPrev, btnPlay, btnNext, bandFm, bandAm, scale).forEach { it.isEnabled = enabled }

        renderPresets(stations, s.band, freq, current, target)
    }

    private fun styleBand(v: TextView, active: Boolean) {
        v.setTextColor(if (active) theme.background else theme.secondaryText)
        v.background = if (active) ThemeCard.square(theme.accent, 10, context) else null
    }

    // ---------- ряд избранного ----------

    private fun renderPresets(
        stations: List<RadioStations.Station>,
        band: String,
        freq: Double?,
        current: RadioStations.Station?,
        target: RadioStations.Station?
    ) {
        if (!showPresets) return
        val favs = RadioStations.favorites(stations)
        val curKey = freq?.let { RadioStations.keyOf(band, it) }
        val targetKey = target?.let { RadioStations.keyOf(it.band, it.freq) }
        val canAdd = freq != null && current?.favorite != true &&
            favs.size < RadioStations.MAX_FAVORITES
        val signature = buildString {
            favs.forEach {
                append(RadioStations.keyOf(it.band, it.freq)).append('=').append(it.title)
                    .append('/').append(it.color).append('|')
            }
            append(curKey).append(targetKey).append(canAdd).append(presetRows)
        }
        if (signature == presetsSignature) return
        presetsSignature = signature
        presetsBox.removeAllViews()

        val cards = favs.map { st ->
            val key = RadioStations.keyOf(st.band, st.freq)
            presetCard(st, isCurrent = key == curKey, isTarget = key == targetKey)
        }.toMutableList()
        if (canAdd) cards += addCard()
        if (cards.isEmpty()) {
            presetsBox.addView(TextView(context).apply {
                text = context.getString(R.string.radio_favorites_hint)
                setTextColor(theme.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
            return
        }
        // Два ряда — карточки по столбцам, чтобы порядок читался слева направо.
        val perRow = (cards.size + presetRows - 1) / presetRows
        cards.chunked(perRow.coerceAtLeast(1)).forEachIndexed { i, chunk ->
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            chunk.forEach { row.addView(it) }
            presetsBox.addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = dp(8)
            })
        }
        // Текущая станция — в зоне видимости.
        cards.firstOrNull { it.tag == TAG_CURRENT }?.let { cur ->
            presetsScroll.post {
                val left = presetsScroll.scrollX
                val right = left + presetsScroll.width
                when {
                    cur.left < left -> presetsScroll.smoothScrollTo(cur.left, 0)
                    cur.right > right -> presetsScroll.smoothScrollTo(cur.right - presetsScroll.width, 0)
                }
            }
        }
    }

    private fun presetCard(st: RadioStations.Station, isCurrent: Boolean, isTarget: Boolean): View {
        val card = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(14), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(
                    if (isCurrent) ColorUtils.setAlphaComponent(theme.accent, 0x38)
                    else ColorUtils.blendARGB(theme.cardBackground, theme.cardBorder, 0.6f)
                )
                when {
                    isCurrent -> setStroke(dp(2), theme.accent)
                    isTarget -> setStroke(dp(2), theme.accent, dp(6).toFloat(), dp(4).toFloat())
                }
            }
            if (isCurrent) tag = TAG_CURRENT
            setOnClickListener {
                if (isCurrent) RadioStationCard.show(context, st.band, st.freq)
                else RadioStationCard.tune(context, st)
            }
            setOnLongClickListener {
                RadioStationCard.favoriteMenu(context, st) { render() }
                true
            }
        }
        card.addView(StationAvatarView(context).apply {
            bind(st.band, st.freq, st.name ?: st.rds, st.color)
        }, LayoutParams(dp(36), dp(36)))
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        col.addView(TextView(context).apply {
            text = st.title
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(150)
        })
        col.addView(TextView(context).apply {
            text = RadioStations.freqText(st.band, st.freq) + if (st.band == RadioHub.AM) " AM" else ""
            setTextColor(if (isCurrent) theme.accent else theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        card.addView(col)
        card.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(58)).apply { marginEnd = dp(8) }
        return card
    }

    /** Карточка «+ В избранное» — текущую станцию в ряд. */
    private fun addCard(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(16), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setStroke(dp(2), theme.cardBorder, dp(6).toFloat(), dp(4).toFloat())
        }
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_plus)
            setColorFilter(theme.secondaryText)
        }, LayoutParams(dp(24), dp(24)))
        addView(TextView(context).apply {
            text = context.getString(R.string.radio_add_favorite)
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(8), 0, 0, 0)
        })
        setOnClickListener { toggleFavorite() }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(58)).apply { marginEnd = dp(8) }
    }

    // ---------- действия ----------

    private fun onStationTap() {
        if (RadioHub.walking) {
            RadioHub.cancelWalk()
            return
        }
        val freq = RadioHub.currentFrequency()
        if (freq == null) RadioHub.open(context)
        else RadioStationCard.show(context, RadioHub.state.band, freq)
    }

    private fun toggleFavorite() {
        val freq = RadioHub.currentFrequency() ?: return
        val s = RadioHub.state
        RadioStationCard.toggleFavorite(context, s.band, freq, s.station) { render() }
    }

    private fun styleIcon(btn: ImageButton, iconRes: Int, tint: Int, bg: Int?, sizeDp: Int, padDp: Int) {
        val size = dp(sizeDp)
        btn.apply {
            setImageResource(iconRes)
            background = bg?.let { ThemeCard.circle(it) }
            setColorFilter(tint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
            layoutParams = LayoutParams(size, size).apply { marginEnd = dp(10) }
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

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

    companion object {
        private const val TAG_CURRENT = "current"
    }
}
