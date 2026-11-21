package com.dash.headunit.location

/**
 * Разбор NMEA 0183 — то, что нужно для координат: RMC (позиция, скорость,
 * курс, время) и GGA (спутники, HDOP, высота). Талкер любой ($GP, $GN, $GL,
 * $BD, $GA…). Строка с неверной контрольной суммой отбрасывается.
 * Чистый код — тестируется без Android.
 */
object Nmea {

    /** RMC: [valid] = статус «A». Скорость — м/с, курс — градусы (null, если пусто). */
    data class Rmc(
        val valid: Boolean,
        val lat: Double,
        val lon: Double,
        val speedMps: Float?,
        val bearing: Float?,
        /** Время UTC фикса, мс от начала суток (null — нет). */
        val utcDayMs: Long?,
    )

    data class Gga(val quality: Int, val satellites: Int?, val hdop: Float?, val altitude: Double?)

    /** Строка → [Rmc] / [Gga] / null (другое предложение, мусор, неверная сумма). */
    fun parse(line: String): Any? {
        val s = line.trim()
        if (s.length < 7 || s[0] != '$') return null
        val star = s.lastIndexOf('*')
        val body = if (star > 0) {
            if (star + 3 > s.length) return null
            val want = s.substring(star + 1, star + 3).toIntOrNull(16) ?: return null
            var sum = 0
            for (i in 1 until star) sum = sum xor s[i].code
            if (sum != want) return null
            s.substring(1, star)
        } else {
            s.substring(1)
        }
        val f = body.split(',')
        val type = f[0].takeLast(3)
        return when (type) {
            "RMC" -> rmc(f)
            "GGA" -> gga(f)
            else -> null
        }
    }

    private fun rmc(f: List<String>): Rmc? {
        if (f.size < 9) return null
        val lat = coord(f[3], f[4]) ?: return null
        val lon = coord(f[5], f[6]) ?: return null
        return Rmc(
            valid = f[2] == "A",
            lat = lat,
            lon = lon,
            speedMps = f[7].toFloatOrNull()?.let { it * KNOT_MPS },
            bearing = f[8].toFloatOrNull(),
            utcDayMs = utc(f[1]),
        )
    }

    private fun gga(f: List<String>): Gga? {
        if (f.size < 10) return null
        return Gga(
            quality = f[6].toIntOrNull() ?: 0,
            satellites = f[7].toIntOrNull(),
            hdop = f[8].toFloatOrNull(),
            altitude = f[9].toDoubleOrNull(),
        )
    }

    /** «ddmm.mmmm» / «dddmm.mmmm» + полушарие → градусы. */
    internal fun coord(v: String, hemi: String): Double? {
        val dot = v.indexOf('.').let { if (it < 0) v.length else it }
        if (dot < 3) return null
        val deg = v.substring(0, dot - 2).toIntOrNull() ?: return null
        val min = v.substring(dot - 2).toDoubleOrNull() ?: return null
        val d = deg + min / 60.0
        return when (hemi) {
            "N", "E" -> d
            "S", "W" -> -d
            else -> null
        }
    }

    private fun utc(v: String): Long? {
        if (v.length < 6) return null
        val h = v.substring(0, 2).toIntOrNull() ?: return null
        val m = v.substring(2, 4).toIntOrNull() ?: return null
        val sec = v.substring(4).toDoubleOrNull() ?: return null
        return (h * 3600_000L + m * 60_000L + (sec * 1000).toLong())
    }

    private const val KNOT_MPS = 0.514444f
}
