package com.dash.headunit.car

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Подсветка экрана ГУ через Desay — как штатный ползунок SystemUI_T1D
 * (widget/ScreenLumSeekBar); путь подтверждён на ГУ пробой на экране
 * диагностики.
 *
 * Состояние — getItemValues(5,1) = [режим, день/ночь, день−1, ночь−1],
 * уровни 1..10. Запись уровня p−1: setProperty(0x21414410, 0x2000000,
 * [p−1, ночь−1]), затем setProperty(0x21404410, area, [p−1, ночь−1] днём /
 * [день−1, p−1] ночью); area: режим 0 → день/ночь, иначе 1 (день) / 2 (ночь).
 *
 * Все обращения к шине — на [io] (один поток: записи виджета и заставки
 * идут строго по очереди). Лог — files/brightness_log.txt.
 *
 * Заставка: [dim] запоминает исходные день/ночь в SharedPreferences
 * (переживает падение процесса) и ставит свой уровень; [undim] возвращает,
 * если уровень с тех пор никто не трогал.
 */
object HuBrightness {

    private const val TAG = "HuBrightness"
    const val MIN_LEVEL = 1
    const val MAX_LEVEL = 10
    const val PROP_PREVIEW = 0x21414410
    const val PROP_SET = 0x21404410
    const val AREA_PREVIEW = 0x2000000

    private const val PREFS = "hu_brightness"
    private const val KEY_RESTORE = "restore"

    /** Уровни 1..10. */
    data class State(val mode: Int, val dayNight: Int, val dayLevel: Int, val nightLevel: Int) {
        val isNight: Boolean get() = dayNight != 0
        /** Уровень активного сейчас режима (день или ночь). */
        val current: Int get() = if (isNight) nightLevel else dayLevel

        /** area для [PROP_SET] — как в sendBrightValue штатного ползунка. */
        val setArea: Int get() = if (mode == 0) dayNight else if (isNight) 2 else 1

        /** Данные [PROP_PREVIEW] для уровня [level] активного режима. */
        fun previewData(level: Int): IntArray = intArrayOf(clamp(level) - 1, nightLevel - 1)

        /** Данные [PROP_SET]: меняется только ячейка активного режима. */
        fun setData(level: Int): IntArray =
            if (isNight) intArrayOf(dayLevel - 1, clamp(level) - 1)
            else intArrayOf(clamp(level) - 1, nightLevel - 1)

        companion object {
            /** Разбор (5,1); null — ячейка не читается (эмулятор, нет связи). */
            fun parse(raw: IntArray?): State? {
                if (raw == null || raw.size < 4) return null
                return State(raw[0], raw[1], clamp(raw[2] + 1), clamp(raw[3] + 1))
            }
        }
    }

    fun clamp(level: Int): Int = level.coerceIn(MIN_LEVEL, MAX_LEVEL)

    /** Однопоточный исполнитель для всех обращений к подсветке. */
    val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var reader: DesayCarReader? = null
    private var logFile: File? = null

    private fun desay(app: Context): DesayCarReader =
        reader ?: DesayCarReader(app.applicationContext).also { reader = it }

    fun log(context: Context, msg: String) {
        Log.i(TAG, msg)
        val f = logFile ?: File(context.applicationContext.filesDir, "brightness_log.txt").also { logFile = it }
        try {
            f.appendText("${STAMP.format(Date())} $msg\n")
            // Лог не должен расти бесконечно.
            if (f.length() > 256 * 1024) f.writeText("")
        } catch (_: Exception) {
        }
    }

    /** Блокирующее чтение — только с фонового потока. */
    fun read(context: Context): State? = State.parse(readRaw(context))

    fun readRaw(context: Context): IntArray? = desay(context).readRaw(5, 1, logErrors = false)

    /** Блокирующая запись уровня активного режима (как штатный ползунок). */
    fun writeLevel(context: Context, level: Int): Boolean {
        val s = read(context)
        if (s == null) {
            log(context, "уровень $level: (5,1) не читается — запись отменена")
            return false
        }
        return writeLevel(context, s, level)
    }

    private fun writeLevel(context: Context, s: State, level: Int): Boolean {
        val r = desay(context)
        val pv = s.previewData(level)
        val ok1 = r.setProperty(PROP_PREVIEW, AREA_PREVIEW, *pv)
        val data = s.setData(level)
        val ok2 = r.setProperty(PROP_SET, s.setArea, *data)
        log(context, "уровень ${clamp(level)} (${if (s.isNight) "ночь" else "день"}): " +
            "preview ${pv.contentToString()} ok=$ok1; set area=${s.setArea} ${data.contentToString()} ok=$ok2")
        return ok1 && ok2
    }

    /** Блокирующая запись обеих ячеек (день и ночь) — возврат как было. */
    fun writeBoth(context: Context, dayLevel: Int, nightLevel: Int): Boolean {
        val s = read(context)
        if (s == null) {
            log(context, "вернуть $dayLevel/$nightLevel: (5,1) не читается")
            return false
        }
        val r = desay(context)
        val d = clamp(dayLevel) - 1
        val n = clamp(nightLevel) - 1
        val ok1 = r.setProperty(PROP_PREVIEW, AREA_PREVIEW, if (s.isNight) n else d, n)
        val ok2 = r.setProperty(PROP_SET, s.setArea, d, n)
        log(context, "вернуть день ${d + 1} ночь ${n + 1}: area=${s.setArea} ok=${ok1 && ok2}")
        return ok1 && ok2
    }

    /** Асинхронно прочитать; [cb] — на главном потоке. */
    fun readAsync(context: Context, cb: (State?) -> Unit) {
        val app = context.applicationContext
        io.execute {
            val s = read(app)
            main.post { cb(s) }
        }
    }

    // --- Заставка ---

    /**
     * Убавить подсветку до [level] (заставка). Исходные день/ночь и то, что
     * поставили, — в prefs до [undim]. [cb] (main): true — Desay принял,
     * false — пути нет (тогда заставка гасит атрибутом окна).
     */
    fun dim(context: Context, level: Int, cb: (Boolean) -> Unit) {
        val app = context.applicationContext
        io.execute {
            val s = read(app)
            if (s == null) {
                main.post { cb(false) }
                return@execute
            }
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // Незакрытый прошлый dim (падение) — исходные значения там, не в шине.
            val saved = Saved.parse(prefs.getString(KEY_RESTORE, null))
            val orig = saved ?: Saved(s.dayLevel, s.nightLevel, s.isNight, clamp(level))
            val rec = orig.copy(night = s.isNight, set = clamp(level))
            prefs.edit().putString(KEY_RESTORE, rec.encode()).commit()
            log(app, "заставка: убавить до ${rec.set} (было день ${orig.day} ночь ${orig.nightLevel})")
            val ok = writeLevel(app, s, rec.set)
            main.post { cb(ok) }
        }
    }

    /**
     * Вернуть подсветку после [dim]. Если ячейка, которую убавляли, уже не
     * равна поставленному (штатный ползунок, другая программа) — не трогаем.
     */
    fun undim(context: Context) {
        val app = context.applicationContext
        io.execute {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = Saved.parse(prefs.getString(KEY_RESTORE, null)) ?: return@execute
            val s = read(app) ?: return@execute // нет связи — попробуем в следующий раз
            val cell = if (saved.night) s.nightLevel else s.dayLevel
            if (cell == saved.set) {
                log(app, "заставка: вернуть день ${saved.day} ночь ${saved.nightLevel}")
                writeBoth(app, saved.day, saved.nightLevel)
            } else {
                log(app, "заставка: уровень изменён ($cell ≠ ${saved.set}) — не возвращаем")
            }
            prefs.edit().remove(KEY_RESTORE).commit()
        }
    }

    /** Запись «к возврату»: исходные день/ночь, какую ячейку убавили и до чего. */
    data class Saved(val day: Int, val nightLevel: Int, val night: Boolean, val set: Int) {
        fun encode(): String = "$day,$nightLevel,${if (night) 1 else 0},$set"

        companion object {
            fun parse(s: String?): Saved? {
                val p = s?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: return null
                if (p.size != 4) return null
                return Saved(clamp(p[0]), clamp(p[1]), p[2] != 0, clamp(p[3]))
            }
        }
    }

    private val STAMP = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)
}
