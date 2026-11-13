package com.dash.headunit.widgets

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import com.dash.headunit.R
import com.dash.headunit.dashboard.PickerGrid
import com.dash.headunit.radio.RadioHub
import com.dash.headunit.radio.RadioStations
import com.dash.headunit.radio.RadioStats
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.ThemeCard
import com.dash.headunit.theme.ThemeStore
import com.dash.headunit.ui.DashDialog

/**
 * Экран «Радиостанции» для виджета «Радио»: вкладки «Избранное | FM | AM |
 * Недавние», плитки станций, поиск, сортировка, сканирование, своё название
 * и избранное (до [RadioStations.MAX_FAVORITES] штук).
 *
 * Список станций штатного радио наружу не отдаётся (docs/research/radio-aidl.md),
 * поэтому он собирается перебором диапазона — теми же стрелками «вперёд/назад»,
 * которыми ходят по станциям в штатном радио. Своё название и избранное виджет
 * хранит у себя.
 */
object RadioStationsDialog {

    /** Открыть диалог со списком станций. */
    fun show(context: Context) {
        Screen(context).open()
    }

    /**
     * Диалог своего названия станции. [onName] получит имя (пустое — убрать).
     */
    fun askName(context: Context, current: String?, onName: (String?) -> Unit) {
        val field = EditText(context).apply {
            setText(current ?: "")
            setSelection(text.length)
            setSingleLine()
            hint = context.getString(R.string.radio_name_hint)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 8), dp(context, 24), 0)
            addView(field, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        DashDialog.Builder(context)
            .setTitle(R.string.radio_name_title)
            .setView(box)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                onName(field.text.toString().trim().takeIf { it.isNotEmpty() })
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    /**
     * Диалог добавления станции вручную: частота и название.
     * [onAdd] получит диапазон, частоту и название.
     */
    fun askStation(context: Context, onAdd: (band: String, freq: Double, name: String?) -> Unit) {
        val band = RadioHub.state.band
        val freq = EditText(context).apply {
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = if (band == RadioHub.AM) {
                context.getString(R.string.radio_add_freq_khz)
            } else {
                context.getString(R.string.radio_add_freq_mhz)
            }
        }
        val name = EditText(context).apply {
            setSingleLine()
            hint = context.getString(R.string.radio_name_hint)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 8), dp(context, 24), 0)
            addView(freq, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(name, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        DashDialog.Builder(context)
            .setTitle(R.string.radio_add_title)
            .setView(box)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val value = freq.text.toString().trim().replace(',', '.').toDoubleOrNull()
                if (value == null || value <= 0) {
                    Toast.makeText(context, R.string.radio_add_bad_freq, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onAdd(band, value, name.text.toString().trim().takeIf { it.isNotEmpty() })
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    // ---------- экран ----------

    /**
     * Экран станций: вкладки «Избранное | FM | AM | Недавние», плитки
     * станций сеткой, поиск, сортировка, сканирование диапазона со шкалой,
     * ручное добавление и уборка ненайденных станций.
     *
     * Тап по плитке — настроиться (экран остаётся открытым, видно ход
     * переключения), долгое нажатие — меню станции
     * ([RadioStationCard.stationMenu]).
     */
    private class Screen(private val context: Context) {

        private val theme: DashTheme = ThemeStore(ThemeStore.PREFS, context).current()
        private val prefs = context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)

        private var tab = TAB_FAVORITES
        private var sort = prefs.getString(KEY_SORT, RadioStations.SORT_FREQ) ?: RadioStations.SORT_FREQ

        private val search = EditText(context).apply {
            setSingleLine()
            hint = context.getString(R.string.radio_search_hint)
        }
        private val tabViews = linkedMapOf<String, TextView>()
        private val sortViews = linkedMapOf<String, TextView>()
        private val sortBox = LinearLayout(context)
        private val btnScan = actionButton(R.drawable.ic_rotate, "")
        private val btnAdd = actionButton(R.drawable.ic_plus, context.getString(R.string.radio_manual))
        private val btnClean = actionButton(R.drawable.ic_trash, "")
        private val scale = BandScaleView(context, theme)
        private val status = TextView(context).apply {
            textSize = 15f
            setTextColor(theme.secondaryText)
            setPadding(0, dp(6), 0, 0)
        }
        /**
         * Сетка станций. Минимальная высота — с экран: прокрутка всё равно
         * ограничит её, зато окно не прыгает по высоте при смене вкладок.
         */
        private val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = context.resources.displayMetrics.heightPixels
        }

        /** Ключи станций, найденных идущим сканированием. */
        private val found = mutableSetOf<String>()
        private var scanBand = RadioHub.FM

        /** Перебор остановили по кнопке — итог не показываем. */
        private var stopped = false

        /** Подпись показанного — не пересобирать сетку вхолостую. */
        private var signature = ""

        private val hubListener: () -> Unit = { refresh() }
        private val stationsListener: () -> Unit = { refresh() }

        fun open() {
            val all = RadioStations.load(context)
            tab = when {
                RadioStations.favorites(all).isNotEmpty() -> TAB_FAVORITES
                RadioHub.state.band == RadioHub.AM -> TAB_AM
                else -> TAB_FM
            }

            // Ряд 1: вкладки и сортировка.
            val tabs = segmentBox()
            listOf(
                TAB_FAVORITES to R.string.radio_tab_favorites,
                TAB_FM to R.string.radio_tab_fm,
                TAB_AM to R.string.radio_tab_am,
                TAB_RECENT to R.string.radio_tab_recent
            ).forEach { (id, label) ->
                val v = segment(context.getString(label)) {
                    tab = id
                    signature = ""
                    refresh()
                }
                tabViews[id] = v
                tabs.addView(v)
            }
            sortBox.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            sortBox.addView(TextView(context).apply {
                text = context.getString(R.string.radio_sort)
                textSize = 15f
                setTextColor(theme.secondaryText)
                setPadding(0, 0, dp(10), 0)
            })
            val sorts = segmentBox()
            listOf(
                RadioStations.SORT_FREQ to R.string.radio_sort_freq,
                RadioStations.SORT_NAME to R.string.radio_sort_name,
                RadioStations.SORT_LISTENED to R.string.radio_sort_listened
            ).forEach { (id, label) ->
                val v = segment(context.getString(label)) {
                    sort = id
                    prefs.edit().putString(KEY_SORT, id).apply()
                    signature = ""
                    refresh()
                }
                sortViews[id] = v
                sorts.addView(v)
            }
            sortBox.addView(sorts)
            val row1 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(tabs)
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(sortBox)
            }

            // Ряд 2: поиск и действия.
            val row2 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                listOf(btnScan, btnAdd, btnClean).forEach {
                    addView(it, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)
                    ).apply { marginStart = dp(10) })
                }
            }
            btnScan.setOnClickListener { scan() }
            btnAdd.setOnClickListener { addStation() }
            btnClean.setOnClickListener { cleanMissing() }

            // Ряд 3: шкала хода сканирования (видна только во время него).
            scale.onCancel = { stopScan() }
            scale.onSeek = {}
            scale.onTune = { RadioStationCard.tune(context, it) }

            val header = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), 0, dp(24), dp(8))
                addView(row1)
                addView(row2, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) })
                addView(scale, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(58)
                ).apply { topMargin = dp(8) })
                addView(status)
            }

            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    refresh()
                }
            })

            val dialog = DashDialog.Builder(context)
                .setTitle(R.string.radio_stations_title)
                .setIcon(R.drawable.ic_radio)
                .setWidthDp(1140)
                .setHeaderView(header)
                .setView(body)
                .setPositiveButton(R.string.radio_card_close, null)
                .showFullScreen()
            // После первой раскладки — ровно по видимой высоте прокрутки:
            // окно не прыгает и пустоту не прокрутить.
            body.post {
                (body.parent as? View)?.height?.takeIf { it > 0 }?.let { body.minimumHeight = it }
            }
            RadioHub.addListener(hubListener)
            RadioStations.addListener(stationsListener)
            dialog.setOnDismissListener {
                RadioHub.removeListener(hubListener)
                RadioStations.removeListener(stationsListener)
            }
            refresh()
        }

        // ---------- отрисовка ----------

        private fun refresh() {
            val all = RadioStations.load(context)
            val walking = RadioHub.walking
            val scanning = walking && RadioHub.walkTarget == null

            tabViews.forEach { (id, v) ->
                val count = when (id) {
                    TAB_FAVORITES -> RadioStations.favorites(all).size
                    TAB_FM -> all.count { it.band == RadioHub.FM }
                    TAB_AM -> all.count { it.band == RadioHub.AM }
                    else -> null
                }
                val label = v.tag as String
                v.text = if (count != null && count > 0) "$label  $count" else label
                styleSegment(v, id == tab)
            }
            sortViews.forEach { (id, v) -> styleSegment(v, id == sort) }
            sortBox.visibility = if (tab == TAB_FM || tab == TAB_AM) View.VISIBLE else View.GONE

            // Действия: сканирование — по текущему диапазону тюнера.
            val band = RadioHub.state.band
            setAction(
                btnScan,
                if (scanning) context.getString(R.string.radio_scan_stop)
                else context.getString(R.string.radio_scan_band, band.uppercase())
            )
            val cleanBand = tabBand()
            val missing = all.count { it.missing && !it.favorite && (cleanBand == null || it.band == cleanBand) }
            setAction(btnClean, context.getString(R.string.radio_clean_missing, missing))
            btnClean.visibility = if (missing > 0 && tabBand() != null) View.VISIBLE else View.GONE

            val freq = RadioHub.currentFrequency()
            scale.visibility = if (scanning) View.VISIBLE else View.GONE
            if (scanning) scale.bind(band, all, freq, null, true)

            val target = RadioHub.walkTarget
            status.text = when {
                RadioHub.unavailable -> context.getString(R.string.radio_unavailable)
                scanning -> context.getString(R.string.radio_scan_progress_band, found.size)
                walking && target != null -> context.getString(
                    R.string.radio_tuning,
                    RadioStations.find(all, target.band, target.freq)?.title
                        ?: RadioStations.formatFreq(target.band, target.freq)
                )
                else -> status.text
            }

            fillGrid(all, freq, target)
        }

        /** Диапазон вкладки (null — все). */
        private fun tabBand(): String? = when (tab) {
            TAB_FM -> RadioHub.FM
            TAB_AM -> RadioHub.AM
            else -> null
        }

        private fun fillGrid(
            all: List<RadioStations.Station>,
            freq: Double?,
            target: RadioStations.Station?
        ) {
            val s = RadioHub.state
            val curKey = freq?.let { RadioStations.keyOf(s.band, it) }
            val targetKey = target?.let { RadioStations.keyOf(it.band, it.freq) }
            val playing = s.playing == true && !RadioHub.walking
            val now = System.currentTimeMillis()

            // Станции вкладки и подпись под частотой.
            val stats = RadioStats.all(context)
            val items: List<Pair<RadioStations.Station, String?>> = when (tab) {
                TAB_FAVORITES -> RadioStations.favorites(all).map { it to null }
                TAB_RECENT -> stats.entries
                    .sortedByDescending { it.value.last }
                    .take(RECENT_MAX)
                    .mapNotNull { (key, e) ->
                        val st = RadioStations.fromKey(key) ?: return@mapNotNull null
                        val known = RadioStations.find(all, st.band, st.freq) ?: st
                        known to RadioStats.formatLast(e.last, now)
                    }
                else -> RadioStations.sorted(
                    all.filter { it.band == tabBand() },
                    sort,
                    stats.mapValues { it.value.totalMs }
                ).map { st ->
                    st to if (sort == RadioStations.SORT_LISTENED) {
                        stats[RadioStations.keyOf(st.band, st.freq)]?.let { RadioStats.formatDuration(it.totalMs) }
                    } else {
                        null
                    }
                }
            }
            val query = search.text.toString()
            val shown = if (query.isBlank()) items else {
                val keep = RadioStations.search(items.map { it.first }, query).toSet()
                items.filter { it.first in keep }
            }

            val sig = buildString {
                append(tab).append(sort).append(query).append('|').append(curKey).append(targetKey).append(playing)
                shown.forEach { (st, note) ->
                    append(RadioStations.keyOf(st.band, st.freq)).append(st.title).append(st.color)
                        .append(st.favorite).append(st.missing).append(note).append(';')
                }
            }
            if (sig == signature) return
            signature = sig
            body.removeAllViews()

            if (shown.isEmpty()) {
                body.addView(TextView(context).apply {
                    text = context.getString(
                        when {
                            query.isNotBlank() -> R.string.radio_stations_empty
                            tab == TAB_FAVORITES -> R.string.radio_favorites_none
                            tab == TAB_RECENT -> R.string.radio_recent_none
                            else -> R.string.radio_stations_none
                        }
                    )
                    textSize = 17f
                    setTextColor(theme.secondaryText)
                    setPadding(dp(28), dp(16), dp(28), dp(16))
                })
                return
            }
            val grid = PickerGrid(context, COLUMNS, dp(10)).apply {
                setPadding(dp(24), dp(4), dp(24), dp(8))
            }
            shown.forEach { (st, note) ->
                val key = RadioStations.keyOf(st.band, st.freq)
                grid.addView(tile(st, note, key == curKey, key == targetKey, key == curKey && playing))
            }
            body.addView(grid, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        /** Плитка станции: значок, название, частота (или подпись), звезда избранного. */
        private fun tile(
            st: RadioStations.Station,
            note: String?,
            isCurrent: Boolean,
            isTarget: Boolean,
            isPlaying: Boolean
        ): View {
            val frame = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(
                        if (isCurrent) ColorUtils.setAlphaComponent(theme.accent, 0x30)
                        else ColorUtils.blendARGB(theme.cardBackground, theme.cardBorder, 0.45f)
                    )
                    when {
                        isCurrent -> setStroke(dp(2), theme.accent)
                        isTarget -> setStroke(dp(2), theme.accent, dp(6).toFloat(), dp(4).toFloat())
                        else -> setStroke(dp(1), theme.cardBorder)
                    }
                }
                if (st.missing) alpha = 0.45f
                setOnClickListener {
                    if (isCurrent) {
                        RadioStationCard.show(context, st.band, st.freq)
                    } else {
                        status.text = context.getString(R.string.radio_tuning, st.title)
                        RadioStationCard.tune(context, st)
                    }
                }
                setOnLongClickListener {
                    RadioStationCard.stationMenu(context, st, withMove = tab == TAB_FAVORITES) { refresh() }
                    true
                }
            }
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }
            col.addView(StationAvatarView(context).apply {
                bind(st.band, st.freq, st.name ?: st.rds, st.color)
                if (isPlaying) ringColor = theme.accent
            }, LinearLayout.LayoutParams(dp(60), dp(60)))
            col.addView(TextView(context).apply {
                text = st.title
                textSize = 16f
                setTextColor(theme.primaryText)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(8), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val freqRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            if (isPlaying) {
                freqRow.addView(OnAirView(context, theme).apply { playing = true },
                    LinearLayout.LayoutParams(dp(14), dp(11)).apply { marginEnd = dp(6) })
            }
            freqRow.addView(TextView(context).apply {
                // Без названия частота уже в заголовке — тут только диапазон.
                val unnamed = st.name.isNullOrBlank() && st.rds.isNullOrBlank()
                text = if (unnamed) st.band.uppercase()
                else RadioStations.freqText(st.band, st.freq) + " " + st.band.uppercase()
                textSize = 14f
                setTextColor(if (isCurrent) theme.accent else theme.secondaryText)
                maxLines = 1
            })
            col.addView(freqRow)
            val line = note ?: if (st.missing) context.getString(R.string.radio_missing) else null
            if (line != null) {
                col.addView(TextView(context).apply {
                    text = line
                    textSize = 13f
                    setTextColor(theme.secondaryText)
                    gravity = Gravity.CENTER
                    maxLines = 1
                })
            }
            frame.addView(col, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            // Звезда: тап — в избранное / из избранного.
            frame.addView(ImageButton(context).apply {
                setImageResource(if (st.favorite) R.drawable.ic_star else R.drawable.ic_star_outline)
                setColorFilter(if (st.favorite) theme.accent else ColorUtils.setAlphaComponent(theme.secondaryText, 0x99))
                background = null
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener {
                    RadioStationCard.toggleFavorite(context, st.band, st.freq, st.rds) { refresh() }
                }
            }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END))
            return frame
        }

        // ---------- действия ----------

        /** Сканирование диапазона тюнера: собрать станции, отметить ненайденные. */
        private fun scan() {
            if (RadioHub.walking && RadioHub.walkTarget == null) {
                stopScan()
                return
            }
            if (RadioHub.unavailable) {
                Toast.makeText(context, R.string.radio_unavailable, Toast.LENGTH_SHORT).show()
                return
            }
            stopped = false
            found.clear()
            scanBand = RadioHub.state.band
            RadioHub.scanStations({ station ->
                found += RadioStations.keyOf(station.band, station.freq)
                RadioStations.upsert(context, station.band, station.freq, station.rds)
            }) { ok ->
                if (!stopped) {
                    if (ok) {
                        RadioStations.markScan(context, scanBand, found)
                        val missing = RadioStations.load(context)
                            .count { it.band == scanBand && it.missing }
                        status.text = context.getString(R.string.radio_scan_result, found.size, missing)
                    } else {
                        status.text = context.getString(R.string.radio_scan_failed)
                    }
                }
                stopped = false
                refresh()
            }
            refresh()
        }

        private fun stopScan() {
            RadioHub.cancelWalk()
            stopped = true
            status.text = context.getString(R.string.radio_scan_stopped, found.size)
            refresh()
        }

        private fun addStation() {
            askStation(context) { band, freq, name ->
                RadioStations.upsert(context, band, freq)
                RadioStations.rename(context, band, freq, name)
                refresh()
            }
        }

        /** Убрать ненайденные станции вкладки (избранные не трогаем). */
        private fun cleanMissing() {
            val band = tabBand()
            val n = RadioStations.load(context)
                .count { it.missing && !it.favorite && (band == null || it.band == band) }
            DashDialog.Builder(context)
                .setTitle(R.string.radio_clean_title)
                .setMessage(context.getString(R.string.radio_clean_text, n))
                .setPositiveButton(R.string.radio_remove_ok) { _, _ ->
                    RadioStations.removeMissing(context, band)
                    refresh()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .showFullScreen()
        }

        // ---------- вид ----------

        private fun segmentBox() = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ThemeCard.square(theme.cardBorder, 12, context)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }

        private fun segment(label: String, onClick: () -> Unit) = TextView(context).apply {
            text = label
            tag = label
            textSize = 16f
            gravity = Gravity.CENTER
            minWidth = dp(64)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { onClick() }
        }

        private fun styleSegment(v: TextView, active: Boolean) {
            v.setTextColor(if (active) theme.background else theme.primaryText)
            v.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            v.background = if (active) ThemeCard.square(theme.accent, 10, context) else null
        }

        /** Кнопка-действие: значок и подпись. */
        private fun actionButton(icon: Int, label: String) = TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(theme.primaryText)
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(8)
            setPadding(dp(14), 0, dp(16), 0)
            background = ThemeCard.card(context, theme)
            val d = androidx.core.content.ContextCompat.getDrawable(context, icon)?.mutate()
            d?.setTint(theme.primaryText)
            d?.setBounds(0, 0, dp(22), dp(22))
            setCompoundDrawablesRelative(d, null, null, null)
        }

        private fun setAction(v: TextView, label: String) {
            if (v.text != label) v.text = label
        }

        private fun dp(v: Int): Int = dp(context, v)
    }

    private const val TAB_FAVORITES = "favorites"
    private const val TAB_FM = "fm"
    private const val TAB_AM = "am"
    private const val TAB_RECENT = "recent"
    private const val COLUMNS = 7
    private const val RECENT_MAX = 24
    private const val UI_PREFS = "radio_stations_ui"
    private const val KEY_SORT = "sort"


    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
        ).toInt()
}
