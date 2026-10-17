package com.dash.headunit.embed

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.View

/**
 * Встраивание чужого приложения в наш виджет через android.app.ActivityView.
 * На Android 9 класс скрыт (greylist) — только рефлексия, всё в try/catch.
 * Как у VDLauncher: ActivityView + запуск через PendingIntent.
 *
 * Требования к цели: resizable activity (карты/навигаторы обычно да).
 * Без системных разрешений работает базовый сценарий; для полного
 * управления задачами желательна системная установка (как у VDLauncher).
 */
object AppEmbed {

    @Volatile
    var lastStatus: String = "не запускалось"
        private set(value) {
            field = value
            // История статусов — для отчёта «Логи для Claude».
            synchronized(history) {
                history.add("${System.currentTimeMillis()} $value")
                while (history.size > HISTORY_MAX) history.removeAt(0)
            }
        }

    private val history = mutableListOf<String>()

    /** Последние статусы встраивания (unix-время мс + текст). */
    fun statusHistory(): List<String> = synchronized(history) { history.toList() }

    /** Последний start упал, потому что ActivityView ещё не готова (нет surface). */
    @Volatile
    var lastNotReady: Boolean = false
        private set

    /** true если класс ActivityView вообще доступен на устройстве. */
    fun isSupported(): Boolean {
        return try {
            Class.forName("android.app.ActivityView")
            true
        } catch (e: Exception) {
            Log.w(TAG, "probe", e)
            lastStatus = "ActivityView отсутствует: ${e.javaClass.simpleName}"
            false
        }
    }

    /** Сигнатуры startActivity у ActivityView (для диагностики). */
    fun methodSignatures(): List<String> {
        return try {
            val cls = Class.forName("android.app.ActivityView")
            cls.methods.filter { it.name == "startActivity" }.map { m ->
                "startActivity(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")"
            }
        } catch (e: Exception) {
            listOf("нет класса: ${e.javaClass.simpleName}")
        } catch (e: Error) {
            listOf("нет класса: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Создаёт View ActivityView и добавляет в контейнер.
     * Возвращает созданную вью или null.
     * На ГУ нет стандартного <init>(Context, AttributeSet) — перебираем
     * доступные конструкторы (Context первым аргументом).
     */
    fun createView(context: Context): View? {
        return try {
            val cls = Class.forName("android.app.ActivityView")
            val (view, sig) = instantiate(cls, context)
            lastStatus = "ActivityView создан ($sig)"
            view
        } catch (e: Exception) {
            Log.w(TAG, "create", e)
            lastStatus = "create: ${e.javaClass.simpleName}: ${e.message}"
            null
        } catch (e: Error) {
            Log.w(TAG, "create", e)
            lastStatus = "create: ${e.javaClass.simpleName}"
            null
        }
    }

    private fun instantiate(cls: Class<*>, context: Context): Pair<View, String> {
        val candidates = cls.constructors.sortedBy { it.parameterTypes.size }
        for (ctor in candidates) {
            val p = ctor.parameterTypes
            if (p.isEmpty() || p[0] != Context::class.java) continue
            val args = arrayOfNulls<Any>(p.size)
            args[0] = context
            var bad = false
            for (i in 1 until p.size) {
                when {
                    p[i] == AttributeSet::class.java -> args[i] = null
                    p[i] == Int::class.javaPrimitiveType -> args[i] = 0
                    p[i] == Boolean::class.javaPrimitiveType -> args[i] = false
                    else -> bad = true
                }
            }
            if (bad) continue
            try {
                val view = ctor.newInstance(*args) as? View ?: continue
                val sig = "(" + p.joinToString(",") { it.simpleName } + ")"
                return view to sig
            } catch (e: Exception) {
                Log.w(TAG, "ctor $p failed", e)
            } catch (e: Error) {
                Log.w(TAG, "ctor $p failed", e)
            }
        }
        throw NoSuchMethodException("нет <init> с Context у ${cls.name}")
    }

    /**
     * Запускает intent пакета внутри ActivityView.
     * Возвращает true если вызов ушёл без исключений.
     */
    fun start(view: View, context: Context, intent: Intent): Boolean {
        lastNotReady = false
        return try {
            val flags = if (Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                @Suppress("UnspecifiedImmutableFlag")
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = PendingIntent.getActivity(context, 0, intent, flags)
            // Ищем startActivity(PendingIntent), иначе 4-аргументный вариант.
            val cls = view.javaClass
            try {
                val m = cls.getMethod("startActivity", PendingIntent::class.java)
                m.invoke(view, pending)
            } catch (e: NoSuchMethodException) {
                var done = false
                for (m in cls.methods) {
                    if (m.name == "startActivity" && m.parameterTypes.size == 4) {
                        m.invoke(view, pending, null, null, null)
                        done = true
                        break
                    }
                }
                if (!done) throw e
            }
            lastStatus = "запущено: ${intent.component?.flattenToShortString() ?: intent.`package`}"
            true
        } catch (e: Exception) {
            Log.w(TAG, "start", e)
            // Обёртка рефлексии: настоящая причина — в cause.
            lastNotReady = (e.cause ?: e) is IllegalStateException
            lastStatus = "start: ${e.javaClass.simpleName}: ${cause(e)}"
            false
        } catch (e: Error) {
            Log.w(TAG, "start", e)
            lastStatus = "start: ${e.javaClass.simpleName}"
            false
        }
    }

    /** Освобождение ресурсов ActivityView (если метод есть). */
    fun release(view: View?) {
        if (view == null) return
        try {
            view.javaClass.getMethod("release").invoke(view)
        } catch (e: Exception) {
            // Метода может не быть — поверхность умрёт сама.
        } catch (e: Error) {
            // Игнорируем.
        }
    }

    private fun cause(e: Exception): String {
        val c = e.cause
        return if (c != null) "${c.javaClass.simpleName}: ${c.message}" else (e.message ?: "—")
    }

    private const val TAG = "AppEmbed"
    private const val HISTORY_MAX = 50
}
