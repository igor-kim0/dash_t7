package com.dash.headunit.navi.cluster

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView

/**
 * Разбор уведомления фонового ведения Яндекс Навигатора (14.x).
 *
 * Уведомление — custom RemoteViews (layout navi_layout_bg_guidance_notification*):
 *  - maneuver_block: nextManeuverView / nextManeuverViewTinted (стрелка),
 *    titleView (расстояние до манёвра), descriptionView (улица);
 *  - eta_block: remainingDistanceView, remainingTimeView, timeOfArrivalView.
 * RemoteViews раздуваем у себя (apply сам берёт ресурсы навигатора) и
 * ищем view по именам id из ресурсов навигатора — так переживаем смену
 * числовых id между версиями. Если имён нет — эвристика по текстам.
 */
object YandexNotificationParser {

    /** Результат: ведение (null — уведомление не про маршрут) и дамп для диагностики. */
    class Result(val guidance: Guidance?, val dump: String)

    fun parse(context: Context, sbn: StatusBarNotification): Result {
        val n = sbn.notification
        val dump = StringBuilder()
        dump.append("key=").append(sbn.key).append(" id=").append(sbn.id)
            .append(" ongoing=").append(sbn.isOngoing)
            .append(" channel=").append(n.channelId).append('\n')
        n.extras?.let { ex ->
            for (k in ex.keySet()) {
                val v = try { ex.get(k) } catch (e: Exception) { e.javaClass.simpleName }
                if (v is CharSequence || v is Number || v is Boolean) {
                    dump.append("  extra ").append(k).append('=').append(v).append('\n')
                }
            }
        }
        val ids = IdLookup(context, sbn.packageName)
        var result: Guidance? = null
        val views = listOf(
            "big" to n.bigContentView,
            "content" to n.contentView,
            "headsUp" to n.headsUpContentView,
        )
        for ((name, rv) in views) {
            if (rv == null) continue
            val root = inflate(context, rv, dump, name) ?: continue
            dumpTree(root, ids, dump, "  ")
            val g = fromTree(context, root, ids)
            if (g != null) result = result?.orElse(g) ?: g
        }
        if (result == null) {
            // Без custom-вида: только заголовок/текст стандартного уведомления.
            val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            if (GuidanceText.isDistance(title) || GuidanceText.classify(text) != null) {
                result = Guidance(
                    distance = title?.takeIf { GuidanceText.isDistance(it) },
                    street = text,
                    maneuver = GuidanceText.classify(text),
                )
            }
        }
        dump.append("  => ").append(result?.let(::describe) ?: "не ведение").append('\n')
        return Result(result, dump.toString())
    }

    private fun inflate(context: Context, rv: RemoteViews, dump: StringBuilder, name: String): View? =
        try {
            rv.apply(context, FrameLayout(context))
        } catch (e: Throwable) {
            Log.w(TAG, "apply $name", e)
            dump.append("  ").append(name).append(": apply ").append(e.javaClass.simpleName)
                .append(": ").append(e.message).append('\n')
            null
        }?.also {
            dump.append("  [").append(name).append(" layout=")
                .append(rv.layoutId.toString(16)).append("]\n")
        }

    private fun fromTree(context: Context, root: View, ids: IdLookup): Guidance? {
        fun text(name: String): String? =
            (ids.find(root, name) as? TextView)?.takeIf { it.visibility == View.VISIBLE }
                ?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        val icon = listOf("nextManeuverView", "nextManeuverViewTinted")
            .mapNotNull { ids.find(root, it) as? ImageView }
            .firstOrNull { it.visibility == View.VISIBLE && it.drawable != null }
        val iconBmp = icon?.let { render(it) }
        var g = Guidance(
            maneuverIcon = iconBmp,
            distance = text("titleView"),
            street = text("descriptionView"),
            remainingDistance = text("remainingDistanceView"),
            remainingTime = text("remainingTimeView"),
            arrival = text("timeOfArrivalView"),
        )
        val title = text("notificationTitle")
        g = g.copy(maneuver = GuidanceText.classify(icon?.contentDescription?.toString())
            ?: iconBmp?.let { ManeuverMatcher.fromIcon(context, it).maneuver }
            ?: GuidanceText.classify(title) ?: GuidanceText.classify(g.street))
        if (g.hasManeuver || g.remainingDistance != null) return g

        // Имена id не нашлись (другая версия навигатора) — эвристика:
        // крупнейшая картинка — стрелка, текст-расстояние — до манёвра.
        val texts = mutableListOf<String>()
        val images = mutableListOf<ImageView>()
        walk(root) { v ->
            if (v.visibility != View.VISIBLE) return@walk
            if (v is TextView) v.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
            if (v is ImageView && v.drawable != null) images.add(v)
        }
        val dist = texts.firstOrNull { GuidanceText.isDistance(it) } ?: return null
        val img = images.maxByOrNull { (it.drawable.intrinsicWidth * it.drawable.intrinsicHeight) }
        return Guidance(
            maneuverIcon = img?.let { render(it) },
            maneuver = texts.firstNotNullOfOrNull { GuidanceText.classify(it) },
            distance = dist,
            street = texts.firstOrNull { it != dist && !GuidanceText.isDistance(it) && !GuidanceText.isClock(it) },
            arrival = texts.firstOrNull { GuidanceText.isClock(it) },
        )
    }

    /** Картинку манёвра — в свой битмап (векторные рисуем крупно). */
    private fun render(view: ImageView): Bitmap? = try {
        val d = view.drawable.constantState?.newDrawable()?.mutate() ?: view.drawable
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: ICON_PX
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: ICON_PX
        val scale = ICON_PX.toFloat() / maxOf(iw, ih)
        val w = (iw * scale).toInt().coerceAtLeast(1)
        val h = (ih * scale).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            d.setBounds(0, 0, w, h)
            d.draw(Canvas(it))
        }
    } catch (e: Throwable) {
        Log.w(TAG, "render", e)
        null
    }

    private fun dumpTree(v: View, ids: IdLookup, out: StringBuilder, indent: String) {
        out.append(indent).append(v.javaClass.simpleName)
        ids.nameOf(v.id)?.let { out.append(" #").append(it) }
        if (v.visibility != View.VISIBLE) out.append(" (скрыт)")
        if (v is TextView) out.append(" \"").append(v.text).append('"')
        if (v is ImageView) {
            val d = v.drawable
            out.append(" img=").append(d?.javaClass?.simpleName)
            if (d != null) out.append(' ').append(d.intrinsicWidth).append('x').append(d.intrinsicHeight)
        }
        v.contentDescription?.let { out.append(" desc=\"").append(it).append('"') }
        out.append('\n')
        if (v is ViewGroup) for (i in 0 until v.childCount) dumpTree(v.getChildAt(i), ids, out, "$indent  ")
    }

    private fun walk(v: View, f: (View) -> Unit) {
        f(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), f)
    }

    fun describe(g: Guidance): String =
        "манёвр=${g.maneuver} стрелка=${g.maneuverIcon?.let { "${it.width}x${it.height}" }} " +
            "до=${g.distance} улица=${g.street} осталось=${g.remainingDistance}/${g.remainingTime} " +
            "прибытие=${g.arrival} огр=${g.speedLimit} камера=${g.cameraDistance}/${g.cameraLimit}" +
            if (g.overspeed) " ПРЕВЫШЕНИЕ" else ""

    /** id view навигатора по имени ресурса и обратно. */
    private class IdLookup(context: Context, private val pkg: String) {
        private val res = try {
            context.packageManager.getResourcesForApplication(pkg)
        } catch (e: Exception) {
            null
        }
        private val cache = HashMap<String, Int>()

        fun find(root: View, name: String): View? {
            val id = cache.getOrPut(name) { res?.getIdentifier(name, "id", pkg) ?: 0 }
            return if (id != 0) root.findViewById(id) else null
        }

        fun nameOf(id: Int): String? =
            if (id == View.NO_ID || res == null) null
            else try { res.getResourceEntryName(id) } catch (e: Exception) { null }
    }

    private const val ICON_PX = 256
    private const val TAG = "NaviNotif"
}
