package com.dash.headunit.weather

import android.content.Context
import com.dash.headunit.location.LocationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Общий источник погодных данных для всех виджетов процесса.
 * Один запрос обслуживает и полосу «часы + сейчас», и полосу «5 дней» —
 * виджеты только подписываются на обновления.
 *
 * Цепочка: Open-Meteo → (fallback) OpenWeatherMap → кэш → ошибка.
 * Автообновление каждые 30 минут + ручное по тапу на виджет.
 */
object WeatherHub {

    private var repository: WeatherRepository? = null
    private var locationHelper: LocationHelper? = null

    var data: WeatherData? = null
        private set
    var error: String? = null
        private set
    var loading: Boolean = false
        private set

    private val listeners = mutableSetOf<() -> Unit>()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var autoStarted = false

    fun init(context: Context) {
        if (repository == null) {
            val app = context.applicationContext
            repository = WeatherRepository(app)
            locationHelper = LocationHelper(app)
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
        listener()
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }

    /** Загрузить данные, если их ещё нет (идемпотентно). */
    fun ensureLoaded() {
        if (data == null && !loading) {
            // Сначала мгновенно показываем кэш с прошлого запуска,
            // свежие данные подтянутся следом.
            try {
                repository?.cached()?.let {
                    data = it
                    notifyListeners()
                }
            } catch (e: Exception) {
                // Игнорируем битый кэш — дальше полный цикл загрузки.
            }
            refresh()
        }
        if (!autoStarted) {
            autoStarted = true
            scope.launch {
                while (true) {
                    delay(AUTO_REFRESH_MS)
                    refresh()
                }
            }
        }
    }

    /**
     * Запросить свежие данные. [onDone] — итог (true — данные обновлены);
     * не вызывается, если загрузка уже идёт.
     */
    fun refresh(onDone: ((Boolean) -> Unit)? = null) {
        val repo = repository ?: return
        val loc = locationHelper ?: return
        if (loading) return
        loading = true
        error = null
        notifyListeners()
        loc.requestFix { fix ->
            val lat = fix?.latitude ?: LocationHelper.FALLBACK_LAT
            val lon = fix?.longitude ?: LocationHelper.FALLBACK_LON
            val isFallback = fix == null
            scope.launch {
                var ok = false
                try {
                    data = withContext(Dispatchers.IO) {
                        repo.refresh(lat, lon, isFallback)
                    }
                    error = null
                    ok = true
                } catch (e: Exception) {
                    data = repo.cached() ?: data
                    if (data == null) error = "error"
                } finally {
                    loading = false
                    notifyListeners()
                    onDone?.invoke(ok)
                }
            }
        }
    }

    /** Для тестов и завершения процесса. */
    fun resetForTests() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        listeners.clear()
        data = null
        error = null
        loading = false
        autoStarted = false
        repository = null
        locationHelper = null
    }

    private const val AUTO_REFRESH_MS = 30 * 60 * 1000L
}
