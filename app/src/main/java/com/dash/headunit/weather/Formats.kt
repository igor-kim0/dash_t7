package com.dash.headunit.weather

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Суббота, 10 октября" по ISO-дате, fallback — как есть. */
fun formatDayLabel(dateIso: String, locale: Locale = Locale("ru")): String {
    return try {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateIso)!!
        SimpleDateFormat("EEEE, d MMM", locale).format(date)
            .replaceFirstChar { it.uppercaseChar() }
    } catch (e: Exception) {
        dateIso
    }
}

/** Короткий день недели "Вт" по ISO-дате, fallback — как есть. */
fun formatDayShort(dateIso: String, locale: Locale = Locale("ru")): String {
    return try {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateIso)!!
        SimpleDateFormat("EE", locale).format(date)
            .replaceFirstChar { it.uppercaseChar() }
    } catch (e: Exception) {
        dateIso
    }
}

fun formatFetchedAt(millis: Long, locale: Locale = Locale("ru")): String =
    SimpleDateFormat("HH:mm", locale).format(Date(millis))
