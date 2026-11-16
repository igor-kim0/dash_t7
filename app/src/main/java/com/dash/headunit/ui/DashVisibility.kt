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
    @Volatile private var musicSaver = false

    val visible: Boolean get() = state.value

    // Музыкальные хабы (плеер, спектр) нужны и под заставкой «Цветомузыка».
    private val music = MutableStateFlow(true)
    private val musicListeners = mutableListOf<(Boolean) -> Unit>()

    /** Дашборд виден или показана заставка, которой нужна музыка. */
    val musicVisible: Boolean get() = music.value

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

    /** Показан фон заставки, которому нужны плеер и спектр (главный поток). */
    fun setMusicSaver(on: Boolean) {
        musicSaver = on
        update()
    }

    /** Ждать [musicVisible] (сразу, если уже). */
    suspend fun awaitMusicVisible() {
        music.first { it }
    }

    /** Как [addListener], но для [musicVisible]. */
    fun addMusicListener(l: (Boolean) -> Unit) {
        musicListeners.add(l)
    }

    fun removeMusicListener(l: (Boolean) -> Unit) {
        musicListeners.remove(l)
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
        val mv = started && (!screensaver || musicSaver)
        if (music.value != mv) {
            music.value = mv
            musicListeners.toList().forEach { it(mv) }
        }
        val v = started && !screensaver
        if (state.value == v) return
        state.value = v
        listeners.toList().forEach { it(v) }
    }
}
