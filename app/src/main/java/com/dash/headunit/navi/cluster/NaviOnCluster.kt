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
 *  - стек ужимается до центральной полосы ([band], ширина как у нашей
 *    карточки), иначе края карты и кнопки уйдут под шкалы.
 * Обратно — `am display move-stack <стек> 0`.
 *
 * Android 9 (ГУ): RESUMED одна activity на все дисплеи — после переноса
 * навигатор забирает фокус, дашборд надо поднять обратно (MainActivity).
 * В PAUSED Yandex Navi продолжает рисовать (так же, как в блоке дашборда).
 * Спец. возможности Android 9 видят окна только главного дисплея — пока
 * навигатор на приборке, наше ведение из его экрана не читается.
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
    private val main = Handler(Looper.getMainLooper())

    /**
     * Перенести навигатор [pkg] на дисплей [displayId] и ужать до [band].
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
                    lastStatus = "навигатор на приборке (стек ${s.id}, дисплей $displayId)"
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
    private val STACK_RE =
        Regex("""^Stack id=(\d+) bounds=\[(\d+),(\d+)]\[(\d+),(\d+)] displayId=(\d+)""")
}
