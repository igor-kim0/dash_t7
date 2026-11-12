package com.dash.headunit.navi.cluster

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dash.headunit.navi.AdbShell
import java.util.concurrent.Executors

/**
 * Сам Яндекс Навигатор на приборке: задача навигатора переносится на
 * дисплей приборки (id 1) через adb-канал ([AdbShell], uid shell):
 *  - уже запущен на ГУ — `am display move-stack <стек> <дисплей>`;
 *  - не запущен — `am start --display <дисплей>` (Android 9 всё равно
 *    открывает его на главном экране — тогда тоже move-stack);
 *  - стек растягивается на весь дисплей приборки ([band]).
 * Обратно — `am display move-stack <стек> 0`.
 *
 * Android 9 (ГУ): RESUMED одна activity на все дисплеи — после переноса
 * навигатор забирает фокус, дашборд надо поднять обратно (MainActivity).
 * В PAUSED Yandex Navi продолжает рисовать (так же, как в блоке дашборда).
 * Пока навигатор на приборке, его ведение не читаем вовсе
 * ([GuidanceHub.paused]): карточки нет, а спец. возможности Android 9
 * всё равно видят окна только главного дисплея.
 *
 * На ГУ не проверено (2026-11-10): неизвестно, пустит ли прошивка
 * activity на presentation-дисплей приборки.
 */
object NaviOnCluster {

    @Volatile
    var lastStatus: String = "не запускалось"
        private set(value) {
            field = value
            Log.i(TAG, value)
        }

    /** Навигатор сейчас на дисплее приборки (по последней проверке). */
    @Volatile
    var onCluster = false
        private set

    private val worker = Executors.newSingleThreadExecutor()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Перенести навигатор [pkg] на дисплей [displayId] с границами [band].
     * [done] — в UI-потоке, аргумент — навигатор на приборке.
     */
    fun moveToCluster(context: Context, pkg: String, displayId: Int, band: Rect, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val ok = try {
                toCluster(app, pkg, displayId, band)
            } catch (e: Exception) {
                lastStatus = "на приборку: ${e.javaClass.simpleName}: ${e.message}"
                false
            }
            onCluster = ok
            main.post { done(ok) }
        }
    }

    /**
     * «Пинок» навигатору [pkg] на дисплее [displayId]: дождаться, пока его окно
     * там нарисовано (процесс готов), и поднять его задачу — он станет RESUMED.
     * Yandex Navi включает слежение за GPS в onResume: после холодного старта
     * или сна ГУ карта на приборке иначе стоит (пользователь 2026-11-12).
     * На главном экране ничего не видно: навигатор на другом дисплее.
     * Вернуть дашборд наверх — забота вызывающего (через пару секунд).
     * [done] — в UI-потоке, аргумент — навигатор поднят.
     */
    fun kick(context: Context, pkg: String, displayId: Int, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val ok = try {
                kickNow(app, pkg, displayId)
            } catch (e: Exception) {
                lastStatus = "пинок: ${e.javaClass.simpleName}: ${e.message}"
                false
            }
            main.post { done(ok) }
        }
    }

    private fun kickNow(app: Context, pkg: String, displayId: Int): Boolean {
        val s = findStack(app, pkg)
        if (s == null || s.display != displayId) {
            lastStatus = "пинок: навигатор не на дисплее $displayId"
            return false
        }
        val cmp = app.packageManager.getLaunchIntentForPackage(pkg)?.component ?: return false
        // Пинок до готовности процесса пропадает впустую (am_failed_to_pause).
        val drawn = waitDrawn(app, pkg, displayId)
        // Задача уже есть — am start только поднимает её на её дисплее.
        run(app, "am start --display $displayId -n ${cmp.flattenToShortString()} >/dev/null")
        // Подстраховка: если прошивка утащила его на главный экран — вернуть.
        val now = findStack(app, pkg)
        if (now != null && now.display != displayId) {
            run(app, "am display move-stack ${now.id} $displayId")
        }
        lastStatus = "навигатор на приборке поднят для onResume" + if (drawn) "" else " (не дождались отрисовки)"
        return true
    }

    /** Вернуть навигатор [pkg] с приборки на главный экран. [done] — в UI-потоке. */
    fun moveBack(context: Context, pkg: String, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val ok = try {
                back(app, pkg)
            } catch (e: Exception) {
                lastStatus = "с приборки: ${e.javaClass.simpleName}: ${e.message}"
                false
            }
            if (ok) onCluster = false
            main.post { done(ok) }
        }
    }

    private fun toCluster(app: Context, pkg: String, displayId: Int, band: Rect): Boolean {
        var stack = findStack(app, pkg)
        if (stack == null) {
            val cmp = app.packageManager.getLaunchIntentForPackage(pkg)?.component
            if (cmp == null) {
                lastStatus = "нет запускаемой активности: $pkg"
                return false
            }
            // Android 9 (эмулятор 2026-11-10) запускает его всё равно на
            // главном экране — тогда переносим ниже, когда стек появится.
            run(app, "am start --display $displayId -n ${cmp.flattenToShortString()} >/dev/null")
        }
        var moved = false
        repeat(STACK_ATTEMPTS) { attempt ->
            val s = findStack(app, pkg).also { stack = it }
            when {
                s != null && s.display == displayId -> {
                    if (s.bounds != band) run(app, "am stack resize ${s.id} ${band.left} ${band.top} ${band.right} ${band.bottom}")
                    // Наше чёрное окно на приборке снимается только после этого:
                    // без него дисплей 1 зеркалит главный экран, пока навигатор
                    // не нарисовался на новом месте (пользователь 2026-11-12).
                    val drawn = waitDrawn(app, pkg, displayId)
                    lastStatus = "навигатор на приборке (стек ${s.id}, дисплей $displayId" +
                        if (drawn) ")" else ", не дождались отрисовки)"
                    return true
                }
                // Новая задача появляется в списке не сразу; переносим один раз.
                s != null && !moved -> {
                    moved = true
                    run(app, "am display move-stack ${s.id} $displayId")
                }
                attempt < STACK_ATTEMPTS - 1 -> Thread.sleep(STACK_RETRY_MS)
            }
        }
        lastStatus = "навигатор не перенёсся на дисплей $displayId (стек: ${stack?.let { "${it.id} на ${it.display}" } ?: "нет"})"
        return false
    }

    private fun back(app: Context, pkg: String): Boolean {
        val stack = findStack(app, pkg)
        if (stack == null || stack.display == 0) {
            lastStatus = "навигатор не на приборке"
            return true
        }
        run(app, "am display move-stack ${stack.id} 0")
        val now = findStack(app, pkg)
        val ok = now == null || now.display == 0
        // Границы полосы приборки переезжают вместе со стеком — вернуть весь экран
        // (блок «Навигатор» потом сам ужмёт его до своего прямоугольника).
        val full = mainScreen(app)
        if (now != null && now.display == 0 && now.bounds != full) {
            run(app, "am stack resize ${now.id} ${full.left} ${full.top} ${full.right} ${full.bottom}")
        }
        lastStatus = if (ok) "навигатор вернулся на главный экран" else "навигатор остался на дисплее ${now?.display}"
        return ok
    }

    /**
     * Ждать, пока окно [pkg] на дисплее [displayId] нарисуется
     * (`mDrawState=HAS_DRAWN`), не дольше [DRAWN_TIMEOUT_MS]. Первая проверка —
     * через [DRAWN_FIRST_MS]: сразу после переноса в dumpsys ещё старое состояние.
     */
    private fun waitDrawn(app: Context, pkg: String, displayId: Int): Boolean {
        val deadline = System.currentTimeMillis() + DRAWN_TIMEOUT_MS
        Thread.sleep(DRAWN_FIRST_MS)
        while (true) {
            val out = AdbShell.exec(app, "dumpsys window windows | grep -E 'Window #|mDisplayId=|mDrawState'")
            if (out != null && isDrawnOn(out, pkg, displayId)) {
                // Кадр ещё должен дойти по HDMI до приборки.
                Thread.sleep(DRAWN_SETTLE_MS)
                return true
            }
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(DRAWN_POLL_MS)
        }
    }

    /**
     * Разбор `dumpsys window windows` (Android 9): у окна пакета [pkg]
     * (`Window #N Window{… pkg/…}`) строка `mDisplayId=<id> …` и
     * `mDrawState=HAS_DRAWN`.
     */
    internal fun isDrawnOn(dump: String, pkg: String, displayId: Int): Boolean {
        var inPkg = false
        var display = -1
        for (line in dump.lineSequence()) {
            val t = line.trimStart()
            if (t.startsWith("Window #")) {
                inPkg = t.contains(" $pkg/")
                display = -1
            } else if (inPkg && t.startsWith("mDisplayId=")) {
                display = t.removePrefix("mDisplayId=").takeWhile { it.isDigit() }.toIntOrNull() ?: -1
            } else if (inPkg && display == displayId && t.contains("mDrawState=HAS_DRAWN")) {
                return true
            }
        }
        return false
    }

    private fun mainScreen(app: Context): Rect {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val p = android.graphics.Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(p)
        return Rect(0, 0, p.x, p.y)
    }

    /** Выполнить [cmd]; успех — команда ничего не напечатала. */
    private fun run(app: Context, cmd: String): Boolean {
        val out = AdbShell.exec(app, "$cmd 2>&1; echo __ok__")
        val ok = out != null && out.contains("__ok__") && out.substringBefore("__ok__").isBlank()
        lastStatus = if (ok) cmd else "$cmd → ${out?.trim() ?: AdbShell.lastStatus}"
        return ok
    }

    internal data class Stack(val id: Int, val bounds: Rect, val display: Int)

    private fun findStack(app: Context, pkg: String): Stack? {
        val out = AdbShell.exec(app, "am stack list") ?: return null
        return parseStack(out, pkg)
    }

    /**
     * Стек с задачей [pkg] на любом дисплее. Разбор `am stack list` (Android 9):
     * `Stack id=31 bounds=[19,86][652,701] displayId=0 ...`, ниже задачи
     * `  taskId=135: ru.yandex.yandexnavi/... bounds=...`.
     */
    internal fun parseStack(list: String, pkg: String): Stack? {
        var current: Stack? = null
        for (line in list.lineSequence()) {
            val m = STACK_RE.find(line)
            if (m != null) {
                val (id, l, t, r, b, display) = m.destructured
                current = Stack(id.toInt(), Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt()), display.toInt())
            } else if (current != null && line.trimStart().startsWith("taskId=") && line.contains(": $pkg/")) {
                return current
            }
        }
        return null
    }

    private const val TAG = "NaviOnCluster"
    private const val STACK_ATTEMPTS = 8
    private const val STACK_RETRY_MS = 400L
    private const val DRAWN_FIRST_MS = 500L
    private const val DRAWN_POLL_MS = 300L
    private const val DRAWN_SETTLE_MS = 200L
    private const val DRAWN_TIMEOUT_MS = 8_000L
    private val STACK_RE =
        Regex("""^Stack id=(\d+) bounds=\[(\d+),(\d+)]\[(\d+),(\d+)] displayId=(\d+)""")
}
