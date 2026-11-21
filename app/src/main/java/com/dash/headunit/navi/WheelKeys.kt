package com.dash.headunit.navi

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import com.dash.headunit.navi.cluster.NaviAccessibilityService
import com.dash.headunit.navi.cluster.YANDEX_NAVI
import com.dash.headunit.settings.AppSettings

/**
 * Алиса Яндекс Навигатора по кнопке на руле.
 *
 * Кнопки руля на ГУ Desay — обычные Android KeyEvent. Системная служба Desay
 * `key_policy` (KeyPolicyImpl: штатный голосовой помощник iFlytek и т.п.)
 * разбирает их в `PhoneWindowManager.interceptKeyBeforeDispatching` — уже
 * после фильтра клавиш служб спец. возможностей. Поэтому
 * [NaviAccessibilityService] с FLAG_REQUEST_FILTER_KEY_EVENTS видит кнопку
 * первым и, вернув true, забирает её: штатный помощник не запускается, а
 * мы зовём Алису ([trigger]). Фильтр включается только когда функция
 * включена или идёт назначение кнопки ([filterWanted]) — иначе через наш
 * процесс шли бы все клавиши ГУ.
 *
 * Вызовы [onKey] — в главном потоке службы; только быстрые проверки.
 */
object WheelKeys {

    /** Что сделать с клавишей. Чистая часть — тестируется. */
    enum class Action { PASS, CONSUME, CONSUME_LEARN, CONSUME_TRIGGER }

    /**
     * Решение по клавише [keyCode]/[action]/[repeat]: идёт назначение —
     * забираем всё, отпускание назначает; функция включена и код совпал —
     * забираем, первое нажатие зовёт Алису (не чаще [TRIGGER_GAP_MS]).
     */
    internal fun decide(
        learning: Boolean,
        enabled: Boolean,
        assigned: Int,
        keyCode: Int,
        action: Int,
        repeat: Int,
        now: Long,
        lastTrigger: Long
    ): Action {
        if (learning) return if (action == KeyEvent.ACTION_UP) Action.CONSUME_LEARN else Action.CONSUME
        if (!enabled || assigned < 0 || keyCode != assigned) return Action.PASS
        if (action == KeyEvent.ACTION_DOWN && repeat == 0 && now - lastTrigger >= TRIGGER_GAP_MS) {
            return Action.CONSUME_TRIGGER
        }
        return Action.CONSUME
    }

    /** Одна клавиша в журнале (для диалога настроек). */
    data class Seen(val at: Long, val keyCode: Int, val scanCode: Int, val action: Int, val handled: String)

    // Лениво: чистая [decide] гоняется в юнит-тестах без Android.
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val journal = ArrayDeque<Seen>()

    @Volatile private var enabledCache = false
    @Volatile private var keyCache = -1
    private var appContext: Context? = null
    private var lastTrigger = 0L

    private var learnUntil = 0L
    private var onLearned: ((Int) -> Unit)? = null

    /** Изменения для открытого диалога (журнал, назначение). */
    var onChanged: (() -> Unit)? = null

    /**
     * Хук дашборда: если он на экране и в блоке Яндекс Навигатор — сам
     * проводит сессию ([NaviUnderlay.aliceSession] с возвратом дашборда).
     * Возвращает true, если взялся.
     */
    var onAliceRequest: (() -> Boolean)? = null

    /** Прочитать настройки; вызывать при старте службы и при их смене. */
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        enabledCache = AppSettings.wheelAlice(app)
        keyCache = AppSettings.wheelAliceKey(app)
        applyFilter()
    }

    val learning: Boolean get() = SystemClock.uptimeMillis() < learnUntil

    /** Нужен ли фильтр клавиш службе сейчас. */
    fun filterWanted(): Boolean = learning || WheelKeyProbe.running || (enabledCache && keyCache >= 0)

    /** Включить/выключить фильтр клавиш у службы по [filterWanted]. */
    fun applyFilter() {
        NaviAccessibilityService.instance?.setKeyFilter(filterWanted())
    }

    /** [applyFilter] с любого потока. */
    fun applyFilterAsync() {
        main.post { applyFilter() }
    }

    /** Назначить кнопку: следующая отпущенная клавиша в течение [LEARN_MS]. */
    fun startLearning(context: Context, done: (Int) -> Unit) {
        appContext = context.applicationContext
        learnUntil = SystemClock.uptimeMillis() + LEARN_MS
        onLearned = done
        applyFilter()
        main.postDelayed({ if (!learning) stopLearning() }, LEARN_MS + 50)
    }

    fun stopLearning() {
        learnUntil = 0L
        onLearned = null
        applyFilter()
        onChanged?.invoke()
    }

    /** Последние клавиши, свежие первыми. */
    fun journal(): List<Seen> = synchronized(journal) { journal.toList().asReversed() }

    /** Клавиша от службы спец. возможностей. true — забрали. */
    fun onKey(e: KeyEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        val act = decide(learning, enabledCache, keyCache, e.keyCode, e.action, e.repeatCount, now, lastTrigger)
        remember(e, act)
        if (WheelKeyProbe.running) WheelKeyProbe.onA11yKey(e)
        when (act) {
            Action.PASS, Action.CONSUME -> Unit
            Action.CONSUME_LEARN -> {
                val ctx = appContext
                val cb = onLearned
                learnUntil = 0L
                onLearned = null
                if (ctx != null) {
                    AppSettings.setWheelAliceKey(ctx, e.keyCode)
                    keyCache = e.keyCode
                }
                main.post {
                    applyFilter()
                    cb?.invoke(e.keyCode)
                }
            }
            Action.CONSUME_TRIGGER -> {
                lastTrigger = now
                main.post { trigger() }
            }
        }
        return act != Action.PASS
    }

    private fun remember(e: KeyEvent, act: Action) {
        // Повторы удержания не пишем — журнал не забивается.
        if (e.repeatCount > 0) return
        synchronized(journal) {
            journal.addLast(
                Seen(System.currentTimeMillis(), e.keyCode, e.scanCode, e.action, act.name)
            )
            while (journal.size > JOURNAL_SIZE) journal.removeFirst()
        }
        onChanged?.let { main.post(it) }
    }

    /**
     * Позвать Алису: дашборд с навигатором в блоке — сессия с возвратом;
     * навигатор наверху — сразу нажать его кнопку голоса; иначе поднять
     * навигатор и нажать.
     */
    fun trigger() {
        val ctx = appContext ?: return
        if (onAliceRequest?.invoke() == true) return
        val a11y = NaviAccessibilityService.instance
            ?: return status(ctx, "служба спец. возможностей навигатора выключена")
        a11y.clickVoice { ok, why ->
            if (ok) {
                status(ctx, null, "кнопка голоса: $why")
                return@clickVoice
            }
            // Окна навигатора нет (он не запущен или не наверху) — поднять.
            val navi = ctx.packageManager.getLaunchIntentForPackage(YANDEX_NAVI)
                ?: return@clickVoice status(ctx, "Яндекс Навигатор не установлен")
            try {
                ctx.startActivity(navi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            } catch (e: Exception) {
                return@clickVoice status(ctx, "${e.javaClass.simpleName}: ${e.message}")
            }
            main.postDelayed({
                a11y.clickVoice { ok2, why2 ->
                    if (ok2) status(ctx, null, "навигатор поднят, кнопка голоса: $why2")
                    else status(ctx, "не нажалась кнопка голоса: $why2")
                }
            }, NaviUnderlay.ALICE_RESUME_MS * 2)
        }
    }

    private fun status(ctx: Context, error: String?, info: String? = null) {
        NaviUnderlay.note("Алиса (руль): ${error ?: info}")
        if (error != null) Toast.makeText(ctx, "Алиса: $error", Toast.LENGTH_LONG).show()
    }

    private const val LEARN_MS = 15_000L
    private const val TRIGGER_GAP_MS = 2_000L
    private const val JOURNAL_SIZE = 20
}
