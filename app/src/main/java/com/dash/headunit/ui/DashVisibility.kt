package com.dash.headunit.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Виден ли дашборд: MainActivity на экране (между onStart и «настоящим»
 * onStop — не от наших же переключений навигатора) и не закрыт заставкой.
 *
 * Виджеты подписаны на хабы по attach/detach, а при onStop (навигатор на
 * весь экран, другое приложение) и под заставкой они остаются
 * прикреплёнными — опросы для них шли впустую. Хабы, которые кормят
 * только виджеты, ждут здесь видимости ([awaitVisible]).
 *
 * Не для фоновой работы: журнал поездок, шторка, приборка, ELM-счётчики
 * идут всегда. По умолчанию — виден (тесты, запуск без активности).
 */
object DashVisibility {

    private val state = MutableStateFlow(true)
    private val listeners = mutableListOf<(Boolean) -> Unit>()

    @Volatile private var started = true
    @Volatile private var screensaver = false

    val visible: Boolean get() = state.value

    /** onStart / «настоящий» onStop MainActivity (главный поток). */
    fun setStarted(on: Boolean) {
        started = on
        update()
    }

    /** Заставка показана / убрана (главный поток). */
    fun setScreensaver(on: Boolean) {
        screensaver = on
        update()
    }

    /** Ждать, пока дашборд снова виден (сразу, если уже). */
    suspend fun awaitVisible() {
        state.first { it }
    }

    /** Для хабов на Handler'ах; вызывается в главном потоке при смене. */
    fun addListener(l: (Boolean) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (Boolean) -> Unit) {
        listeners.remove(l)
    }

    private fun update() {
        val v = started && !screensaver
        if (state.value == v) return
        state.value = v
        listeners.toList().forEach { it(v) }
    }
}
