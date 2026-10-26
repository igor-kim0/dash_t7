package com.dash.headunit.navi.cluster

import android.graphics.Bitmap

/** Пакет Яндекс Навигатора — источник маршрута для приборки. */
const val YANDEX_NAVI = "ru.yandex.yandexnavi"

/** Тип манёвра — для своей стрелки, если картинки из навигатора нет. */
enum class Maneuver {
    STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT,
    UTURN_LEFT, UTURN_RIGHT, KEEP_LEFT, KEEP_RIGHT, ROUNDABOUT, FINISH
}

/**
 * Состояние ведения по маршруту. Все поля необязательные: уведомление
 * навигатора и служба спец. возможностей дают разные части, [GuidanceHub]
 * их склеивает.
 */
data class Guidance(
    /** Картинка манёвра из уведомления навигатора (форма — по альфе). */
    val maneuverIcon: Bitmap? = null,
    /** Тип манёвра, распознанный по тексту (запасная стрелка). */
    val maneuver: Maneuver? = null,
    /** Расстояние до манёвра как его пишет навигатор: «300 м», «1,2 км». */
    val distance: String? = null,
    /** Улица / направление после манёвра. */
    val street: String? = null,
    val remainingDistance: String? = null,
    val remainingTime: String? = null,
    /** Время прибытия «14:35». */
    val arrival: String? = null,
    /** Ограничение скорости, км/ч. */
    val speedLimit: Int? = null,
    /** Камера впереди: расстояние до неё. */
    val cameraDistance: String? = null,
    /** Ограничение на камере, км/ч. */
    val cameraLimit: Int? = null,
    /** Навигатор показывает превышение (#view_speedlimit_alarm виден). */
    val overspeed: Boolean = false,
) {
    /** Есть ли что показать про манёвр. */
    val hasManeuver: Boolean
        get() = maneuverIcon != null || maneuver != null || !distance.isNullOrBlank()

    /**
     * Идёт ведение по маршруту: есть манёвр или остаток пути. Манёвр бывает
     * не виден (Яндекс рисует его в карте), а остаток — виден всегда.
     */
    val onRoute: Boolean
        get() = hasManeuver || remainingDistance != null || remainingTime != null

    /** Поля [this], а пустые — из [other]. */
    fun orElse(other: Guidance?): Guidance {
        if (other == null) return this
        return Guidance(
            maneuverIcon = maneuverIcon ?: other.maneuverIcon,
            maneuver = maneuver ?: other.maneuver,
            distance = distance ?: other.distance,
            street = street ?: other.street,
            remainingDistance = remainingDistance ?: other.remainingDistance,
            remainingTime = remainingTime ?: other.remainingTime,
            arrival = arrival ?: other.arrival,
            speedLimit = speedLimit ?: other.speedLimit,
            cameraDistance = cameraDistance ?: other.cameraDistance,
            cameraLimit = cameraLimit ?: other.cameraLimit,
            overspeed = overspeed || other.overspeed,
        )
    }
}

/** Разбор текстов навигатора (чистые функции — покрыты тестами). */
object GuidanceText {

    /**
     * Тип манёвра по фразе навигатора («Поверните направо», «Держитесь
     * левее», «Развернитесь», contentDescription стрелки и т.п.).
     * null — фраза не про манёвр.
     */
    fun classify(text: String?): Maneuver? {
        val t = text?.lowercase()?.replace('ё', 'е') ?: return null
        fun has(vararg w: String) = w.any { t.contains(it) }
        val left = has("налево", "левее", "влево", "left")
        val right = has("направо", "правее", "вправо", "right")
        return when {
            has("приехали", "финиш", "конечн", "пункт назначения", "arrive", "finish") -> Maneuver.FINISH
            has("кругов", "кольц", "roundabout") -> Maneuver.ROUNDABOUT
            has("разворот", "развернит", "u-turn", "uturn") ->
                if (right) Maneuver.UTURN_RIGHT else Maneuver.UTURN_LEFT
            has("держитесь", "держись", "keep") && left -> Maneuver.KEEP_LEFT
            has("держитесь", "держись", "keep") && right -> Maneuver.KEEP_RIGHT
            has("резко", "sharp") && left -> Maneuver.SHARP_LEFT
            has("резко", "sharp") && right -> Maneuver.SHARP_RIGHT
            has("плавно", "slight", "левее", "правее") && left -> Maneuver.SLIGHT_LEFT
            has("плавно", "slight", "левее", "правее") && right -> Maneuver.SLIGHT_RIGHT
            left -> Maneuver.LEFT
            right -> Maneuver.RIGHT
            has("прямо", "straight") -> Maneuver.STRAIGHT
            else -> null
        }
    }

    /** Похоже ли на расстояние: «300 м», «1,2 км», «800 m». */
    fun isDistance(text: String?): Boolean =
        text != null && DISTANCE.matches(text.trim().replace(' ', ' '))

    /** Похоже ли на время прибытия «14:35». */
    fun isClock(text: String?): Boolean =
        text != null && CLOCK.matches(text.trim())

    /**
     * Ограничение скорости из текста знака: «60», «60 км/ч», «Ограничение 60».
     * Допустимы только реальные значения знаков (5..150, кратные 5).
     */
    fun speedLimit(text: String?): Int? {
        val n = text?.let { NUMBER.find(it)?.value?.toIntOrNull() } ?: return null
        return n.takeIf { it in 5..150 && it % 5 == 0 }
    }

    /**
     * Запрос приборки «показать/убрать навигацию» из extras широковещания
     * desay.broadcast.map.meter.interaction (как разбирает Jarvis):
     * ключи RequestNaviAreaDisplay / map_area / map_status, либо JSON-строка
     * с "RequestNaviAreaDisplay". 1 — показать, 0 — убрать, null — не про это.
     */
    fun naviAreaRequest(extras: Map<String, Any?>): Int? {
        for (key in AREA_KEYS) {
            when (val v = extras[key]) {
                is Int -> return if (v != 0) 1 else 0
                is Boolean -> return if (v) 1 else 0
                is String -> when (v.trim().lowercase()) {
                    "1", "true" -> return 1
                    "0", "false" -> return 0
                }
            }
        }
        val all = buildString {
            append(extras.toString())
            extras.values.filterIsInstance<String>().forEach { append(' ').append(it) }
        }.replace(" ", "")
        return when {
            AREA_ON.any { all.contains(it) } -> 1
            AREA_OFF.any { all.contains(it) } -> 0
            else -> null
        }
    }

    /** Расстояние навигатора в метрах: «300 м», «1,2 км», «800 m». */
    fun meters(text: String?): Int? {
        val m = METERS.find(text?.replace('\u00A0', ' ') ?: return null) ?: return null
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].lowercase()
        return Math.round(if (unit == "км" || unit == "km") n * 1000 else n).toInt()
    }

    /** Время навигатора в секундах: «18 мин», «1 ч 5 мин», «2 min», «1 h». */
    fun seconds(text: String?): Int? {
        val t = text?.lowercase() ?: return null
        val h = HOURS.find(t)?.groupValues?.get(1)?.toIntOrNull()
        val min = MINUTES.find(t)?.groupValues?.get(1)?.toIntOrNull()
        if (h == null && min == null) return null
        return ((h ?: 0) * 60 + (min ?: 0)) * 60
    }

    private val METERS = Regex("""(\d+(?:[.,]\d+)?)\s*(км|km|м|m)(?![a-zа-я])""", RegexOption.IGNORE_CASE)
    private val HOURS = Regex("""(\d+)\s*(ч|h)""")
    private val MINUTES = Regex("""(\d+)\s*(мин|min)""")
    private val DISTANCE = Regex("""^\d+([.,]\d+)?\s*(м|км|m|km|ft|mi)\.?$""", RegexOption.IGNORE_CASE)
    private val CLOCK = Regex("""^\d{1,2}:\d{2}$""")
    private val NUMBER = Regex("""\d{1,3}""")
    private val AREA_KEYS = listOf("RequestNaviAreaDisplay", "map_area", "map_status")
    private val AREA_ON = listOf("RequestNaviAreaDisplay=1", "\"RequestNaviAreaDisplay\":1", "\"RequestNaviAreaDisplay\":\"1\"")
    private val AREA_OFF = listOf("RequestNaviAreaDisplay=0", "\"RequestNaviAreaDisplay\":0", "\"RequestNaviAreaDisplay\":\"0\"")
}
