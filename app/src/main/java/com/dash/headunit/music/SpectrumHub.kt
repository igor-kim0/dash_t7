package com.dash.headunit.music

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.dash.headunit.ui.DashVisibility
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Источник полос спектра для виджетов музыки ([com.dash.headunit.widgets.SpectrumView]).
 *
 * Реальный спектр — [Visualizer] на общем выходе (session 0), нужен
 * RECORD_AUDIO. На ГУ BT-аудио (A2DP) может идти мимо микшера Android,
 * тогда Visualizer отдаёт нули (или вовсе не создаётся) — в этом случае,
 * пока трек играет, рисуем правдоподобную имитацию ([simulated]).
 * На паузе полосы плавно гаснут.
 *
 * Visualizer живёт, пока есть подписчики: первый [addListener] создаёт,
 * последний [removeListener] освобождает.
 */
object SpectrumHub {
    private const val TAG = "SpectrumHub"

    /** Число полос. */
    const val BANDS = 32

    /** Нижняя/верхняя граница сетки полос, Гц. */
    private const val F_MIN = 40.0
    private const val F_MAX = 16000.0

    /** Шкала уровня: магнитуда 1 (0 дБ) → 0, [DB_TOP] → 1. */
    private const val DB_TOP = 42.0

    /** Сколько ждать реальных данных при игре, прежде чем включить имитацию. */
    private const val SILENCE_MS = 1500L
    private const val FRAME_MS = 33L
    private const val PEAK_HOLD_FRAMES = 14

    /** Сглаженные уровни 0..1 — читает вью в onDraw. */
    val levels = FloatArray(BANDS)
    /** Пиковые маркеры 0..1. */
    val peaks = FloatArray(BANDS)

    /** Сейчас рисуется имитация, а не реальный звук. */
    @Volatile
    var simulated = false
        private set

    private val listeners = mutableListOf<() -> Unit>()
    private val playingBy = HashMap<String, Boolean>()
    private val realTarget = FloatArray(BANDS)
    private val target = FloatArray(BANDS)
    private val peakHold = IntArray(BANDS)
    private var lastRealMs = 0L
    private var visualizer: Visualizer? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var ticking = false

    private val tick = object : Runnable {
        override fun run() {
            if (listeners.isEmpty()) {
                ticking = false
                return
            }
            if (step()) {
                listeners.toList().forEach { it() }
                main.postDelayed(this, FRAME_MS)
            } else {
                // Всё погасло и ничего не играет — не тикаем 30 раз в секунду
                // впустую; снова запустят [setPlaying] или звук из Visualizer.
                ticking = false
            }
        }
    }

    fun addListener(context: Context, listener: () -> Unit) {
        if (listener in listeners) return
        listeners.add(listener)
        if (listeners.size == 1) start(context.applicationContext)
    }

    fun removeListener(listener: () -> Unit) {
        if (!listeners.remove(listener)) return
        if (listeners.isEmpty()) stop()
    }

    /** Состояние воспроизведения от виджета (у каждого виджета свой хаб плеера). */
    fun setPlaying(key: String, playing: Boolean) {
        playingBy[key] = playing
        if (playing) ensureTicking()
    }

    /** Главный поток. */
    private fun ensureTicking() {
        if (ticking || listeners.isEmpty() || !DashVisibility.visible) return
        ticking = true
        main.post(tick)
    }

    fun clearPlaying(key: String) {
        playingBy.remove(key)
    }

    fun hasAudioPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun start(context: Context) {
        lastRealMs = 0L
        if (hasAudioPermission(context)) openVisualizer()
        DashVisibility.addListener(onVisibility)
        if (!DashVisibility.visible) visualizer?.enabled = false
        ensureTicking()
    }

    /**
     * Дашборд скрыт (заставка, другое приложение) — захват звука и кадры
     * спектра не нужны; вернулся — снова.
     */
    private val onVisibility: (Boolean) -> Unit = { v ->
        try {
            visualizer?.enabled = v
        } catch (e: Exception) {
            // ГУ: вендорский эффект мог уже умереть.
        }
        if (v) {
            ensureTicking()
        } else {
            main.removeCallbacks(tick)
            ticking = false
        }
    }

    private fun stop() {
        DashVisibility.removeListener(onVisibility)
        main.removeCallbacks(tick)
        ticking = false
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (e: Exception) {
            // ГУ: вендорский эффект мог уже умереть.
        }
        visualizer = null
        levels.fill(0f)
        peaks.fill(0f)
        realTarget.fill(0f)
    }

    private fun openVisualizer() {
        try {
            val v = Visualizer(0)
            v.enabled = false
            v.captureSize = Visualizer.getCaptureSizeRange()[1]
            val rate = min(Visualizer.getMaxCaptureRate(), 30_000)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    fft ?: return
                    // samplingRate приходит в мГц.
                    val bands = bandsFromFft(fft, samplingRate / 1000, BANDS)
                    if (bands.any { it > 0.02f }) {
                        bands.copyInto(realTarget)
                        lastRealMs = SystemClock.uptimeMillis()
                        // Колбэк — в главном потоке (Visualizer создан в нём).
                        ensureTicking()
                    } else {
                        realTarget.fill(0f)
                    }
                }
            }, rate, false, true)
            v.enabled = true
            visualizer = v
        } catch (e: Throwable) {
            Log.w(TAG, "Visualizer недоступен, будет имитация: $e")
            visualizer = null
        }
    }

    /** Один кадр сглаживания; false — всё погасло, перерисовка не нужна. */
    private fun step(): Boolean {
        val now = SystemClock.uptimeMillis()
        val playing = playingBy.values.any { it }
        val real = lastRealMs != 0L && now - lastRealMs < SILENCE_MS
        when {
            real -> {
                simulated = false
                realTarget.copyInto(target)
            }
            playing -> {
                simulated = true
                simulate(now / 1000.0, target)
            }
            else -> target.fill(0f)
        }
        var any = false
        for (i in 0 until BANDS) {
            val t = target[i]
            val l = levels[i]
            // Быстрый подъём, медленный спад.
            levels[i] = if (t > l) l + (t - l) * 0.6f else max(t, l - max(0.025f, (l - t) * 0.18f))
            if (levels[i] >= peaks[i]) {
                peaks[i] = levels[i]
                peakHold[i] = PEAK_HOLD_FRAMES
            } else if (peakHold[i] > 0) {
                peakHold[i]--
            } else {
                peaks[i] = max(0f, peaks[i] - 0.02f)
            }
            if (levels[i] > 0.001f || peaks[i] > 0.001f) any = true
        }
        return any || playing
    }

    /**
     * Имитация: «бас выше», ритмичный бит ~2 Гц, у каждой полосы свои
     * несоизмеримые частоты колебаний — выглядит живо, не повторяется.
     */
    internal fun simulate(t: Double, out: FloatArray) {
        val n = out.size
        val beat = max(0.0, sin(2 * PI * 2.05 * t)).pow(6)
        val beat2 = max(0.0, sin(2 * PI * 4.1 * t + 1.0)).pow(8)
        for (i in 0 until n) {
            val x = i / (n - 1.0)
            val env = 0.82 - 0.42 * x
            val a = sin(t * (2.3 + i * 0.61) + i * 1.7)
            val b = sin(t * (5.1 + i * 0.29) + i * 0.9)
            val c = sin(t * (11.7 + i * 0.13) + i * 2.3)
            val wobble = 0.5 + 0.25 * a + 0.15 * b + 0.1 * c
            val v = env * (0.25 + 0.45 * wobble + 0.4 * beat * (1 - x) + 0.25 * beat2 * x)
            out[i] = v.toFloat().coerceIn(0f, 1f)
        }
    }

    /**
     * FFT Visualizer → [n] полос в логарифмической сетке [F_MIN]..[F_MAX], 0..1.
     * Формат [fft]: [0] — DC, [1] — Найквист, далее пары re/im бинов 1..N/2-1.
     */
    fun bandsFromFft(fft: ByteArray, samplingRateHz: Int, n: Int): FloatArray {
        val out = FloatArray(n)
        val bins = fft.size / 2
        if (bins < 2 || samplingRateHz <= 0) return out
        val binHz = samplingRateHz.toDouble() / fft.size
        val fMax = min(F_MAX, samplingRateHz / 2.0)
        val logMin = ln(F_MIN)
        val logSpan = ln(fMax) - logMin
        for (b in 0 until n) {
            val f0 = kotlin.math.exp(logMin + logSpan * b / n)
            val f1 = kotlin.math.exp(logMin + logSpan * (b + 1) / n)
            var k0 = (f0 / binHz).toInt().coerceIn(1, bins - 1)
            var k1 = (f1 / binHz).toInt().coerceIn(1, bins - 1)
            if (k1 < k0) k1 = k0.also { k0 = k1 }
            var mag = 0.0
            for (k in k0..k1) {
                val re = fft[2 * k].toDouble()
                val im = fft[2 * k + 1].toDouble()
                mag = max(mag, sqrt(re * re + im * im))
            }
            out[b] = if (mag <= 1.0) 0f else (20 * log10(mag) / DB_TOP).toFloat().coerceIn(0f, 1f)
        }
        return out
    }
}
