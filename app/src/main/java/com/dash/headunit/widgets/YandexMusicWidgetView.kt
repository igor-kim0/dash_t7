package com.dash.headunit.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dash.headunit.R
import com.dash.headunit.dashboard.WidgetSettingsStore
import com.dash.headunit.music.MusicHub
import com.dash.headunit.music.SpectrumHub
import com.dash.headunit.music.YandexMusicHub
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.ThemeCard
import com.dash.headunit.ui.DashDialog

/**
 * Виджет «Яндекс Музыка»: обложка трека, транспорт и выбор коллекции.
 *
 * Работает с сессией приложения Яндекс Музыки на самом ГУ — берёт её из
 * [YandexMusicHub]. Обложка приходит с сессии (bitmap или отдельно
 * скачивается по URI). Коллекции — дерево MediaBrowser («Моя волна»,
 * плейлисты…): папки раскрываются «в глубину» ([YandexMusicHub.navigate]),
 * тап по листовому узлу запускает коллекцию в плеере.
 *
 * Требуется доступ к уведомлениям, как у виджета «Музыка»
 * ([com.dash.headunit.music.MediaListenerService]).
 */
class YandexMusicWidgetView(
    context: Context,
    private val theme: DashTheme = DashTheme.DARK,
    private val widgetId: String = "",
    settingsStore: WidgetSettingsStore? = null
) : LinearLayout(context) {

    /** Блок спектра (опция виджета); null — выключен. */
    private val spectrum: SpectrumView? =
        if (settingsStore != null && SpectrumView.isOn(settingsStore, widgetId)) {
            SpectrumView(context, theme, SpectrumView.style(settingsStore, widgetId))
        } else {
            null
        }
    private val spectrumKey = "ymusic:$widgetId"

    private val artView = ImageView(context)
    private val titleView = TextView(context)
    private val artistView = TextView(context)
    private val progressFill = View(context)
    private val progressEmpty = View(context)
    private val timeElapsedView = TextView(context)
    private val timeTotalView = TextView(context)
    private val btnPrev = ImageButton(context)
    private val btnPlay = ImageButton(context)
    private val btnNext = ImageButton(context)
    private val accessBtn = Button(context)
    private val chipsTitle = TextView(context)
    private val chipsHost = LinearLayout(context)
    private val chipsScroll: HorizontalScrollView

    /** Обложка, нарисованная последний раз, — чтобы не перезаливать её каждый рендер. */
    private var shownArt: Bitmap? = null

    /** Строка трека (обложка, тексты, транспорт) — тускнеет без воспроизведения. */
    private val main = LinearLayout(context)

    /** Что нарисовано в ленте — пересобираем её только при изменении (не сбивая прокрутку). */
    private var shownChips: Any? = null

    private val hubListener: () -> Unit = { render() }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL

        // Тап по свободной области: без доступа — настройки уведомлений, иначе обновить.
        setOnClickListener { onBodyTap() }

        // Обложка со скруглением; без картинки скрыта — заглушка-квадрат не нужна.
        artView.apply {
            visibility = GONE
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                }
            }
        }

        // Тексты: название / исполнитель, тонкий прогресс, время.
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView.apply {
            setTextColor(theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            isSingleLine = true
        }
        artistView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            isSingleLine = true
        }
        texts.addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        texts.addView(artistView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val progress = LinearLayout(context).apply { orientation = HORIZONTAL }
        progressFill.setBackgroundColor(theme.accent)
        progressEmpty.setBackgroundColor(theme.cardBorder)
        progress.addView(progressFill, LayoutParams(0, dp(3), 0f))
        progress.addView(progressEmpty, LayoutParams(0, dp(3), 1f))
        timeElapsedView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        timeTotalView.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        texts.addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(5)
        })
        val times = LinearLayout(context).apply { orientation = HORIZONTAL }
        times.addView(timeElapsedView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        times.addView(View(context), LayoutParams(0, 0, 1f))
        times.addView(timeTotalView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        texts.addView(times, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        accessBtn.apply {
            text = context.getString(R.string.music_enable_access)
            textSize = 14f
            setTextColor(theme.background)
            background = ThemeCard.accent(context, theme)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener { openListenerSettings() }
            visibility = GONE
        }
        texts.addView(accessBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })

        // Транспорт: назад / play-pause / вперёд (акцентная, крупнее).
        val controls = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        styleTransportButton(btnPrev, R.drawable.ic_prev, ThemeCard.square(theme.cardBorder, 12, context), theme.secondaryText, 48)
        styleTransportButton(btnPlay, R.drawable.ic_play, ThemeCard.square(theme.cardBorder, 12, context), theme.primaryText, 52)
        styleTransportButton(btnNext, R.drawable.ic_next, ThemeCard.square(theme.accent, 12, context), theme.background, 60)
        btnPrev.setOnClickListener { YandexMusicHub.prev() }
        btnPlay.setOnClickListener { YandexMusicHub.toggle() }
        btnNext.setOnClickListener { YandexMusicHub.next() }
        controls.addView(btnPrev)
        controls.addView(btnPlay)
        controls.addView(btnNext)

        val artSize = dp(84)
        main.orientation = HORIZONTAL
        main.gravity = Gravity.CENTER_VERTICAL
        main.addView(artView, LayoutParams(artSize, artSize).apply { marginEnd = dp(12) })
        main.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
        main.addView(controls, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // Коллекции: подпись и горизонтальная лента чипов под основной строкой.
        chipsTitle.apply {
            setTextColor(theme.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            visibility = GONE
        }
        chipsHost.orientation = HORIZONTAL
        chipsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = GONE
            addView(
                chipsHost,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            )
        }

        addView(main, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        spectrum?.let {
            it.minimumHeight = dp(32)
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        }
        addView(
            chipsTitle,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        )
        addView(
            chipsScroll,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(CHIP_H + 4)).apply {
                topMargin = dp(4)
            }
        )

        render()
    }

    /**
     * Квадратная кнопка транспорта: фон-квадрат с мягким радиусом,
     * иконка по центру. Как в виджете «Музыка», чуть мельче.
     */
    private fun styleTransportButton(
        btn: ImageButton,
        iconRes: Int,
        bg: android.graphics.drawable.Drawable,
        tint: Int,
        sizeDp: Int
    ) {
        val s = dp(sizeDp)
        btn.apply {
            setImageResource(iconRes)
            background = bg
            setColorFilter(tint)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LayoutParams(s, s).apply {
                marginEnd = dp(10)
            }
        }
    }

    /**
     * Чип коллекции/папки: скруглённый брусок с иконкой (если есть) и
     * подписью; у папки — «›» справа. Тап — раскрыть или запустить.
     */
    private fun makeChip(
        label: String,
        accent: Boolean,
        icon: Bitmap? = null,
        folder: Boolean = false,
        onClick: () -> Unit
    ): View {
        val chip = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ThemeCard.square(if (accent) theme.accent else theme.cardBorder, 10, context)
            setPadding(if (icon != null) dp(4) else dp(14), 0, dp(14), 0)
            isClickable = true
            setOnClickListener { onClick() }
        }
        if (icon != null) {
            val iv = ImageView(context).apply {
                setImageBitmap(icon)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(7).toFloat())
                    }
                }
            }
            val s = dp(CHIP_H - 8)
            chip.addView(iv, LayoutParams(s, s).apply { marginEnd = dp(8) })
        }
        val tv = TextView(context).apply {
            text = if (folder) "$label  ›" else label
            setTextColor(if (accent) theme.background else theme.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isSingleLine = true
            maxWidth = dp(220)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        chip.addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        return chip
    }

    /**
     * Лента коллекций: «←» (если открыта папка) + дети уровня. Видна и без
     * воспроизведения — как раз тогда коллекцию и выбирают. Если браузер
     * не пускает — чип «Открыть Яндекс Музыку».
     */
    private fun renderCollections() {
        val hub = YandexMusicHub
        val level = hub.level
        val failed = hub.collections.isEmpty() && !hub.loading &&
            hub.browserState == YandexMusicHub.BrowserState.FAILED
        val key = listOf(hub.collections, level, hub.loading, failed, hub.installed)
        if (key == shownChips) return
        shownChips = key
        chipsHost.removeAllViews()
        if (!hub.installed) {
            chipsTitle.visibility = GONE
            chipsScroll.visibility = GONE
            return
        }
        val chipLp = { LayoutParams(LayoutParams.WRAP_CONTENT, dp(CHIP_H)).apply { marginEnd = dp(8) } }
        if (level > 0) {
            chipsHost.addView(
                makeChip("←", true) { hub.up() },
                LayoutParams(dp(44), dp(CHIP_H)).apply { marginEnd = dp(8) }
            )
        }
        hub.collections.forEach { node ->
            chipsHost.addView(
                makeChip(node.title, false, node.icon, node.browsable) { hub.navigate(node) },
                chipLp()
            )
        }
        if (failed) {
            chipsHost.addView(
                makeChip(context.getString(R.string.yam_open_app), true) { hub.openApp() },
                chipLp()
            )
        }
        val visible = hub.collections.isNotEmpty() || level > 0 || hub.loading || failed
        chipsTitle.visibility = if (visible) VISIBLE else GONE
        chipsScroll.visibility = if (visible) VISIBLE else GONE
        chipsTitle.text = when {
            hub.loading -> context.getString(R.string.yam_collections_loading)
            failed -> context.getString(R.string.yam_collections_unavailable)
            level == 0 -> context.getString(R.string.yam_collections_root)
            else -> hub.folderTitle ?: context.getString(R.string.yam_collections_deep)
        }
        if (level > 0 || hub.loading) chipsScroll.scrollTo(0, 0)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    /** Без доступа к уведомлениям и без сессии браузера управлять нечем. */
    private fun accessMissing(): Boolean =
        !YandexMusicHub.hasController && MusicHub.needsAccess(context)

    private fun onBodyTap() {
        if (YandexMusicHub.data == null && accessMissing()) {
            openListenerSettings()
        } else {
            YandexMusicHub.refresh()
        }
    }

    /** Как в виджете «Музыка»: самовключение доступа или диалог с adb-командой. */
    private fun openListenerSettings() {
        if (MusicHub.enableSelf(context) == true) {
            Toast.makeText(context, R.string.music_access_self_ok, Toast.LENGTH_SHORT).show()
            MusicHub.refresh()
            YandexMusicHub.refresh()
            render()
            return
        }
        val settingsIntent = android.content.Intent(
            android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
        )
        val canOpen = try {
            context.packageManager.resolveActivity(
                settingsIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            ) != null
        } catch (e: Exception) {
            false
        }
        val builder = DashDialog.Builder(context)
            .setTitle(R.string.music_access_title)
            .setMessage(R.string.music_access_text)
            .setPositiveButton(R.string.dialog_ok, null)
        if (canOpen) {
            builder.setNeutralButton(R.string.music_access_open_settings) { _, _ ->
                try {
                    context.startActivity(
                        settingsIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    // Экран есть в манифесте, но не открывается — тишина.
                }
            }
        }
        builder.showFullScreen()
    }

    // ---------- отрисовка ----------

    private fun render() {
        val d = YandexMusicHub.data
        if (spectrum != null) SpectrumHub.setPlaying(spectrumKey, d?.playing == true)
        if (d == null) {
            shownArt = null
            artView.setImageDrawable(null)
            artView.visibility = GONE
            val noAccess = accessMissing()
            titleView.text = when {
                !YandexMusicHub.installed -> context.getString(R.string.yam_not_installed)
                noAccess -> context.getString(R.string.music_no_access)
                else -> context.getString(R.string.yam_nothing)
            }
            artistView.text = ""
            setProgress(0f)
            timeElapsedView.text = ""
            timeTotalView.text = ""
            btnPlay.setImageResource(R.drawable.ic_play)
            accessBtn.visibility = if (noAccess && YandexMusicHub.installed) VISIBLE else GONE
            main.alpha = 0.6f
            renderCollections()
            return
        }
        accessBtn.visibility = GONE
        main.alpha = 1f
        // Обложка: bitmap из сессии (может догрузиться по URI фоном в hub'е).
        val art = d.art
        if (art != null) {
            if (shownArt !== art) {
                shownArt = art
                artView.setImageBitmap(art)
            }
            artView.visibility = VISIBLE
        } else {
            // Ещё не догрузилась по URI — ждём, не мигая заглушкой.
            shownArt = null
            artView.setImageDrawable(null)
            artView.visibility = GONE
        }
        titleView.text = d.title
        artistView.text = d.artist ?: d.album ?: context.getString(R.string.yam_default_artist)
        btnPlay.setImageResource(
            if (d.playing) R.drawable.ic_pause else R.drawable.ic_play
        )
        if (d.durationMs > 0 && d.positionMs >= 0) {
            setProgress((d.positionMs.toFloat() / d.durationMs).coerceIn(0f, 1f))
            timeElapsedView.text = formatTime(d.positionMs)
            timeTotalView.text = formatTime(d.durationMs)
        } else {
            setProgress(if (d.playing) 1f else 0f)
            timeElapsedView.text = ""
            timeTotalView.text = ""
        }
        renderCollections()
    }

    private fun formatTime(ms: Long): String {
        if (ms < 0) return context.getString(R.string.music_time_unknown)
        val totalSec = ms / 1000
        return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
    }

    private fun setProgress(fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        (progressFill.layoutParams as LayoutParams).weight = f
        (progressEmpty.layoutParams as LayoutParams).weight = 1f - f
        progressFill.requestLayout()
        progressEmpty.requestLayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MusicHub.init(context)
        YandexMusicHub.init(context)
        MusicHub.addListener(hubListener)
        MusicHub.ensurePolling()
        YandexMusicHub.addListener(hubListener)
        YandexMusicHub.ensurePolling()
    }

    override fun onDetachedFromWindow() {
        MusicHub.removeListener(hubListener)
        YandexMusicHub.removeListener(hubListener)
        SpectrumHub.clearPlaying(spectrumKey)
        super.onDetachedFromWindow()
    }

    private companion object {
        /** Высота чипа коллекции, dp. */
        const val CHIP_H = 40
    }
}
