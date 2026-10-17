package com.dash.headunit.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Геолокация без Google Play Services (на штатных ГУ их часто нет) —
 * напрямую через [LocationManager].
 *
 * Стратегия: сначала самая свежая lastKnown-локация, иначе разовый запрос
 * с таймаутом. Если ничего нет — вернётся null и погода использует fallback.
 */
class LocationHelper(context: Context) {

    private val manager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())

    data class Fix(val latitude: Double, val longitude: Double, val isFallback: Boolean)

    @SuppressLint("MissingPermission")
    fun requestFix(callback: (Fix?) -> Unit) {
        // 1. Пробуем lastKnown от всех провайдеров.
        var best: Location? = null
        for (provider in manager.getProviders(true)) {
            try {
                val loc = manager.getLastKnownLocation(provider) ?: continue
                if (best == null || (loc.time > best.time)) best = loc
            } catch (e: SecurityException) {
                callback(null)
                return
            } catch (e: IllegalArgumentException) {
                // Нет такого провайдера — пропускаем.
            }
        }
        if (best != null && System.currentTimeMillis() - best.time < STALE_MS) {
            callback(Fix(best.latitude, best.longitude, isFallback = false))
            return
        }

        // 2. Разовый запрос с таймаутом.
        var done = false
        lateinit var listener: LocationListener
        lateinit var timeout: Runnable
        fun finish(fix: Fix?) {
            if (done) return
            done = true
            try {
                manager.removeUpdates(listener)
            } catch (e: Exception) { /* ignore */ }
            handler.removeCallbacks(timeout)
            callback(fix)
        }

        val listenerImpl = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                finish(Fix(location.latitude, location.longitude, isFallback = false))
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        listener = listenerImpl
        timeout = Runnable {
            // Таймаут: отдаём хоть устаревшую, иначе null.
            val stale = best
            finish(stale?.let { Fix(it.latitude, it.longitude, isFallback = false) })
        }

        try {
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())
            } else if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
            } else if (best != null) {
                callback(Fix(best.latitude, best.longitude, isFallback = false))
                return
            } else {
                callback(null)
                return
            }
        } catch (e: SecurityException) {
            callback(null)
            return
        }
        handler.postDelayed(timeout, TIMEOUT_MS)
    }

    companion object {
        private const val TIMEOUT_MS = 15_000L
        private const val STALE_MS = 30 * 60 * 1000L
        /** Fallback-координаты (Москва), если геолокация недоступна. */
        const val FALLBACK_LAT = 55.7558
        const val FALLBACK_LON = 37.6173
    }
}
