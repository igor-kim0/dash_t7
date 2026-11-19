package com.dash.headunit.dashboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Загрузка/сохранение раскладки виджетов. Дашбордов (страниц) несколько:
 * список id — [KEY_PAGES], текущая — [KEY_CURRENT]. Раскладка страницы 0 —
 * прежний ключ [KEY_LAYOUT] (как было до страниц), остальных — `layout_page_<id>`.
 * Хранится в SharedPreferences в виде JSON. [load]/[save]/[clearAll]/[reset]
 * без страницы — текущая страница.
 */
class DashboardStore(context: Context) {
    private val prefs =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Загрузка раскладки. Дефолт — только при первом запуске (ключа нет вообще).
     * Пустой список — осознанное состояние "дашборд очищен", его не перезаписываем.
     */
    fun load(): List<WidgetSpec> = load(currentPage())

    /** Раскладка страницы [page]; у страницы 0 без ключа — дефолт (первый запуск). */
    fun load(page: Int): List<WidgetSpec> {
        val raw = prefs.getString(layoutKey(page), null)
            ?: return if (page == FIRST_PAGE) defaultLayout() else emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it) }.filter {
                it.getString("type") !in WidgetType.REMOVED_IDS
            }.map { o ->
                WidgetSpec(
                    widgetId = o.getString("id"),
                    type = WidgetType.byId(o.getString("type")),
                    x = o.getInt("x"),
                    y = o.getInt("y"),
                    w = o.getInt("w"),
                    h = o.getInt("h")
                ).clamped(minW = 1, minH = 1)
            }
        } catch (e: Exception) {
            if (page == FIRST_PAGE) defaultLayout() else emptyList()
        }
    }

    fun save(specs: List<WidgetSpec>) = save(currentPage(), specs)

    fun save(page: Int, specs: List<WidgetSpec>) {
        val arr = JSONArray()
        specs.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.widgetId)
                    .put("type", it.type.id)
                    .put("x", it.x)
                    .put("y", it.y)
                    .put("w", it.w)
                    .put("h", it.h)
            )
        }
        prefs.edit().putString(layoutKey(page), arr.toString()).apply()
    }

    /** Сбросить текущую страницу: страница 0 — к дефолту, остальные — пустые. */
    fun reset() {
        prefs.edit().remove(layoutKey(currentPage())).apply()
    }

    /** Id страниц по порядку (минимум одна). */
    fun pages(): List<Int> =
        parsePages(prefs.getString(KEY_PAGES, null))

    /** Текущая страница (если сохранённой нет в списке — первая). */
    fun currentPage(): Int {
        val pages = pages()
        val cur = prefs.getInt(KEY_CURRENT, pages.first())
        return if (cur in pages) cur else pages.first()
    }

    fun setCurrentPage(page: Int) {
        prefs.edit().putInt(KEY_CURRENT, page).apply()
    }

    /** Новая пустая страница в конце; null — уже [MAX_PAGES]. */
    fun addPage(): Int? {
        val pages = pages()
        if (pages.size >= MAX_PAGES) return null
        val id = (pages.maxOrNull() ?: FIRST_PAGE) + 1
        prefs.edit()
            .putString(KEY_PAGES, (pages + id).joinToString(","))
            .putString(layoutKey(id), "[]")
            .apply()
        return id
    }

    /**
     * Удалить страницу [page] (последнюю не удаляем). Возвращает страницу,
     * на которую перейти, или null, если удалять нельзя.
     */
    fun removePage(page: Int): Int? {
        val pages = pages()
        val next = pageAfterRemove(pages, page) ?: return null
        prefs.edit()
            .putString(KEY_PAGES, pages.filter { it != page }.joinToString(","))
            .remove(layoutKey(page))
            .putInt(KEY_CURRENT, next)
            .apply()
        return next
    }

    /** Виджеты всех страниц — для уникальных id и общих настроек (заставка). */
    fun allSpecs(): List<WidgetSpec> = pages().flatMap { load(it) }

    /** Очистить дашборд: сохранить пустой список (не дефолт!). */
    fun clearAll() {
        save(emptyList())
    }

    /** Раскладка по умолчанию для альбомного экрана ГУ. */
    fun defaultLayout(): List<WidgetSpec> = listOf(
        WidgetSpec("combo1", WidgetType.COMBO, x = 4, y = 6, w = 60, h = 24),
        WidgetSpec("forecast1", WidgetType.WEATHER, x = 4, y = 32, w = 92, h = 34)
    )

    private fun layoutKey(page: Int): String =
        if (page == FIRST_PAGE) KEY_LAYOUT else "layout_page_$page"

    companion object {
        private const val PREFS = "dashboard_prefs"
        private const val KEY_LAYOUT = "layout_v1"
        private const val KEY_PAGES = "pages"
        private const val KEY_CURRENT = "current_page"
        private const val FIRST_PAGE = 0
        /** Сколько дашбордов можно завести. */
        const val MAX_PAGES = 5

        /** Разбор списка страниц; пусто/битое — одна страница 0. Тестируется. */
        internal fun parsePages(raw: String?): List<Int> =
            raw.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.distinct()
                .ifEmpty { listOf(FIRST_PAGE) }

        /**
         * Куда перейти после удаления [page]: на следующую, если она есть,
         * иначе на предыдущую; null — удалять нельзя (одна или нет такой).
         * Тестируется.
         */
        internal fun pageAfterRemove(pages: List<Int>, page: Int): Int? {
            val i = pages.indexOf(page)
            if (i < 0 || pages.size <= 1) return null
            return pages.getOrNull(i + 1) ?: pages[i - 1]
        }

        /**
         * Новый id виджета [type]: `<тип>_<n>`, n — больше всех занятых этого
         * типа на всех страницах (иначе на второй странице снова `heat_1`,
         * и настройки виджетов стали бы общими). Тестируется.
         */
        fun nextWidgetId(type: WidgetType, all: List<WidgetSpec>): String {
            val prefix = "${type.id}_"
            val used = all.mapNotNull { s ->
                s.widgetId.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull()
            }
            val n = (used.maxOrNull() ?: 0) + 1
            return "$prefix$n"
        }
    }
}
