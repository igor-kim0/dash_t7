package com.dash.headunit.elm

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Хранилище JSON-профилей ([ObdProfile]):
 *  - встроенные — assets/profiles/<файл>.json (id «asset:<файл>»);
 *  - загруженные пользователем — копия во внутренней памяти
 *    files/profiles/<файл>.json (id «file:<файл>»), их можно удалить.
 *
 * Выбранный профиль (id) хранится в prefs; без профиля [ElmHub]
 * опрашивает стандартный набор OBD-II.
 */
object ProfileStore {

    class Entry(val id: String, val name: String, val builtIn: Boolean, val error: String?)

    private const val ASSET_DIR = "profiles"
    private const val PREFS = "obd_profile"
    private const val KEY_SELECTED = "selected"
    /** Предел размера JSON-профиля (файл с флешки или из выбора файла). */
    const val MAX_BYTES = 2L * 1024 * 1024

    private fun dir(ctx: Context) = File(ctx.filesDir, "profiles").apply { mkdirs() }

    /** Все профили: сначала встроенные, затем загруженные (по имени). */
    fun list(ctx: Context): List<Entry> {
        val assets = ctx.assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".json", true) }.sorted()
            .map { entry(ctx, "asset:$it", builtIn = true) }
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".json", true) }.orEmpty()
            .sortedBy { it.name.lowercase() }
            .map { entry(ctx, "file:${it.name}", builtIn = false) }
        return assets + files
    }

    private fun entry(ctx: Context, id: String, builtIn: Boolean): Entry =
        try {
            Entry(id, load(ctx, id).name, builtIn, null)
        } catch (e: Exception) {
            Entry(id, id.substringAfter(':'), builtIn, e.message ?: e.javaClass.simpleName)
        }

    fun read(ctx: Context, id: String): String = when {
        id.startsWith("asset:") -> ctx.assets.open("$ASSET_DIR/${id.substringAfter(':')}").use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        id.startsWith("file:") -> File(dir(ctx), id.substringAfter(':')).readText()
        else -> throw IllegalArgumentException("неизвестный профиль $id")
    }

    fun load(ctx: Context, id: String): ObdProfile = ObdProfile.parse(read(ctx, id))

    /**
     * Проверить и сохранить JSON как загруженный профиль; файл с тем же
     * именем перезаписывается. Возвращает id. Ошибка разбора — исключение
     * с понятным текстом, ничего не сохраняется.
     */
    fun import(ctx: Context, fileName: String, text: String): String {
        ObdProfile.parse(text)
        val base = fileName.substringAfterLast('/').substringBeforeLast('.')
            .replace(Regex("""[^\p{L}\p{N} ._()-]"""), "_").trim().ifEmpty { "profile" }
        val file = File(dir(ctx), "$base.json")
        file.writeText(text)
        return "file:${file.name}"
    }

    fun importFile(ctx: Context, file: File): String {
        if (file.length() > MAX_BYTES) throw IllegalArgumentException("файл больше 2 МБ")
        return import(ctx, file.name, file.readText())
    }

    fun delete(ctx: Context, id: String) {
        if (!id.startsWith("file:")) return
        File(dir(ctx), id.substringAfter(':')).delete()
        if (selectedId(ctx) == id) select(ctx, null)
    }

    fun selectedId(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SELECTED, null)

    fun select(ctx: Context, id: String?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SELECTED, id).apply()
    }

    /**
     * JSON-файлы на флешках и во внутренней памяти: корни /storage/…,
     * Download, Documents и папки «obd» / «profiles» (на ГУ файловых
     * менеджеров часто нет — так профиль можно принести на флешке).
     */
    @Suppress("DEPRECATION")
    fun scanStorage(): List<File> {
        val roots = LinkedHashSet<File>()
        roots += Environment.getExternalStorageDirectory()
        File("/storage").listFiles()?.filter { it.isDirectory && it.canRead() && it.name != "self" }?.let(roots::addAll)
        File("/mnt/media_rw").listFiles()?.filter { it.canRead() }?.let(roots::addAll)
        val dirs = roots.flatMap { r ->
            listOf(r, File(r, "Download"), File(r, "Documents"), File(r, "obd"), File(r, "OBD"), File(r, "profiles"),
                File(r, "obd code"))
        }
        return dirs.flatMap { d ->
            d.listFiles { f -> f.isFile && f.name.endsWith(".json", true) && f.length() in 2..MAX_BYTES }.orEmpty().toList()
        }.distinctBy { it.canonicalPath }.sortedBy { it.path }
    }
}
