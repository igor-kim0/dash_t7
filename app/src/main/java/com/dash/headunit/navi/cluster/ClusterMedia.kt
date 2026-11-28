package com.dash.headunit.navi.cluster

import android.content.Context
import android.graphics.Bitmap
import com.dash.headunit.music.MusicData
import com.dash.headunit.music.MusicHub
import com.dash.headunit.radio.RadioHub
import com.dash.headunit.radio.RadioStations

/**
 * Что сейчас играет, для приборки «Тест» ([ModernCluster]): трек
 * Bluetooth/плеера ([MusicHub]) или радиостанция ([RadioHub]). Если ничего
 * не играет — null, и блок не показывается.
 *
 * Приборку видно всегда, поэтому [MusicHub] опрашивается и при скрытом
 * дашборде ([MusicHub.setBackground]). Подписка только на время темы «Тест».
 * Всё работает в главном потоке.
 */
object ClusterMedia {

    data class Item(
        val title: String,
        val sub: String?,
        /** Обложка трека (только музыка). */
        val art: Bitmap?,
        /** Радио: буквы станции или диапазон в цветном круге. */
        val badge: String?,
        val color: Int?
    )

    var item: Item? = null
        private set

    private var app: Context? = null
    private var onChange: (() -> Unit)? = null
    private var stations: List<RadioStations.Station> = emptyList()

    private val hubListener: () -> Unit = { update() }
    private val stationsListener: () -> Unit = {
        app?.let { stations = RadioStations.load(it) }
        update()
    }

    /** Начать следить; [changed] вызывается при смене [item]. */
    fun start(context: Context, changed: () -> Unit) {
        val first = onChange == null
        onChange = changed
        if (!first) return
        val ctx = context.applicationContext
        app = ctx
        stations = RadioStations.load(ctx)
        RadioStations.addListener(stationsListener)
        RadioHub.init(ctx)
        RadioHub.addListener(hubListener)
        MusicHub.init(ctx)
        MusicHub.setBackground(true)
        MusicHub.addListener(hubListener)
        MusicHub.ensurePolling()
        update()
    }

    fun stop() {
        if (onChange == null) return
        onChange = null
        MusicHub.removeListener(hubListener)
        MusicHub.setBackground(false)
        RadioHub.removeListener(hubListener)
        RadioStations.removeListener(stationsListener)
        item = null
    }

    private fun update() {
        val next = pick(MusicHub.data, RadioHub.state, stations)
        val prev = item
        // Обложка — новый Bitmap на каждый опрос: сравниваем без неё.
        val same = prev != null && next != null &&
            prev.copy(art = null) == next.copy(art = null) && (prev.art == null) == (next.art == null)
        if (same || (prev == null && next == null)) return
        item = next
        onChange?.invoke()
    }

    /** Сначала играющая музыка (у BT состояние надёжнее), потом радио. */
    internal fun pick(music: MusicData?, radio: RadioHub.State, stations: List<RadioStations.Station>): Item? {
        if (music?.playing == true) {
            val title = music.title ?: return null
            return Item(title, music.artist, music.art, null, null)
        }
        if (radio.playing != true) return null
        val freq = RadioHub.Json.parseFrequency(radio.frequency)
        val band = radio.band
        val bandText = band.uppercase()
        if (freq == null) return radio.station?.let { Item(it, bandText, null, bandText, null) }
        val known = RadioStations.find(stations, band, freq)
        val name = known?.name?.takeIf { it.isNotBlank() }
            ?: known?.rds?.takeIf { it.isNotBlank() }
            ?: radio.station?.takeIf { it.isNotBlank() }
        val freqText = RadioStations.formatFreq(band, freq)
        val badge = RadioStations.initials(name).ifEmpty { bandText }
        val color = RadioStations.colorOf(band, freq, known?.color)
        return if (name != null) Item(name, "$bandText $freqText", null, badge, color)
        else Item(freqText, "Радио $bandText", null, badge, color)
    }
}
