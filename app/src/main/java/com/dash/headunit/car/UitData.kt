package com.dash.headunit.car

/**
 * Запись ключей модели данных Kanzi приборки (uit.svc.data) штатной утилитой
 * Desay `uit.test.data-controller` в sh приборки через qconn ([QnxLauncher]).
 *
 * Каждая команда — отдельный короткий sh ([QnxLauncher.sh]): так работает
 * проба diag/ClusterGaugesTest (ГУ 2026-11-21/24). Долгая sh-сессия с
 * командами через stdin сокета на ГУ не сработала ни разу.
 *
 * Утилита может не завершаться сама — запускаем в фоне и снимаем через
 * [RUN_S] с. Enum утилита разбирает через stoi — только номер.
 */
object UitData {

    const val UIT = "/emmc/uit"

    /** Штатные скорость, обороты и угловые дуги (ГУ 2026-11-21: работает). */
    val HIDE_GAUGES = listOf("--setint=System.InitialStartUpInt#0")
    val SHOW_GAUGES = listOf("--setint=System.InitialStartUpInt#1")

    /** Весь штатный слой Root/IGNON, лампы остаются (ГУ 2026-11-24: работает). */
    val HIDE_ALL = listOf("--setenum=System.IGNState#0")
    val SHOW_ALL = listOf("--setenum=System.IGNState#2")

    /** Сколько секунд даём утилите на одну запись. */
    const val RUN_S = 4

    /** Начало скрипта: окружение утилиты (библиотеки uit, рабочая папка). */
    val ENV = """
        export LD_LIBRARY_PATH=$UIT/lib:${'$'}LD_LIBRARY_PATH
        cd $UIT/etc
    """.trimIndent()

    /**
     * Фрагмент sh: записать [args] по очереди. [quiet] — без вывода утилиты
     * (для фоновых скриптов), иначе её вывод и пометка «снята по таймауту».
     */
    fun runs(args: List<String>, quiet: Boolean = false): String = args.joinToString("\n") { arg ->
        val out = if (quiet) ">/dev/null 2>&1" else "2>&1"
        val note = if (quiet) "" else " && echo '(снята по таймауту)'"
        "$UIT/bin/uit.test.data-controller '$arg' </dev/null $out &\n" +
            "P=${'$'}!; sleep $RUN_S; kill ${'$'}P 2>/dev/null$note"
    }

    /** Таймаут [QnxLauncher.sh] для скрипта из [writes] записей. */
    fun timeoutMs(writes: Int): Int = (writes * RUN_S + 6) * 1000

    /** Одна запись [arg] с выводом утилиты (проба); null — нет sh на приборке. */
    fun run(arg: String): String? =
        QnxLauncher.sh("$ENV\n${runs(listOf(arg))}\necho '(конец)'", timeoutMs(1))
}
