package com.dash.headunit.car

import android.content.Context
import com.dash.headunit.settings.AppSettings

/**
 * Автооткрытие шторки люка при старте: если она была открыта в конце
 * последней поездки, молча пишем ту же команду, что и кнопка «открыть»
 * ([ButtonsWidgetView]): группа 3, id 124, значение 1.
 *
 * Состояние — ячейка 0,10: 6 = открыто (подтверждено), 1 = закрыто.
 *
 * Запоминание: [CarHub] читает 0,10 на каждом тике опроса, пока двигатель
 * работает (и ещё раз в момент конца поездки, если шина жива), и отдаёт
 * в [record]. В prefs остаётся последнее значение при работающем
 * двигателе = состояние в конце поездки. Раньше читали только в момент
 * конца поездки — а ГУ обычно засыпает вместе с зажиганием, и поездка
 * закрывается «вслепую» (шины уже нет) или утром по разрыву: состояние
 * не записывалось никогда либо записывалось утреннее.
 *
 * Открытие — раз за запуск приложения, с проверкой: запись в шину oneway
 * (true значит лишь «транзакция ушла», машина могла её не принять — на
 * старте шина комфорта просыпается не сразу), поэтому через
 * [VERIFY_TICKS] тиков читаем 0,10; не открыто — повторяем, не больше
 * [MAX_ATTEMPTS] раз. Пока открытие не подтверждено и не брошено,
 * [record] ничего не пишет — иначе «закрыто» на старте (шторка ещё едет)
 * затёрло бы сохранённое «открыто».
 */
object ShadeResume {

    /** Ячейка 0,10: 6 = открыто (сырое значение). */
    const val OPEN_RAW = 6

    private const val PREFS = "shade_prefs"
    private const val KEY_RAW = "shade_raw"

    /** Последнее зафиксированное сырое значение шторки. */
    @Volatile
    private var savedRaw: Int? = null

    /** Чистая машина состояний автооткрытия (память процесса). */
    private val auto = AutoOpen()

    /** Для тестов. */
    fun resetForTests() {
        savedRaw = null
        auto.reset()
    }

    /**
     * Тик цикла опроса ([CarHub]), вызывать НЕ с главного потока (чтение и
     * запись шины синхронные). [busAlive] — шина отвечает в этом тике.
     * [readShade] — чтение 0,10 (только для проверки открытия), [writeOpen] —
     * запись «открыть»; оба через уже подключённый ридер [CarHub]: свежий
     * [DesayCarReader] на главном потоке не подключится (onServiceConnected
     * приходит на тот же главный поток, который ждёт его на latch).
     */
    fun tick(
        context: Context,
        busAlive: Boolean,
        readShade: () -> Int?,
        writeOpen: () -> Boolean
    ) {
        if (savedRaw == null) savedRaw = loadRaw(context)
        if (auto.finished) return
        if (!AppSettings.shadeResume(context)) {
            auto.finish()
            return
        }
        val raw = saved(context)
        val action = auto.step(
            busAlive = busAlive,
            wantOpen = raw != null && wasOpen(raw),
            current = readShade
        )
        if (action == AutoOpen.Action.WRITE) {
            val ok = try {
                writeOpen()
            } catch (e: Exception) {
                android.util.Log.w(TAG, "shade auto open", e)
                false
            }
            android.util.Log.i(TAG, "auto open attempt ${auto.attempts}: write=$ok")
            if (!ok) auto.writeFailed()
        }
    }

    /** Автооткрытие ещё в процессе — состояние шторки сейчас не показательно. */
    val pending: Boolean get() = !auto.finished

    /**
     * Запомнить состояние шторки. Битое чтение (шина отвалилась) состояние
     * не портит; пока идёт автооткрытие — тоже не пишем.
     */
    fun record(context: Context, raw: Int?) {
        if (raw == null || raw < 0) return
        if (pending) return
        if (savedRaw == raw) return
        savedRaw = raw
        store(context, raw)
    }

    /** Состояние из prefs — до первого чтения шины (перезапуск процесса). */
    fun saved(context: Context): Int? {
        if (savedRaw == null) savedRaw = loadRaw(context)
        return savedRaw
    }

    /* Чистые — тестируются. */

    /** «Открыта в конце поездки»: сырое значение ячейки 0,10. */
    internal fun wasOpen(raw: Int): Boolean = raw == OPEN_RAW

    /**
     * Машина состояний автооткрытия: ждать живую шину → записать →
     * подождать [VERIFY_TICKS] → прочитать; открыто — готово, нет —
     * снова записать (до [MAX_ATTEMPTS]).
     */
    internal class AutoOpen {
        enum class Action { NONE, WRITE }

        var finished = false
            private set
        var attempts = 0
            private set
        private var waitTicks = 0

        fun reset() {
            finished = false
            attempts = 0
            waitTicks = 0
        }

        fun finish() {
            finished = true
        }

        /** Запись не ушла вовсе — проверять нечего, на следующем тике снова. */
        fun writeFailed() {
            waitTicks = 0
        }

        fun step(busAlive: Boolean, wantOpen: Boolean, current: () -> Int?): Action {
            if (finished) return Action.NONE
            if (!wantOpen) {
                finished = true
                return Action.NONE
            }
            if (!busAlive) return Action.NONE
            if (attempts > 0) {
                if (waitTicks > 0) {
                    waitTicks--
                    return Action.NONE
                }
                val now = current()
                if (now != null && now == OPEN_RAW) {
                    finished = true
                    return Action.NONE
                }
            }
            if (attempts >= MAX_ATTEMPTS) {
                finished = true
                return Action.NONE
            }
            attempts++
            waitTicks = VERIFY_TICKS
            return Action.WRITE
        }
    }

    private fun store(context: Context, raw: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_RAW, raw).apply()
    }

    private fun loadRaw(context: Context): Int? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_RAW, -1).takeIf { it >= 0 }

    private const val TAG = "ShadeResume"
    /** Команда «открыть шторку» — как у кнопки виджета «Кнопки». */
    const val WRITE_GROUP = 3
    const val WRITE_ID = 124
    const val VALUE_OPEN = 1
    /** Сколько раз писать «открыть», прежде чем сдаться. */
    internal const val MAX_ATTEMPTS = 4
    /** Тиков опроса (по 5 с) между записью и проверкой — шторка едет. */
    internal const val VERIFY_TICKS = 2
}
