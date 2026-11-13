package com.dash.headunit.widgets

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dash.headunit.R
import com.dash.headunit.dashboard.WidgetSettingsStore
import com.dash.headunit.radio.RadioHub
import com.dash.headunit.radio.RadioStations
import com.dash.headunit.radio.RadioStats
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.ThemeCard
import com.dash.headunit.theme.ThemeStore
import com.dash.headunit.ui.DashDialog

/**
 * Карточка «О станции» и действия со станцией для современного вида
 * «Радио» ([ModernRadioView]): избранное, своё название, цвет значка,
 * место в ряду избранного.
 *
 * Карточка показывает название, RDS-имя, частоту, когда и сколько
 * станцию слушали ([RadioStats]), избранное ГУ (только у играющей —
 * служба сообщает его лишь для текущей станции).
 */
object RadioStationCard {

    /** Станция для карточки: из своего списка или текущая (её может не быть в списке). */
    private fun resolve(context: Context, band: String, freq: Double): RadioStations.Station {
        val known = RadioStations.find(RadioStations.load(context), band, freq)
        if (known != null) return known
        val rds = if (isCurrent(band, freq)) RadioHub.state.station else null
        return RadioStations.Station(band, RadioStations.normalize(band, freq), rds = rds)
    }

    private fun isCurrent(band: String, freq: Double): Boolean {
        val cur = RadioHub.currentFrequency() ?: return false
        return RadioHub.state.band == band && RadioStations.sameFreq(band, cur, freq)
    }

    // ---------- карточка ----------

    /** Открыть карточку станции [band]/[freq]. */
    fun show(context: Context, band: String, freq: Double) {
        val theme = ThemeStore(ThemeStore.PREFS, context).current()
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var dialog: Dialog? = null
        lateinit var fill: () -> Unit
        fill = {
            box.removeAllViews()
            buildCard(context, theme, box, band, freq, refresh = { fill() }, close = { dialog?.dismiss() })
        }
        fill()
        dialog = DashDialog.Builder(context)
            .setWidthDp(720)
            .setView(box)
            .setPositiveButton(R.string.radio_card_close, null)
            .showFullScreen()
    }

    private fun buildCard(
        context: Context,
        theme: DashTheme,
        box: LinearLayout,
        band: String,
        freq: Double,
        refresh: () -> Unit,
        close: () -> Unit
    ) {
        val st = resolve(context, band, freq)
        val current = isCurrent(band, freq)
        val dp = { v: Int -> dp(context, v) }

        // Шапка: большой значок, название, частота, RDS.
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), 0, dp(28), dp(16))
        }
        val avatar = StationAvatarView(context).apply {
            bind(st.band, st.freq, st.name ?: st.rds, st.color)
            if (current && RadioHub.state.playing == true) ringColor = theme.accent
        }
        head.addView(avatar, LinearLayout.LayoutParams(dp(88), dp(88)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, 0, 0)
        }
        texts.addView(text(context, st.title, 26f, theme.primaryText, bold = true))
        texts.addView(
            text(
                context,
                "${RadioStations.formatFreq(st.band, st.freq)} · ${st.band.uppercase()}",
                18f, theme.accent
            )
        )
        val rds = st.rds?.takeIf { it.isNotBlank() && it != st.title }
        if (rds != null) {
            texts.addView(text(context, context.getString(R.string.radio_card_rds, rds), 16f, theme.secondaryText))
        }
        head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(head)

        // Сведения.
        val stats = RadioStats.get(context, st.band, st.freq)
        val rows = mutableListOf<Pair<String, String>>()
        rows += context.getString(R.string.radio_card_now) to context.getString(
            when {
                current && RadioHub.walking -> R.string.radio_card_now_tuning
                current && RadioHub.state.playing == true -> R.string.radio_card_now_playing
                current -> R.string.radio_card_now_paused
                else -> R.string.radio_card_now_other
            }
        )
        rows += context.getString(R.string.radio_card_last) to when {
            current && RadioHub.state.playing == true && !RadioHub.walking ->
                context.getString(R.string.radio_card_last_now)
            stats != null -> RadioStats.formatLast(stats.last, System.currentTimeMillis())
            else -> context.getString(R.string.radio_card_last_never)
        }
        rows += context.getString(R.string.radio_card_total) to
            (stats?.let { RadioStats.formatDuration(it.totalMs) } ?: "—")
        val known = RadioStations.find(RadioStations.load(context), st.band, st.freq)
        rows += context.getString(R.string.radio_card_list) to context.getString(
            when {
                known?.favorite == true -> R.string.radio_card_list_fav
                known != null -> R.string.radio_card_list_yes
                else -> R.string.radio_card_list_no
            }
        )
        if (current && RadioHub.state.favorite != null) {
            rows += context.getString(R.string.radio_card_gu_fav) to context.getString(
                if (RadioHub.state.favorite == true) R.string.radio_card_yes else R.string.radio_card_no
            )
        }
        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(4), dp(28), dp(12))
        }
        rows.forEach { (k, v) ->
            info.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5), 0, dp(5))
                addView(text(context, k, 17f, theme.secondaryText), LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(text(context, v, 17f, theme.primaryText), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        box.addView(info)

        // Действия.
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (!current) {
            actions += context.getString(R.string.radio_card_play) to {
                tune(context, st)
                close()
            }
        }
        actions += context.getString(
            if (st.favorite) R.string.radio_card_unfav else R.string.radio_card_fav
        ) to { toggleFavorite(context, st.band, st.freq, st.rds) { refresh() } }
        actions += context.getString(R.string.radio_card_rename) to {
            rename(context, st) { refresh() }
        }
        actions += context.getString(R.string.radio_card_color) to {
            pickColor(context, st) { refresh() }
        }
        if (current) {
            actions += context.getString(R.string.radio_gu_favorite) to {
                RadioHub.toggleFavorite()
                // Служба ответит колбэком — перерисуем чуть позже.
                box.postDelayed({ if (box.isAttachedToWindow) refresh() }, 800)
            }
        }
        actions += context.getString(R.string.radio_card_open_gu) to {
            RadioHub.open(context)
            close()
        }
        val perRow = 3
        actions.chunked(perRow).forEach { chunk ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(24), dp(4), dp(24), dp(4))
            }
            chunk.forEach { (label, act) ->
                row.addView(actionButton(context, theme, label, act), LinearLayout.LayoutParams(
                    0, dp(56), 1f
                ).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
            repeat(perRow - chunk.size) {
                row.addView(View(context), LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                })
            }
            box.addView(row)
        }
    }

    // ---------- ⏮ ⏭ ----------

    /** Опция виджета: что делают ⏮ ⏭ ([RadioStations.SKIP_SEEK] и др.). */
    const val OPT_SKIP = "radio_skip"

    fun skipMode(store: WidgetSettingsStore?, widgetId: String?): String {
        if (store == null || widgetId == null) return RadioStations.SKIP_SEEK
        val m = store.getOption(widgetId, OPT_SKIP, RadioStations.SKIP_SEEK)
        return m.takeIf {
            it == RadioStations.SKIP_FAVORITES || it == RadioStations.SKIP_LIST
        } ?: RadioStations.SKIP_SEEK
    }

    /**
     * ⏮ ([dir] = −1) или ⏭ (+1) в режиме [mode]. По избранному и списку —
     * настройка на соседнюю станцию (перебором); считаем от цели идущего
     * переключения, чтобы двойное нажатие ушло на две станции. Соседней
     * нет — обычный поиск тюнером.
     */
    fun skip(context: Context, mode: String, dir: Int) {
        if (mode != RadioStations.SKIP_SEEK) {
            val base = RadioHub.walkTarget
            val band = base?.band ?: RadioHub.state.band
            val freq = base?.freq ?: RadioHub.currentFrequency()
            val next = RadioStations.neighbor(RadioStations.load(context), mode, band, freq, dir)
            if (next != null) {
                tune(context, next)
                return
            }
        }
        if (RadioHub.walking) RadioHub.cancelWalk()
        if (dir > 0) RadioHub.seekUp() else RadioHub.seekDown()
    }

    // ---------- действия ----------

    /** Настроиться на станцию; не нашлась у тюнера — сказать. */
    fun tune(context: Context, station: RadioStations.Station) {
        RadioHub.tuneTo(station.band, station.freq) { ok ->
            if (!ok) Toast.makeText(context, R.string.radio_station_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Добавить станцию в избранное (сразу спросив своё название) или убрать
     * из него. Станции нет в списке — добавится.
     */
    fun toggleFavorite(context: Context, band: String, freq: Double, rds: String?, done: () -> Unit) {
        val stations = RadioStations.load(context)
        val known = RadioStations.find(stations, band, freq)
        if (known?.favorite == true) {
            RadioStations.setFavorite(context, band, freq, false)
            done()
            return
        }
        if (RadioStations.favorites(stations).size >= RadioStations.MAX_FAVORITES) {
            full(context)
            return
        }
        RadioStationsDialog.askName(context, known?.name ?: known?.rds ?: rds) { name ->
            RadioStations.upsert(context, band, freq, rds)
            RadioStations.rename(context, band, freq, name)
            if (!RadioStations.setFavorite(context, band, freq, true)) full(context)
            done()
        }
    }

    private fun full(context: Context) {
        Toast.makeText(
            context,
            context.getString(R.string.radio_favorites_full, RadioStations.MAX_FAVORITES),
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Своё название (станции нет в списке — добавится). */
    fun rename(context: Context, st: RadioStations.Station, done: () -> Unit) {
        RadioStationsDialog.askName(context, st.name ?: st.rds) { name ->
            RadioStations.upsert(context, st.band, st.freq, st.rds)
            RadioStations.rename(context, st.band, st.freq, name)
            done()
        }
    }

    /** Выбор цвета значка: палитра и «Авто» (цвет по частоте). */
    fun pickColor(context: Context, st: RadioStations.Station, done: () -> Unit) {
        val theme = ThemeStore(ThemeStore.PREFS, context).current()
        var dialog: Dialog? = null
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), 0, dp(context, 24), dp(context, 8))
        }
        val choose = { color: Int? ->
            RadioStations.setColor(context, st.band, st.freq, color)
            dialog?.dismiss()
            done()
        }
        val cells: List<Int?> = listOf<Int?>(null) + RadioStations.PALETTE.toList()
        cells.chunked(7).forEach { chunk ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            chunk.forEach { c ->
                val selected = c == st.color
                val cell = if (c == null) {
                    // «Авто» — значок с цветом по частоте и подписью.
                    StationAvatarView(context).apply {
                        bind(st.band, st.freq, context.getString(R.string.radio_color_auto), null)
                        if (selected) ringColor = theme.primaryText
                    }
                } else {
                    View(context).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(c)
                            if (selected) setStroke(dp(context, 4), theme.primaryText)
                        }
                    }
                }
                cell.setOnClickListener { choose(c) }
                row.addView(cell, LinearLayout.LayoutParams(dp(context, 56), dp(context, 56)).apply {
                    setMargins(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
                })
            }
            grid.addView(row)
        }
        dialog = DashDialog.Builder(context)
            .setTitle(R.string.radio_color_title)
            .setWidthDp(640)
            .setView(grid)
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    /**
     * Меню станции (долгое нажатие на карточку избранного или плитку
     * списка): о станции, избранное, имя, цвет, место в ряду избранного
     * ([withMove]), убрать из списка.
     */
    fun stationMenu(
        context: Context,
        st: RadioStations.Station,
        withMove: Boolean,
        done: () -> Unit
    ) {
        val known = RadioStations.find(RadioStations.load(context), st.band, st.freq)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += context.getString(R.string.radio_menu_info) to { show(context, st.band, st.freq) }
        actions += context.getString(
            if (st.favorite) R.string.radio_card_unfav else R.string.radio_card_fav
        ) to { toggleFavorite(context, st.band, st.freq, st.rds, done) }
        actions += context.getString(R.string.radio_card_rename) to { rename(context, st, done) }
        actions += context.getString(R.string.radio_card_color) to { pickColor(context, st, done) }
        if (withMove && st.favorite) {
            actions += context.getString(R.string.radio_menu_left) to {
                RadioStations.moveFavorite(context, st.band, st.freq, -1)
                done()
            }
            actions += context.getString(R.string.radio_menu_right) to {
                RadioStations.moveFavorite(context, st.band, st.freq, 1)
                done()
            }
        }
        if (known != null) {
            actions += context.getString(R.string.radio_menu_remove) to {
                DashDialog.Builder(context)
                    .setTitle(R.string.radio_remove_title)
                    .setMessage(context.getString(R.string.radio_remove_text, st.title))
                    .setPositiveButton(R.string.radio_remove_ok) { _, _ ->
                        RadioStations.remove(context, st.band, st.freq)
                        done()
                    }
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .showFullScreen()
            }
        }
        DashDialog.Builder(context)
            .setTitle(st.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    // ---------- вспомогательное ----------

    private fun text(context: Context, s: String, sp: Float, color: Int, bold: Boolean = false) =
        TextView(context).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    private fun actionButton(context: Context, theme: DashTheme, label: String, onClick: () -> Unit) =
        TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(theme.primaryText)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(context, 10), 0, dp(context, 10), 0)
            background = ThemeCard.card(context, theme)
            setOnClickListener { onClick() }
        }

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
        ).toInt()
}
