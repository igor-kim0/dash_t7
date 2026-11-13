package com.dash.headunit.widgets

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import com.dash.headunit.R
import com.dash.headunit.radio.RadioHub
import com.dash.headunit.radio.RadioStations
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.ThemeCard
import com.dash.headunit.theme.ThemeStore
import com.dash.headunit.ui.DashDialog

/**
 * Диалог «Радиостанции» для виджета «Радио»: поиск по списку станций, своё
 * название и избранное (до [RadioStations.MAX_FAVORITES] штук).
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

    private class Screen(private val context: Context) {

        private val theme: DashTheme = ThemeStore(ThemeStore.PREFS, context).current()

        private val search = EditText(context).apply {
            setSingleLine()
            hint = context.getString(R.string.radio_search_hint)
        }

        private val status = TextView(context).apply {
            textSize = 14f
            setTextColor(theme.secondaryText)
        }

        private val btnScan = actionButton(context.getString(R.string.radio_scan))

        private val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        private var refresh: () -> Unit = {}

        /** Перебор остановили по кнопке — итог не показываем. */
        private var stopped = false

        fun open() {
            // Шапка (поле поиска и действия) не прокручивается вместе со списком.
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 24), 0, dp(context, 24), dp(context, 4))
                addView(search, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.START
                        addView(
                            btnScan,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { marginEnd = dp(context, 10) }
                        )
                        addView(
                            actionButton(context.getString(R.string.radio_add_station)) {
                                addStation()
                            },
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { marginEnd = dp(context, 10) }
                        )
                        // Избранное штатного радио — списка у него нет,
                        // поэтому кнопка просто переключает текущую станцию.
                        addView(
                            actionButton(context.getString(R.string.radio_gu_favorite)) {
                                toggleGuFavorite()
                            },
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                        )
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(context, 6) }
                )
                addView(status, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(context, 4) })
            }

            refresh = { fill() }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    refresh()
                }
            })
            btnScan.setOnClickListener { scan() }
            // Перебор мог остаться от прошлого открытия — покажем «Остановить».
            if (RadioHub.walking) {
                btnScan.text = context.getString(R.string.radio_scan_stop)
                status.text = context.getString(R.string.radio_scan_running)
            }

            DashDialog.Builder(context)
                .setTitle(R.string.radio_stations_title)
                .setHeaderView(header)
                .setView(list)
                .setPositiveButton(R.string.dialog_ok, null)
                .showFullScreen()

            fill()
        }

        /** Перерисовать список: избранное сверху, потом остальные станции. */
        private fun fill() {
            list.removeAllViews()
            val all = RadioStations.load(context)
            val found = RadioStations.search(all, search.text.toString())
            if (found.isEmpty()) {
                list.addView(TextView(context).apply {
                    text = context.getString(
                        if (all.isEmpty()) R.string.radio_stations_none else R.string.radio_stations_empty
                    )
                    textSize = 16f
                    setTextColor(theme.secondaryText)
                    setPadding(dp(context, 28), dp(context, 12), dp(context, 28), dp(context, 12))
                })
                return
            }
            val favs = found.filter { it.favorite }
            val rest = found.filterNot { it.favorite }
            if (favs.isNotEmpty()) {
                list.addView(section(context.getString(R.string.radio_favorites_section)))
                favs.forEach { list.addView(row(it)) }
            }
            if (rest.isNotEmpty()) {
                list.addView(section(context.getString(R.string.radio_all_section)))
                rest.forEach { list.addView(row(it)) }
            }
        }

        private fun section(title: String) = TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(theme.secondaryText)
            setPadding(dp(context, 28), dp(context, 12), dp(context, 28), dp(context, 4))
        }

        /**
         * Строка станции: частота (переключает на станцию), название (своё
         * имя) и звезда (избранное). Долгое нажатие — убрать из списка.
         */
        private fun row(station: RadioStations.Station): View {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 12), dp(context, 4), dp(context, 12), dp(context, 4))
                background = pressable()
                setOnLongClickListener {
                    remove(station)
                    true
                }
            }
            val freq = TextView(context).apply {
                text = RadioStations.freqText(station.band, station.freq)
                textSize = 18f
                setTextColor(theme.accent)
                minWidth = dp(context, 64)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { tune(station) }
            }
            val name = TextView(context).apply {
                text = station.title
                textSize = 18f
                setTextColor(theme.primaryText)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(context, 12), 0, dp(context, 8), 0)
                setOnClickListener { rename(station) }
            }
            val fav = ImageButton(context).apply {
                setImageResource(
                    if (station.favorite) R.drawable.ic_star else R.drawable.ic_star_outline
                )
                setColorFilter(if (station.favorite) theme.accent else theme.secondaryText)
                setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
                setOnClickListener { toggleFavorite(station) }
            }
            row.addView(freq, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            row.addView(name, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))
            row.addView(fav, LinearLayout.LayoutParams(
                dp(context, 48), dp(context, 48)
            ))
            return row
        }

        private fun tune(station: RadioStations.Station) {
            status.text = context.getString(
                R.string.radio_tuning, RadioStations.formatFreq(station.band, station.freq)
            )
            RadioHub.tuneTo(station.band, station.freq) { ok ->
                if (ok) {
                    status.text = context.getString(
                        R.string.radio_tuned, RadioStations.formatFreq(station.band, station.freq)
                    )
                } else {
                    status.text = context.getString(R.string.radio_station_not_found)
                    Toast.makeText(
                        context, R.string.radio_station_not_found, Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        private fun rename(station: RadioStations.Station) {
            askName(context, station.name ?: station.rds) { name ->
                RadioStations.rename(context, station.band, station.freq, name)
                refresh()
            }
        }

        private fun toggleFavorite(station: RadioStations.Station) {
            if (station.favorite) {
                RadioStations.setFavorite(context, station.band, station.freq, false)
                refresh()
                return
            }
            val favs = RadioStations.favorites(RadioStations.load(context))
            if (favs.size >= RadioStations.MAX_FAVORITES) {
                Toast.makeText(
                    context,
                    context.getString(R.string.radio_favorites_full, RadioStations.MAX_FAVORITES),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            // Своё название спросим сразу — так избранное и делается.
            askName(context, station.name ?: station.rds) { name ->
                RadioStations.rename(context, station.band, station.freq, name)
                if (!RadioStations.setFavorite(context, station.band, station.freq, true)) {
                    Toast.makeText(
                    context,
                    context.getString(R.string.radio_favorites_full, RadioStations.MAX_FAVORITES),
                    Toast.LENGTH_SHORT
                ).show()
                }
                refresh()
            }
        }

        private fun remove(station: RadioStations.Station) {
            DashDialog.Builder(context)
                .setTitle(R.string.radio_remove_title)
                .setMessage(context.getString(R.string.radio_remove_text, station.title))
                .setPositiveButton(R.string.radio_remove_ok) { _, _ ->
                    RadioStations.remove(context, station.band, station.freq)
                    refresh()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .showFullScreen()
        }

        /** Перебор диапазона: собрать найденные станции. */
        private fun scan() {
            if (RadioHub.walking) {
                RadioHub.cancelWalk()
                stopped = true
                btnScan.text = context.getString(R.string.radio_scan)
                status.text = ""
                return
            }
            if (RadioHub.unavailable) {
                Toast.makeText(context, R.string.radio_unavailable, Toast.LENGTH_SHORT).show()
                return
            }
            stopped = false
            btnScan.text = context.getString(R.string.radio_scan_stop)
            status.text = context.getString(R.string.radio_scan_running)
            RadioHub.scanStations({ station ->
                RadioStations.upsert(context, station.band, station.freq, station.rds)
                refresh()
                status.text = context.getString(
                    R.string.radio_scan_progress, RadioStations.load(context).size
                )
            }) { ok ->
                btnScan.text = context.getString(R.string.radio_scan)
                if (!stopped) {
                    status.text = context.getString(
                        if (ok) R.string.radio_scan_done else R.string.radio_scan_failed
                    )
                }
                stopped = false
                refresh()
            }
        }

        private fun addStation() {
            askStation(context) { band, freq, name ->
                RadioStations.upsert(context, band, freq)
                RadioStations.rename(context, band, freq, name)
                refresh()
            }
        }

        /** Избранное штатного радио: переключить текущую станцию. */
        private fun toggleGuFavorite() {
            if (RadioHub.state.frequency == null) {
                Toast.makeText(context, R.string.radio_connecting, Toast.LENGTH_SHORT).show()
                return
            }
            val was = RadioHub.state.favorite == true
            RadioHub.toggleFavorite()
            Toast.makeText(
                context,
                if (was) R.string.radio_gu_favorite_removed else R.string.radio_gu_favorite_added,
                Toast.LENGTH_SHORT
            ).show()
        }

        /** Кнопка-действие в шапке диалога. */
        private fun actionButton(text: String, onClick: (() -> Unit)? = null): TextView =
            TextView(context).apply {
                this.text = text
                textSize = 15f
                setTextColor(theme.primaryText)
                gravity = Gravity.CENTER
                setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
                background = ThemeCard.card(context, theme)
                if (onClick != null) setOnClickListener { onClick() }
            }

        private fun pressable() = RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(theme.accent, 0x40)),
            null,
            ColorDrawable(Color.WHITE)
        )

        private fun dp(v: Int): Int = dp(context, v)
    }

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
        ).toInt()
}
