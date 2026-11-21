package com.dash.headunit.navi

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import com.dash.headunit.navi.cluster.NaviAccessibilityService
import com.dash.headunit.navi.cluster.YANDEX_NAVI
import com.dash.headunit.settings.AppSettings
import java.util.concurrent.Executors

/**
 * Алиса Яндекс Навигатора по кнопке на руле.
 *
 * Кнопки руля на ГУ Desay — обычные Android KeyEvent (голос = 292
 * KEYCODE_VR, scan 793), но фильтру клавиш службы спец. возможностей они
 * не приходят вовсе (проба HU 2026-11-21). Слушаем их своим колбэком в
 * системной службе Desay key_policy ([KeyPolicyTap]) — он видит все кнопки
 * руля, но забрать клавишу не может: штатная реакция остаётся. Поэтому
 * Алиса — по ДОЛГОМУ нажатию ([LONG_MS]): штатный помощник долгое нажатие
 * VR пропускает («vr key is long pressed, so return»), короткое остаётся
 * ему. Колбэк регистрируется только когда функция включена или идёт
 * назначение ([wanted]).
 *
 * Клавиши приходят в binder-потоке и переносятся в главный.
 */
object WheelKeys {

    /** Что сделать с клавишей. Чистая часть — тестируется. */
    enum class Action { NONE, LEARN, TRIGGER }

    /**
     * Решение по клавише: идёт назначение — отпускание назначает; функция
     * включена и код совпал — удержание [holdMs] ≥ [LONG_MS] зовёт Алису
     * (один раз на нажатие: [downTime] ≠ [triggeredDown]). Срабатывает и
     * на повторе удержания, и на отпускании — если повторов не было.
     */
    internal fun decide(
        learning: Boolean,
        enabled: Boolean,
        assigned: Int,
        keyCode: Int,
        action: Int,
        holdMs: Long,
        downTime: Long,
        triggeredDown: Long
    ): Action {
        if (learning) return if (action == KeyEvent.ACTION_UP) Action.LEARN else Action.NONE
        if (!enabled || assigned < 0 || keyCode != assigned) return Action.NONE
        if (downTime == triggeredDown || holdMs < LONG_MS) return Action.NONE
        return Action.TRIGGER
    }

    /** Одна клавиша в журнале (для диалога настроек). */
    data class Seen(val at: Long, val keyCode: Int, val scanCode: Int, val action: Int, val handled: String)

    // Лениво: чистая [decide] гоняется в юнит-тестах без Android.
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val io by lazy { Executors.newSingleThreadExecutor { Thread(it, "WheelKeys") } }
    private val journal = ArrayDeque<Seen>()
    private val tap by lazy { KeyPolicyTap(TAP_NAME) { e -> main.post { onKey(e) } } }

    @Volatile private var enabledCache = false
    @Volatile private var keyCache = -1
    private var appContext: Context? = null
    private var triggeredDown = -1L

    private var learnUntil = 0L
    private var onLearned: ((Int) -> Unit)? = null

    /** Состояние колбэка key_policy — для диалога. */
    @Volatile var tapStatus: String = "колбэк не зарегистрирован"
        private set

    /** Изменения для открытого диалога (журнал, назначение). */
    var onChanged: (() -> Unit)? = null

    /**
     * Хук дашборда: если он на экране и в блоке Яндекс Навигатор — сам
     * проводит сессию ([NaviUnderlay.aliceSession] с возвратом дашборда).
     * Возвращает true, если взялся.
     */
    var onAliceRequest: (() -> Boolean)? = null

    /** Прочитать настройки; вызывать при старте и при их смене. */
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        enabledCache = AppSettings.wheelAlice(app)
        keyCache = AppSettings.wheelAliceKey(app)
        applyTap()
    }

    val learning: Boolean get() = SystemClock.uptimeMillis() < learnUntil

    /** Нужен ли колбэк key_policy сейчас. */
    private fun wanted(): Boolean = learning || (enabledCache && keyCache >= 0)

    /** Зарегистрировать/снять колбэк по [wanted] (binder — в своём потоке). */
    private fun applyTap() {
        val ctx = appContext ?: return
        val want = wanted()
        io.execute {
            if (want && !tap.registered) {
                tapStatus = tap.register(ctx)
                Log.i(TAG, tapStatus)
            } else if (!want && tap.registered) {
                tap.unregister(ctx)
                tapStatus = "колбэк снят"
            }
            onChanged?.let { main.post(it) }
        }
    }

    /** Назначить кнопку: следующая отпущенная клавиша в течение [LEARN_MS]. */
    fun startLearning(context: Context, done: (Int) -> Unit) {
        appContext = context.applicationContext
        learnUntil = SystemClock.uptimeMillis() + LEARN_MS
        onLearned = done
        applyTap()
        main.postDelayed({ if (!learning && onLearned != null) stopLearning() }, LEARN_MS + 50)
    }

    fun stopLearning() {
        learnUntil = 0L
        onLearned = null
        applyTap()
        onChanged?.invoke()
    }

    /** Последние клавиши, свежие первыми. */
    fun journal(): List<Seen> = synchronized(journal) { journal.toList().asReversed() }

    /** Клавиша из key_policy (главный поток). */
    private fun onKey(e: KeyEvent) {
        val act = decide(
            learning, enabledCache, keyCache, e.keyCode, e.action,
            e.eventTime - e.downTime, e.downTime, triggeredDown
        )
        remember(e, act)
        when (act) {
            Action.NONE -> Unit
            Action.LEARN -> {
                val ctx = appContext
                val cb = onLearned
                learnUntil = 0L
                onLearned = null
                if (ctx != null) {
                    AppSettings.setWheelAliceKey(ctx, e.keyCode)
                    keyCache = e.keyCode
                }
                applyTap()
                cb?.invoke(e.keyCode)
            }
            Action.TRIGGER -> {
                triggeredDown = e.downTime
                trigger()
            }
        }
    }

    private fun remember(e: KeyEvent, act: Action) {
        // Повторы удержания не пишем — журнал не забивается.
        if (e.repeatCount > 0 && act == Action.NONE) return
        synchronized(journal) {
            journal.addLast(
                Seen(System.currentTimeMillis(), e.keyCode, e.scanCode, e.action, act.name)
            )
            while (journal.size > JOURNAL_SIZE) journal.removeFirst()
        }
        onChanged?.invoke()
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

    private const val TAG = "WheelKeys"
    private const val TAP_NAME = "dash_wheel_alice"
    private const val LEARN_MS = 15_000L
    /** Удержание, после которого зовём Алису. */
    internal const val LONG_MS = 800L
    private const val JOURNAL_SIZE = 20
}
