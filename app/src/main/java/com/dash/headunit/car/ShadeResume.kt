package com.dash.headunit.car

import android.content.Context
import com.dash.headunit.settings.AppSettings

/**
 * Автооткрытие шторки люка при старте: если она была открыта в конце
 * последней поездки, молча пишем ту же команду, что и кнопка «открыть»
 * ([ButtonsWidgetView]): группа 3, id 124, значение 1.
 *
 * Состояние — ячейка 0,10: 6 = открыто (подтверждено), 1 = закрыто.
 * Постоянного опроса нет (как и запись поездки в журнал [TripTracker]):
 * значение читается однократно по факту конца поездки — [CarHub] видит
 * конец по [TripTracker.tripEndTick] и передаёт сюда сырое значение,
 * которое хранится в prefs и переживает перезапуск процесса.
 *
 * Само открытие — раз за запуск приложения: тик [tick] вызывается из
 * цикла опроса, команда пишется, пока шина живая. Если запись не ушла
 * (шина ещё не проснулась) — пробуем на следующем тике, но не больше
 * [MAX_ATTEMPTS] раз. Допустимая скорость не важна: опция срабатывает
 * на парковке.
 */
object ShadeResume {

    /** Ячейка 0,10: 6 = открыто (сырое значение). */
    const val OPEN_RAW = 6

    private const val PREFS = "shade_prefs"
    private const val KEY_RAW = "shade_raw"

    /** Последнее зафиксированное сырое значение шторки (конец поездки). */
    @Volatile
    private var savedRaw: Int? = null

    /** Попытка автооткрытия удалась — больше не повторяем (память процесса). */
    @Volatile
    private var autoOpenDone = false

    private var attempts = 0

    /** Для тестов. */
    fun resetForTests() {
        savedRaw = null
        autoOpenDone = false
        attempts = 0
    }

    /**
     * Тик цикла опроса ([CarHub]). [busAlive] — шина отвечает в этом тике.
     * Здесь только автооткрытие на старте; ячейку 0,10 тик не читает.
     */
    fun tick(context: Context, busAlive: Boolean) {
        if (savedRaw == null) savedRaw = loadRaw(context)
        maybeAutoOpen(context, busAlive)
    }

    /**
     * Запомнить состояние шторки — единственный вызов читается в момент
     * конца поездки ([CarHub], [TripTracker.tripEndTick]). Битое чтение
     * (шина отвалилась) состояние не портит: остаётся поездочное.
     */
    fun record(context: Context, raw: Int?) {
        if (raw == null || raw < 0) return
        savedRaw = raw
        store(context, raw)
    }

    /** Состояние из prefs — до первого чтения шины (перезапуск процесса). */
    fun saved(context: Context): Int? {
        if (savedRaw == null) savedRaw = loadRaw(context)
        return savedRaw
    }

    private fun maybeAutoOpen(context: Context, busAlive: Boolean) {
        if (autoOpenDone || !AppSettings.shadeResume(context)) return
        // Шина молчит — запись и не уйдёт, ждём живого тика.
        if (!busAlive) return
        val raw = saved(context) ?: return
        if (!wasOpen(raw)) return
        if (++attempts > MAX_ATTEMPTS) return
        autoOpenDone = try {
            val reader = DesayCarReader(context.applicationContext)
            reader.connect() && reader.write(3, 124, VALUE_OPEN)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "shade auto open", e)
            false
        }
    }

    /* Чистые — тестируются. */

    /** «Открыта в конце поездки»: сырое значение ячейки 0,10. */
    internal fun wasOpen(raw: Int): Boolean = raw == OPEN_RAW

    private fun store(context: Context, raw: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_RAW, raw).apply()
    }

    private fun loadRaw(context: Context): Int? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_RAW, -1).takeIf { it >= 0 }

    private const val TAG = "ShadeResume"
    /** Значение команды «открыть шторку» — как у кнопки виджета «Кнопки». */
    private const val VALUE_OPEN = 1
    /** Сколько тиков можно пробовать, прежде чем сдаться. */
    private const val MAX_ATTEMPTS = 5
}
