package com.dash.headunit

import android.annotation.SuppressLint
import android.Manifest
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.Region
import android.net.wifi.WifiManager
import android.provider.OpenableColumns
import android.widget.ScrollView
import android.text.InputType
import android.widget.EditText
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dash.headunit.dashboard.DashboardGridLayout
import com.dash.headunit.dashboard.DashboardStore
import com.dash.headunit.dashboard.PickerGrid
import com.dash.headunit.dashboard.WidgetFrame
import com.dash.headunit.dashboard.WidgetSettingsStore
import com.dash.headunit.dashboard.WidgetSpec
import com.dash.headunit.dashboard.WidgetType
import com.dash.headunit.elm.ElmHub
import com.dash.headunit.elm.ProfileStore
import com.dash.headunit.car.CarHub
import com.dash.headunit.car.CoolantEtm
import com.dash.headunit.car.HvacShade
import com.dash.headunit.car.TripTracker
import com.dash.headunit.navi.HoleBackground
import com.dash.headunit.navi.NaviUnderlay
import com.dash.headunit.ui.DashDialog
import com.dash.headunit.ui.QrCode
import com.dash.headunit.ui.SettingsList
import com.dash.headunit.navi.cluster.ClusterOverlay
import com.dash.headunit.navi.cluster.ClusterSetup
import com.dash.headunit.navi.cluster.NaviOnCluster
import com.dash.headunit.navi.cluster.YANDEX_NAVI
import com.dash.headunit.settings.AppSettings
import com.dash.headunit.theme.DashTheme
import com.dash.headunit.theme.RetroLook
import com.dash.headunit.theme.ThemeCard
import com.dash.headunit.theme.ThemeStore
import com.dash.headunit.theme.WidgetBackground
import com.dash.headunit.widgets.IgnitionWidgetView
import com.dash.headunit.widgets.CarWidgetView
import com.dash.headunit.widgets.ClockWidgetView
import com.dash.headunit.widgets.ComboWidgetView
import com.dash.headunit.widgets.ForecastStripView
import com.dash.headunit.widgets.MusicWidgetView
import com.dash.headunit.widgets.RadioWidgetView
import com.dash.headunit.widgets.ModernRadioView
import com.dash.headunit.widgets.SpectrumView
import com.dash.headunit.music.SpectrumHub
import com.dash.headunit.widgets.YandexMusicWidgetView
import com.dash.headunit.widgets.WifiWidgetView
import com.dash.headunit.widgets.ReferenceWidgetView
import com.dash.headunit.widgets.AppWidgetView
import com.dash.headunit.widgets.AppsWidgetView
import com.dash.headunit.widgets.ButtonsWidgetView
import com.dash.headunit.widgets.NavApps
import com.dash.headunit.widgets.TripWidgetView
import com.dash.headunit.widgets.ClimateControlView
import com.dash.headunit.widgets.ClimateIconsView
import com.dash.headunit.widgets.ClimateWidgetView
import com.dash.headunit.widgets.OutsideWidgetView
import com.dash.headunit.widgets.ElmWidgetView
import com.dash.headunit.widgets.RetroRadioView
import com.dash.headunit.widgets.RetroSpeedometerView
import com.dash.headunit.widgets.SpeedWidgetView
import com.dash.headunit.widgets.VazClusterView
import com.dash.headunit.widgets.ScreensaverWidgetView
import com.dash.headunit.widgets.BrightnessWidgetView
import com.dash.headunit.car.HuBrightness
import com.dash.headunit.screensaver.Screensaver

/**
 * Главный экран дашборда ГУ (альбомная ориентация, иммерсивный режим).
 *
 * - Строит виджеты из [DashboardStore] на сетке 100x70.
 * - Иконка сетки включает режим редактирования: перемещение/ресайз,
 *   удаление (бейдж ✕), добавление блоков, очистка, сохранение.
 * - Геолокация запрашивается для виджета погоды.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var dashboard: DashboardGridLayout
    private lateinit var barNormal: View
    private lateinit var barEdit: View
    private lateinit var btnEdit: ImageButton
    private lateinit var iconWifi: ImageView
    private lateinit var iconBluetooth: ImageView
    private lateinit var store: DashboardStore
    private lateinit var settingsStore: WidgetSettingsStore
    private lateinit var themeStore: ThemeStore

    private var editMode = false
    private lateinit var screensaver: Screensaver
    private lateinit var theme: DashTheme

    override fun onCreate(savedInstanceState: Bundle?) {
        // Пока доступна только тёмная тема — форсируем её.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ClusterOverlay.init(this)
        ClusterSetup.restoreOnStart(this)

        // Не гаснуть на ГУ + во весь экран.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Окно всегда на весь экран: не зависит ни от системных панелей, ни
        // от wm overscan (остаётся от старых версий режима навигатора).
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN or
                // Немодальное окно: иначе WMS считает зоной касаний весь стек и
                // вырез под навигатор (NaviUnderlay.installTouchHole) не работает.
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                // Касания мимо окна (в навигатор в блоке) — тоже активность
                // для таймера заставки (ACTION_OUTSIDE).
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        )
        hideSystemUi()

        store = DashboardStore(this)
        settingsStore = WidgetSettingsStore(this)
        themeStore = ThemeStore(ThemeStore.PREFS, this)
        theme = themeStore.current()
        applyThemeChrome()
        dashboard = findViewById(R.id.dashboard)
        barNormal = findViewById(R.id.bar_normal)
        barEdit = findViewById(R.id.bar_edit)
        btnEdit = findViewById(R.id.btn_edit)
        iconWifi = findViewById(R.id.icon_wifi)
        iconBluetooth = findViewById(R.id.icon_bluetooth)
        // Диалог управления Wi-Fi: сохранённые/видимые сети, добавление сети.
        findViewById<View>(R.id.icon_wifi).setOnClickListener {
            com.dash.headunit.ui.WifiConnectionDialog.show(this)
        }
        applyBarSide()

        dashboard.onLayoutChanged = { persistLayout() }

        btnEdit.setOnClickListener { setEditMode(!editMode) }
        findViewById<View>(R.id.btn_done).setOnClickListener { setEditMode(false) }
        findViewById<View>(R.id.btn_clear).setOnClickListener { confirmClearDashboard() }
        findViewById<View>(R.id.btn_add).setOnClickListener { showAddDialog() }
        // Диагностика: проба подогрева руля.
        findViewById<View>(R.id.btn_debug).setOnClickListener {
            startActivity(android.content.Intent(this, com.dash.headunit.car.CarDebugActivity::class.java))
        }
        // Приборка по кругу: выкл → сам Яндекс Навигатор → наша карточка → выкл.
        // Долгое нажатие — снимок ведения для диагностики приборки (навигатор
        // остаётся в блоке): строка источников — во всплывашке, всё — в файл.
        findViewById<View>(R.id.btn_cluster_navi).setOnLongClickListener {
            val app = applicationContext
            Thread {
                val msg = try {
                    val f = com.dash.headunit.diag.NaviSnapshot.write(app, "cluster")
                    "${com.dash.headunit.navi.cluster.GuidanceHub.sourcesInfo()}\n${f.absolutePath}"
                } catch (e: Exception) {
                    "снимок: ${e.javaClass.simpleName}: ${e.message}"
                }
                runOnUiThread { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
            }.start()
            true
        }
        findViewById<View>(R.id.btn_cluster_navi).setOnClickListener {
            val next = when (clusterMode()) {
                CLUSTER_OFF -> CLUSTER_YANDEX
                CLUSTER_YANDEX -> CLUSTER_OURS
                else -> CLUSTER_OFF
            }
            setClusterMode(next)
            Toast.makeText(
                this,
                when (next) {
                    CLUSTER_YANDEX -> R.string.cluster_mode_yandex
                    CLUSTER_OURS -> R.string.cluster_mode_ours
                    else -> R.string.cluster_mode_off
                },
                Toast.LENGTH_SHORT
            ).show()
        }
        updateClusterNaviButton()
        findViewById<View>(R.id.btn_night).setOnClickListener {
            showThemeDialog()
        }
        findViewById<View>(R.id.btn_settings).setOnClickListener { showSettingsDialog() }
        // «Домой»: выход в штатную систему авто (домашний лаунчер).
        findViewById<View>(R.id.btn_home).setOnClickListener {
            try {
                startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_MAIN
                    ).apply {
                        addCategory(android.content.Intent.CATEGORY_HOME)
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(this, e.message ?: "?", Toast.LENGTH_SHORT).show()
            }
        }
        rootBackground = HoleBackground(theme.background, theme.glow).apply {
            // Углы блока навигатора — как у карточек (ThemeCard.card); рамка —
            // только в темах, где у виджетов есть карточка.
            val dp = resources.displayMetrics.density
            holeRadius = 12 * dp
            if (!theme.flatWidgets) {
                holeBorderWidth = 1 * dp
                holeBorderColor = theme.cardBorder
            }
        }
        findViewById<View>(R.id.root).background = rootBackground
        screensaver = Screensaver(this) { showing ->
            // Заставка непрозрачна: дашборд под ней не рисуем (иначе каждый
            // её кадр перерисовывает и все виджеты, ретро-приборы — на CPU).
            // INVISIBLE, не GONE — раскладка и вырез под навигатор остаются.
            findViewById<View>(R.id.main_row)?.visibility = if (showing) View.INVISIBLE else View.VISIBLE
            com.dash.headunit.ui.DashVisibility.setScreensaver(showing)
            // Под заставкой окно ловит касания целиком: разбудивший тап по
            // блоку навигатора не должен уйти в карту.
            window.decorView.requestLayout()
        }.apply { canShow = { !editMode && hasWindowFocus() } }
        // Под заставкой касания выреза тоже наши, но крайняя строка выреза
        // остаётся открытой: иначе окно навигатора пропадает для спец.
        // возможностей и ведение на приборку перестаёт идти.
        touchHoleInstalled = NaviUnderlay.installTouchHole(
            window.decorView,
            hole = { touchHole },
            swallowHole = { screensaver.isShowing },
            keep = { sideBarRect() }
        )
        // Навигатор мог остаться в границах блока после падения.
        NaviUnderlay.resetOnStart(this)
        NaviUnderlay.installCrashReset(this)
        // Штатная служба климата могла остаться остановленной (HvacShade).
        HvacShade.restoreOnStart(this)

        buildWidgets(store.load())
        updateStatusIcons()
        ensureLocationPermission()
        // Пуск двигателя — «пинок» навигатору на приборке (режим «Яндекс»).
        CarHub.init(this)
        CarHub.ensurePolling()
        CarHub.addListener(engineListener)
    }

    override fun onDestroy() {
        CarHub.removeListener(engineListener)
        super.onDestroy()
    }

    /** Колонка кнопок в координатах окна — её касания не отдаём ([NaviUnderlay.openEdge]). */
    private fun sideBarRect(): Rect? {
        val side = findViewById<View>(R.id.side_bar) ?: return null
        if (side.width == 0) return null
        val loc = IntArray(2).also { side.getLocationInWindow(it) }
        return Rect(loc[0], loc[1], loc[0] + side.width, loc[1] + side.height)
    }

    override fun onStart() {
        super.onStart()
        com.dash.headunit.ui.DashVisibility.setStarted(true)
    }

    override fun onResume() {
        super.onResume()
        updateStatusIcons()
        refreshNavi()
        // Заставку могли не убрать (процесс убит при показанной) — вернуть подсветку ГУ.
        HuBrightness.undim(this)
        screensaver.resume()
        // Вернулись на дашборд (сон ГУ, другое приложение) — навигатор на
        // приборке мог остаться без onResume.
        if (stoppedAway) {
            stoppedAway = false
            kickYandexIfOnCluster("возврат на дашборд")
        }
    }

    override fun onPause() {
        super.onPause()
        screensaver.pause()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // Касание, разбудившее заставку, виджетам не отдаём.
        if (screensaver.onTouch(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun onStop() {
        super.onStop()
        // Сон ГУ / уход с дашборда — поездка и моточасы на флеш (иначе
        // пишутся только на пуске и глушении).
        com.dash.headunit.car.TripTracker.persist(this)
        // Сами только что подняли навигатор под себя — это не уход с экрана.
        if (System.currentTimeMillis() - underlayLaunchedAt < UNDERLAY_GRACE_MS) return
        stoppedAway = true
        // Виджетов не видно — опросы, нужные только им, ждут ([DashVisibility]).
        com.dash.headunit.ui.DashVisibility.setStarted(false)
        // Дашборд закрыт другим приложением (или навигатором на весь экран):
        // вернуть экран целиком, навигатор под нами больше не гарантирован.
        underlayPkg = null
        NaviUnderlay.setHole(this, null)
        ClusterOverlay.naviVisible(false)
    }

    /**
     * Хром в цветах текущей темы: фон, фоны кнопок панелей.
     * Виджеты получают тему при построении.
     */
    private fun applyThemeChrome() {
        val card = ThemeCard.card(this, theme)
        findViewById<View>(R.id.icon_wifi).background = card
        findViewById<View>(R.id.icon_bluetooth).background = ThemeCard.card(this, theme)
        findViewById<View>(R.id.btn_night).background = ThemeCard.card(this, theme)
        findViewById<View>(R.id.btn_home).background = ThemeCard.card(this, theme)
        findViewById<View>(R.id.btn_settings).background = ThemeCard.card(this, theme)
        findViewById<View>(R.id.btn_edit).background = ThemeCard.card(this, theme)
        findViewById<View>(R.id.btn_clear).background = ThemeCard.card(this, theme)
        findViewById<View>(R.id.btn_add).background = ThemeCard.card(this, theme)
        findViewById<View>(R.id.btn_cluster_navi).background = ThemeCard.card(this, theme)
        findViewById<View>(R.id.btn_debug).background = ThemeCard.card(this, theme)
        findViewById<View>(R.id.btn_done).background = ThemeCard.accent(this, theme)
        findViewById<ImageButton>(R.id.btn_done).setColorFilter(theme.background)
        findViewById<View>(R.id.top_glow_line).apply {
            visibility = if (theme.glow != null) View.VISIBLE else View.GONE
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(android.graphics.Color.TRANSPARENT, theme.accent, android.graphics.Color.TRANSPARENT)
            )
        }
    }

    /** Диалог выбора темы оформления. */
    private fun showThemeDialog() {
        val themes = DashTheme.ALL
        val names = themes.map { it.name }.toTypedArray()
        val checked = themes.indexOfFirst { it.id == theme.id }.takeIf { it >= 0 } ?: 0
        var selected = checked
        DashDialog.Builder(this)
            .setTitle(R.string.set_theme_title)
            .setSingleChoiceItems(names, checked) { _, which ->
                selected = which
            }
            .setPositiveButton(R.string.edit_done) { _, _ ->
                val next = themes[selected]
                if (next.id != theme.id) {
                    themeStore.set(next)
                    recreate()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    /**
     * Настройки приложения — секции-карточки ([SettingsList]): общее,
     * приборка, автомобиль. Объём бака и срок журнала меняются прямо в строке.
     * Всё применяется по «Готово», «Отмена» ничего не меняет.
     */
    private fun showSettingsDialog() {
        var autostart = AppSettings.autostart(this)
        var barLeft = AppSettings.barSideLeft(this)
        var clusterNavi = AppSettings.clusterNavi(this)
        var etm = AppSettings.etmCoolant(this)
        var shade = AppSettings.shadeResume(this)
        var tank = CarHub.tankLiters(this)
        val keepOptions = intArrayOf(7, 14, 30, 0)
        var keep = keepOptions.indexOf(AppSettings.journalKeepDays(this))
            .takeIf { it >= 0 } ?: (keepOptions.size - 1)

        var dialog: android.app.Dialog? = null
        var applyRef: () -> Unit = {}
        val list = SettingsList(this, theme)
            .section(getString(R.string.settings_sec_general))
            .switch(R.drawable.ic_power, getString(R.string.settings_autostart), null, autostart) { autostart = it }
            .switch(
                R.drawable.ic_grid, getString(R.string.settings_bar_left_t),
                getString(R.string.settings_bar_left_s), barLeft
            ) { barLeft = it }
            .section(getString(R.string.settings_sec_about))
            .nav(
                R.drawable.ic_telegram, getString(R.string.contacts_telegram_t),
                getString(R.string.contacts_telegram_s), getString(R.string.contacts_telegram_handle)
            ) {
                // Уходим в контакты — отмеченное не теряем.
                applyRef()
                dialog?.dismiss()
                showContactsDialog()
            }
            .column()
            .section(getString(R.string.settings_sec_cluster))
            .switch(R.drawable.ic_nav, getString(R.string.settings_cluster_navi), null, clusterNavi) { clusterNavi = it }
            .switch(
                R.drawable.ic_thermo, getString(R.string.settings_etm_coolant_t),
                getString(R.string.settings_etm_coolant_s), etm
            ) { etm = it }
            .column()
            .section(getString(R.string.settings_sec_car))
            .switch(
                R.drawable.ic_window, getString(R.string.settings_shade_resume_t),
                getString(R.string.settings_shade_resume_s), shade
            ) { shade = it }
            .stepper(
                R.drawable.ic_fuel, getString(R.string.tank_title_short),
                getString(R.string.tank_sub), tank, CarHub.TANK_RANGE_L,
                { getString(R.string.tank_value, it) }
            ) { tank = it }
            .segmented(
                R.drawable.ic_journal, getString(R.string.journal_keep_title_short),
                getString(R.string.journal_keep_sub),
                listOf(
                    getString(R.string.journal_keep_week_s),
                    getString(R.string.journal_keep_two_weeks_s),
                    getString(R.string.journal_keep_month_s),
                    getString(R.string.journal_keep_always_s)
                ),
                keep
            ) { keep = it }

        fun apply() {
                applyAutostart(autostart)
                AppSettings.setBarSideLeft(this, barLeft)
                applyBarSide()
                // Выключили — выкл (Яндекс вернётся с приборки); включили — наша карточка.
                if (!clusterNavi) setClusterMode(CLUSTER_OFF)
                else if (clusterMode() == CLUSTER_OFF) setClusterMode(CLUSTER_OURS)
                // Шторка: настройка проверяется при следующем старте.
                AppSettings.setShadeResume(this, shade)
                // ETM: отключили — посылаем приборке выход из инженерного
                // меню (вернуть как было). Вход идёт при каждом
                // подключении [ClusterPps] к qconn.
                AppSettings.setEtmCoolant(this, etm)
                if (!etm) Thread { runCatching { CoolantEtm.send(0) } }.start()
                if (tank != CarHub.tankLiters(this)) CarHub.setTankLiters(this, tank)
                val days = keepOptions[keep]
                if (days != AppSettings.journalKeepDays(this)) {
                    AppSettings.setJournalKeepDays(this, days)
                    if (days > 0) TripTracker.applyJournalKeep(applicationContext, days)
                }
        }

        applyRef = ::apply
        dialog = DashDialog.Builder(this)
            .setIcon(R.drawable.ic_gear)
            .setTitle(R.string.settings_title)
            .setWidthDp(1640)
            .setView(list.view)
            .setPositiveButton(R.string.edit_done) { _, _ -> apply() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    /**
     * «Контакты»: QR-код и ссылка на Telegram-канал. На ГУ может не быть
     * ни Telegram, ни браузера — QR сканируется телефоном; «Открыть»
     * пробует открыть ссылку здесь.
     */
    private fun showContactsDialog() {
        val url = getString(R.string.contacts_telegram_url)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val qr = android.widget.ImageView(this).apply {
            setImageBitmap(QrCode.bitmap(url, dp(240), android.graphics.Color.BLACK, android.graphics.Color.WHITE))
            // Белая подложка с полями — читается в любой теме.
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(android.graphics.Color.WHITE)
            }
        }
        val texts = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(28), 0, 0, 0)
            fun line(text: String, size: Float, color: Int, bold: Boolean = false, top: Int = 0) =
                addView(TextView(this@MainActivity).apply {
                    this.text = text
                    textSize = size
                    setTextColor(color)
                    if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setLineSpacing(0f, 1.15f)
                    setPadding(0, dp(top), 0, 0)
                })
            line(getString(R.string.contacts_telegram_t), 22f, theme.primaryText, bold = true)
            line(getString(R.string.contacts_telegram_link), 20f, theme.accent, top = 6)
            line(getString(R.string.contacts_telegram_s), 16f, theme.secondaryText, top = 14)
            line(getString(R.string.contacts_scan_hint), 16f, theme.secondaryText, top = 6)
            line(getString(R.string.contacts_version, BuildConfig.VERSION_NAME), 14f, theme.secondaryText, top = 20)
        }
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(4), dp(28), dp(8))
            addView(qr, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(texts, android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
        }
        DashDialog.Builder(this)
            .setIcon(R.drawable.ic_telegram)
            .setTitle(R.string.contacts_title)
            .setWidthDp(900)
            .setView(content)
            .setPositiveButton(R.string.contacts_open) { _, _ -> openLink(url) }
            .setNegativeButton(R.string.contacts_close, null)
            .showFullScreen()
    }

    /** Открыть ссылку (Telegram / браузер); нечем — подсказать про QR. */
    private fun openLink(url: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = intent.resolveActivity(packageManager) != null &&
            runCatching { startActivity(intent) }.isSuccess
        if (!ok) Toast.makeText(this, R.string.contacts_no_app, Toast.LENGTH_LONG).show()
    }

    /**
     * Автозапуск держится на слушателе уведомлений: система привязывает его
     * при загрузке и поднимает наш процесс ([com.dash.headunit.settings.Autostart]).
     * При включении настройки включаем слушатель через adb-канал, если он выключен.
     */
    private fun applyAutostart(enabled: Boolean) {
        val was = AppSettings.autostart(this)
        AppSettings.setAutostart(this, enabled)
        if (!enabled || was || ClusterSetup.isListenerEnabled(this)) return
        val app = applicationContext
        Thread {
            val report = ClusterSetup.enableListener(app)
            val ok = ClusterSetup.isListenerEnabled(app)
            runOnUiThread {
                Toast.makeText(
                    app,
                    getString(if (ok) R.string.autostart_ready else R.string.autostart_no_listener, report),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    /**
     * Маршрут на приборке: при включении выдаём себе права через adb-канал
     * (оверлей, спец. возможности, уведомления) и, если маршрут уже идёт,
     * сразу показываем; при выключении — убираем с приборки.
     */
    private fun applyClusterNavi(enabled: Boolean) {
        val was = AppSettings.clusterNavi(this)
        AppSettings.setClusterNavi(this, enabled)
        if (enabled == was) return
        if (!enabled) {
            ClusterOverlay.remove()
            return
        }
        val app = applicationContext
        Thread {
            val report = ClusterSetup.ensureAll(app)
            runOnUiThread {
                Toast.makeText(app, report, Toast.LENGTH_LONG).show()
                ClusterOverlay.init(app)
                ClusterOverlay.show()
            }
        }.start()
    }

    /** Что сейчас на приборке по настройкам: [CLUSTER_OFF], [CLUSTER_YANDEX], [CLUSTER_OURS]. */
    private fun clusterMode(): Int = when {
        !AppSettings.clusterNavi(this) -> CLUSTER_OFF
        AppSettings.clusterYandex(this) -> CLUSTER_YANDEX
        else -> CLUSTER_OURS
    }

    /**
     * Переключить приборку. Уход из режима Яндекса — сначала вернуть
     * навигатор на главный экран; вход — перенести его на приборку
     * ([moveYandexToCluster]).
     */
    private fun setClusterMode(mode: Int) {
        val wasYandex = clusterMode() == CLUSTER_YANDEX
        AppSettings.setClusterYandex(this, mode == CLUSTER_YANDEX)
        if (wasYandex && mode != CLUSTER_YANDEX) {
            ClusterOverlay.setYandex(false)
            // Пока навигатор едет обратно, блок его не запускает: иначе
            // launchUnder и move-stack/resize идут двумя adb-потоками
            // вперемешку и итог зависит от скорости ГУ.
            yandexReturning = true
            NaviOnCluster.moveBack(this, YANDEX_NAVI) { ok ->
                yandexReturning = false
                if (!ok) Toast.makeText(this, NaviOnCluster.lastStatus, Toast.LENGTH_LONG).show()
                if (isDestroyed) return@moveBack
                // Навигатор вернулся на ГУ поверх дашборда — дашборд наверх,
                // блок «Навигатор» снова заберёт его к себе (запуском под себя:
                // после переезда у задачи новый стек во весь экран).
                underlayPkg = null
                bringDashboardToFront()
                refreshNavi()
            }
        }
        applyClusterNavi(mode != CLUSTER_OFF)
        if (mode == CLUSTER_YANDEX) moveYandexToCluster()
        updateClusterNaviButton()
        refreshNavi()
    }

    // Перенос навигатора на приборку идёт.
    private var yandexMoving = false

    // Навигатор возвращается с приборки на главный экран.
    private var yandexReturning = false

    /**
     * Режим «Яндекс на приборке»: перенести навигатор на дисплей приборки,
     * если он ещё не там (после перезагрузки — на старте). Не вышло —
     * откатиться на нашу карточку.
     */
    private fun moveYandexToCluster() {
        if (yandexMoving || NaviOnCluster.onCluster) return
        val target = ClusterOverlay.clusterTarget(this)
        if (target == null) {
            Toast.makeText(this, R.string.cluster_mode_no_display, Toast.LENGTH_LONG).show()
            setClusterMode(CLUSTER_OURS)
            return
        }
        yandexMoving = true
        // Пока навигатор едет и не поднят — на приборке чёрный занавес,
        // а не наша плашка и не зеркало дашборда.
        ClusterOverlay.coverForYandex()
        // Навигатор заберёт фокус — наш onStop в это время не уход с экрана.
        underlayLaunchedAt = System.currentTimeMillis()
        NaviOnCluster.moveToCluster(this, YANDEX_NAVI, target.first, target.second) { ok ->
            yandexMoving = false
            if (isDestroyed) return@moveToCluster
            if (clusterMode() != CLUSTER_YANDEX) return@moveToCluster
            if (ok) {
                // Блок «Навигатор» больше не держит его под собой.
                underlayPkg = null
                // Дать навигатору честный onResume (иначе карта стоит), потом
                // вернуть дашборд — Android 9: фокус у навигатора на приборке.
                // Занавес снимаем после пинка: am start может на миг уронить
                // картинку навигатора на приборке.
                kickYandexOnCluster(target.first) {
                    if (clusterMode() == CLUSTER_YANDEX) ClusterOverlay.setYandex(true)
                }
            } else {
                Toast.makeText(
                    this, getString(R.string.cluster_mode_yandex_failed, NaviOnCluster.lastStatus),
                    Toast.LENGTH_LONG
                ).show()
                setClusterMode(CLUSTER_OURS)
            }
            refreshNavi()
        }
    }

    // Идёт «пинок» навигатору на приборке.
    private var yandexKicking = false

    // Ждут конца текущего пинка (снять занавес на приборке).
    private val kickWaiters = mutableListOf<() -> Unit>()

    /**
     * Поднять навигатор на дисплее приборки [displayId] (он становится
     * RESUMED и включает слежение за GPS), через [YANDEX_FOCUS_BACK_MS] —
     * дашборд обратно. Нужен после переноса, пуска двигателя и возврата
     * на дашборд: в PAUSED Yandex рисует, но после холодного старта/сна
     * карта без onResume не двигается.
     */
    private fun kickYandexOnCluster(displayId: Int, onKicked: (() -> Unit)? = null) {
        if (isDestroyed) return
        onKicked?.let { kickWaiters += it }
        if (yandexKicking) return
        yandexKicking = true
        // Наш onPause/onStop на это время — не уход с экрана.
        underlayLaunchedAt = System.currentTimeMillis()
        NaviOnCluster.kick(this, YANDEX_NAVI, displayId) { ok ->
            val waiters = kickWaiters.toList()
            kickWaiters.clear()
            waiters.forEach { it() }
            if (isDestroyed) {
                yandexKicking = false
                return@kick
            }
            underlayLaunchedAt = System.currentTimeMillis()
            window.decorView.postDelayed({
                yandexKicking = false
                bringDashboardToFront()
            }, if (ok) YANDEX_FOCUS_BACK_MS else 0L)
        }
    }

    /** Режим «Яндекс на приборке» и навигатор уже там — пнуть его. */
    private fun kickYandexIfOnCluster(reason: String) {
        if (clusterMode() != CLUSTER_YANDEX || !NaviOnCluster.onCluster || yandexMoving || yandexReturning) return
        val display = ClusterOverlay.clusterTarget(this)?.first ?: return
        android.util.Log.i("MainActivity", "пинок навигатору на приборке: $reason")
        kickYandexOnCluster(display)
    }

    // Дашборд уходил с экрана (onStop не от наших же переключений).
    private var stoppedAway = false

    // Двигатель: последний тик CarHub с работающим двигателем (elapsedRealtime).
    private var lastEngineOnAt = 0L

    private val engineListener: () -> Unit = {
        val now = android.os.SystemClock.elapsedRealtime()
        if (com.dash.headunit.car.TripTracker.engineOn(com.dash.headunit.car.CarHub.data)) {
            // Пуск после стоянки: ГУ спала (процесс заморожен) — навигатор
            // на приборке остался PAUSED без слежения.
            if (lastEngineOnAt > 0L && now - lastEngineOnAt > ENGINE_RESTART_GAP_MS) {
                kickYandexIfOnCluster("пуск двигателя")
            }
            lastEngineOnAt = now
        }
    }

    private fun bringDashboardToFront() {
        if (isDestroyed) return
        underlayLaunchedAt = System.currentTimeMillis()
        try {
            startActivity(
                android.content.Intent(this, MainActivity::class.java).addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "?", Toast.LENGTH_SHORT).show()
        }
    }

    /** Кнопка приборки в баре: выкл — полупрозрачная, Яндекс — стрелка навигации. */
    private fun updateClusterNaviButton() {
        val btn = findViewById<ImageButton>(R.id.btn_cluster_navi)
        val mode = clusterMode()
        btn.setImageResource(if (mode == CLUSTER_YANDEX) R.drawable.ic_nav else R.drawable.ic_cluster)
        btn.alpha = if (mode == CLUSTER_OFF) 0.4f else 1f
    }

    /** Перенести вертикальную колонку иконок влево или вправо по настройке. */
    private fun applyBarSide() {
        val row = findViewById<android.widget.LinearLayout>(R.id.main_row) ?: return
        val side = findViewById<View>(R.id.side_bar) ?: return
        row.removeView(side)
        if (AppSettings.barSideLeft(this)) {
            row.addView(side, 0)
        } else {
            row.addView(side)
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateStatusIcons() {
        // Зелёный = есть подключение, серый = включено, тусклое = выключено.
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val connected = wifi?.isWifiEnabled == true &&
                runCatching { wifi.connectionInfo?.networkId != -1 }.getOrDefault(false)
            iconWifi.setColorFilter(if (connected) theme.success else theme.secondaryText)
            iconWifi.alpha = if (wifi?.isWifiEnabled == true) 1f else 0.35f
        } catch (e: Exception) {
            iconWifi.setColorFilter(theme.secondaryText)
            iconWifi.alpha = 0.35f
        }
        try {
            val bt = (getSystemService(BluetoothManager::class.java))?.adapter
            val connected = runCatching {
                // A2DP — подключён ли аудиопрофиль (нормальное право BLUETOOTH).
                bt?.getProfileConnectionState(BluetoothProfile.A2DP) ==
                    BluetoothAdapter.STATE_CONNECTED
            }.getOrDefault(false)
            iconBluetooth.setColorFilter(if (connected) theme.success else theme.secondaryText)
            iconBluetooth.alpha = if (bt?.isEnabled == true) 1f else 0.35f
        } catch (e: Exception) {
            iconBluetooth.setColorFilter(theme.secondaryText)
            iconBluetooth.alpha = 0.35f
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // ---------- навигатор под дашбордом ----------

    // Фон корня с вырезом под блок навигатора.
    private lateinit var rootBackground: HoleBackground

    // Касания в этой области (координаты окна) уходят навигатору.
    private var touchHole: Region? = null
    private var touchHoleInstalled = false

    // Пакет, запущенный под дашбордом (null — под нами ничего не гарантировано:
    // ещё не запускали или дашборд с тех пор закрывали).
    private var underlayPkg: String? = null
    private var underlayLaunchedAt = 0L

    // Навигатор после launchUnder ещё ни разу не был RESUMED.
    private var resumeKickPending = false

    // Подключение adb-канала идёт (ждём «Разрешить отладку»).
    private var connecting = false

    /** Первый блок «Навигатор» на дашборде (поддерживается один). */
    private fun naviWidget(): AppWidgetView? {
        for (i in 0 until dashboard.childCount) {
            val frame = dashboard.getChildAt(i) as? WidgetFrame ?: continue
            (frame.contentRoot as? AppWidgetView)?.let { return it }
        }
        return null
    }

    /**
     * Привести режим навигатора в соответствие с состоянием: есть ли блок,
     * выбран ли навигатор, есть ли adb-канал, не идёт ли редактирование.
     * Вызывается на onResume, после раскладки и смены режима.
     */
    private fun refreshNavi() {
        val view = naviWidget()
        // Остальные блоки «Навигатор» (если их несколько) — просто карточки.
        for (i in 0 until dashboard.childCount) {
            val other = (dashboard.getChildAt(i) as? WidgetFrame)?.contentRoot as? AppWidgetView
            if (other != null && other !== view) other.showMessage(getString(R.string.app_none))
        }
        val pkg = view?.pkg().orEmpty()
        val yandexMode = clusterMode() == CLUSTER_YANDEX
        // Режим «Яндекс на приборке», а перенос ещё не делали (старт приложения).
        if (yandexMode && !NaviOnCluster.onCluster && NaviUnderlay.shellReady == true) moveYandexToCluster()
        // Навигатор блока сейчас на приборке — в блоке только надпись.
        val naviAway = (yandexMode || yandexReturning) && pkg == YANDEX_NAVI
        val message: String? = when {
            view == null -> ""
            pkg.isEmpty() -> getString(R.string.app_none)
            !view.installed(pkg) -> getString(R.string.navi_not_installed, pkg)
            naviAway -> getString(R.string.navi_on_cluster, view.appLabel(pkg))
            editMode -> getString(R.string.navi_edit, view.appLabel(pkg))
            connecting -> getString(R.string.navi_connecting)
            NaviUnderlay.shellReady != true -> getString(R.string.navi_need_adb, view.appLabel(pkg))
            else -> null
        }
        if (message != null) {
            view?.showMessage(message)
            // Канал разрешали раньше и он вот-вот подключится — запустить
            // навигатор уже сейчас: его холодный старт — самое долгое на
            // запуске дашборда, пусть идёт параллельно с adb и первым кадром.
            if (view != null && pkg.isNotEmpty() && !editMode && !naviAway && view.installed(pkg) &&
                (connecting || NaviUnderlay.shellReady == null) && NaviUnderlay.isAuthorized(this)
            ) launchNavi(pkg)
            setNaviHole(null)
            ClusterOverlay.naviVisible(false)
            // Без блока навигатора экран должен быть целым.
            if (!editMode) NaviUnderlay.setHole(this, null)
            // Канал уже разрешали раньше — подключаемся сами, без касания.
            if (view != null && NaviUnderlay.shellReady == null && !connecting &&
                NaviUnderlay.isAuthorized(this)
            ) connectShell()
            return
        }
        view!!
        if (!launchNavi(pkg)) {
            view.showMessage(getString(R.string.navi_failed, NaviUnderlay.lastStatus))
            ClusterOverlay.naviVisible(false)
            return
        }
        view.showActive()
        // Границы блока известны только после раскладки.
        val frame = view.parent as View
        if (frame.width == 0 || frame.height == 0) {
            frame.post { refreshNavi() }
            return
        }
        val loc = IntArray(2)
        frame.getLocationOnScreen(loc)
        val screen = Rect(loc[0], loc[1], loc[0] + frame.width, loc[1] + frame.height)
        NaviUnderlay.setHole(this, screen, pkg) { inBlock ->
            if (inBlock) runOnUiThread { kickNaviResume(pkg) }
        }
        frame.getLocationInWindow(loc)
        val inWindow = Rect(loc[0], loc[1], loc[0] + frame.width, loc[1] + frame.height)
        setNaviHole(inWindow, view.expandButton)
        ClusterOverlay.naviVisible(true)
    }

    /** Запустить навигатор [pkg] под дашбордом, если он там ещё не запущен. */
    private fun launchNavi(pkg: String): Boolean {
        if (underlayPkg == pkg) return true
        if (!NaviUnderlay.launchUnder(this, pkg)) return false
        underlayPkg = pkg
        underlayLaunchedAt = System.currentTimeMillis()
        resumeKickPending = true
        return true
    }

    /**
     * Один раз после [NaviUnderlay.launchUnder]: навигатор уже в границах
     * блока — дать ему onResume (иначе карта не следит за машиной).
     */
    private fun kickNaviResume(pkg: String) {
        if (!resumeKickPending || underlayPkg != pkg || !isResumedNow()) return
        resumeKickPending = false
        // Пинок до готовности процесса навигатора пропадает впустую.
        NaviUnderlay.whenDrawn(this, pkg) {
            if (underlayPkg != pkg || !isResumedNow()) return@whenDrawn
            // Наш onPause/onStop на это время — не уход с экрана.
            underlayLaunchedAt = System.currentTimeMillis()
            NaviUnderlay.resumeInBlock(this, pkg)
        }
    }

    /** Вырезать [hole] (координаты окна) из фона и из зоны касаний дашборда. */
    private fun setNaviHole(hole: Rect?, keepTouchable: View? = null) {
        // Фон рисует корень — переводим вырез в его координаты.
        val root = findViewById<View>(R.id.root)
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        rootBackground.hole = hole?.let { Rect(it).apply { offset(-rootLoc[0], -rootLoc[1]) } }
        touchHole = hole?.let {
            Region(it).apply {
                // Кнопка «на весь экран» лежит поверх карты — её касания наши.
                if (keepTouchable != null && keepTouchable.width > 0) {
                    val loc = IntArray(2)
                    keepTouchable.getLocationInWindow(loc)
                    op(
                        Rect(loc[0], loc[1], loc[0] + keepTouchable.width, loc[1] + keepTouchable.height),
                        Region.Op.DIFFERENCE
                    )
                }
            }
        }
        // Пересчитать зону касаний окна (ViewRootImpl спрашивает её на обходе).
        window.decorView.requestLayout()
    }

    /** Подключить adb-канал; при первом разе ГУ спросит «Разрешить отладку». */
    private fun connectShell() {
        if (connecting) return
        connecting = true
        refreshNavi()
        NaviUnderlay.connect(this) { ok ->
            connecting = false
            if (isDestroyed) return@connect
            if (!ok && !NaviUnderlay.portOpen) {
                naviWidget()?.showMessage(getString(R.string.navi_port_closed))
                return@connect
            }
            refreshNavi()
        }
    }

    /** Навигатор на весь экран; вернётся в блок, когда снова откроют дашборд. */
    private fun openNaviFull() {
        val pkg = naviWidget()?.pkg().orEmpty()
        if (pkg.isNotEmpty()) NaviUnderlay.openFull(this, pkg)
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    // ---------- виджеты ----------

    private fun buildWidgets(specs: List<WidgetSpec>) {
        dashboard.removeAllViews()
        specs.forEach { spec ->
            // Общий вид «Ретро» — у виджетов без своей ретро-отрисовки.
            val retro = spec.type !in OWN_RETRO && RetroLook.isOn(settingsStore, spec.widgetId)
            val wTheme = if (retro) RetroLook.THEME else theme
            val content: View = when (spec.type) {
                WidgetType.CLOCK -> ClockWidgetView(this, wTheme)
                WidgetType.COMBO -> ComboWidgetView(this, spec.widgetId, wTheme, settingsStore)
                WidgetType.WEATHER -> ForecastStripView(this, spec.widgetId, wTheme, settingsStore)
                WidgetType.CAR -> CarWidgetView(this, wTheme, spec.widgetId, settingsStore)
                WidgetType.MUSIC -> MusicWidgetView(this, wTheme, spec.widgetId, settingsStore)
                WidgetType.YANDEX_MUSIC -> YandexMusicWidgetView(this, wTheme, spec.widgetId, settingsStore)
                WidgetType.RADIO ->
                    when (settingsStore.getOption(spec.widgetId, RetroLook.OPT_LOOK, RetroLook.LOOK_MODERN)) {
                        RetroLook.LOOK_RETRO -> RetroRadioView(this)
                        RetroLook.LOOK_CLASSIC -> RadioWidgetView(this, wTheme)
                        else -> ModernRadioView(this, wTheme)
                    }
                WidgetType.WIFI -> WifiWidgetView(this, wTheme)
                WidgetType.APP -> AppWidgetView(this, spec.widgetId, wTheme, settingsStore).apply {
                    onTapIdle = { if (!editMode) connectShell() }
                    onExpand = { openNaviFull() }
                }
                WidgetType.APPS -> AppsWidgetView(
                    this, spec.widgetId, wTheme, settingsStore
                ) { showAppsSettings(spec.widgetId) }
                WidgetType.TRIP -> TripWidgetView(this, wTheme, spec.widgetId, settingsStore)
                WidgetType.CLIMATE ->
                    if (settingsStore.getOption(spec.widgetId, OPT_CLIMATE_STYLE, CLIMATE_TEXT) == CLIMATE_ICONS) {
                        ClimateIconsView(this)
                    } else {
                        ClimateWidgetView(this, wTheme)
                    }
                WidgetType.CLIMATE_CONTROL -> ClimateControlView(
                    this, wTheme,
                    retro = settingsStore.getOption(
                        spec.widgetId, ClimateControlView.OPT_THEME, ClimateControlView.THEME_CLASSIC
                    ) == ClimateControlView.THEME_RETRO,
                    items = ClimateControlView.selected(settingsStore, spec.widgetId)
                )
                WidgetType.REFERENCE -> ReferenceWidgetView(this, wTheme)
                // Тот же вид, что «Управление климатом», только подогревы; «Ретро» — его своя тема.
                WidgetType.HEAT -> ClimateControlView(
                    this, wTheme,
                    retro = RetroLook.isOn(settingsStore, spec.widgetId),
                    items = ClimateControlView.HEAT_ITEMS
                )
                WidgetType.OUTSIDE -> OutsideWidgetView(this, wTheme)
                WidgetType.ELM -> ElmWidgetView(this, wTheme, spec.widgetId, settingsStore)
                WidgetType.SPEED ->
                    when (settingsStore.getOption(spec.widgetId, OPT_SPEED_STYLE, SPEED_RETRO)) {
                        SPEED_DIGITAL -> SpeedWidgetView(this, wTheme)
                        SPEED_VAZ -> VazClusterView(this)
                        else -> RetroSpeedometerView(this)
                    }
                WidgetType.BUTTONS -> ButtonsWidgetView(this, wTheme)
                WidgetType.SCREENSAVER -> ScreensaverWidgetView(
                    this, wTheme, Screensaver.config(settingsStore, spec.widgetId),
                    canToggle = { !editMode }
                ) { on ->
                    settingsStore.setOption(spec.widgetId, Screensaver.OPT_ENABLED, if (on) "1" else "0")
                    applyScreensaverConfig(readSpecs())
                }
                WidgetType.BRIGHTNESS -> BrightnessWidgetView(this, wTheme)
                WidgetType.IGNITION -> IgnitionWidgetView(this, wTheme)
            }
            val frame = WidgetFrame(
                context = this,
                widgetId = spec.widgetId,
                widgetType = spec.type,
                editing = editMode,
                theme = wTheme,
                retro = retro,
                bgMode = WidgetBackground.get(settingsStore, spec.widgetId),
                onGeometryChanged = { persistLayout() },
                onDelete = { removeWidget(spec.widgetId) },
                // Настройки: свои у части виджетов, у остальных — только вид.
                onSettings = when (spec.type) {
                    WidgetType.WEATHER -> {
                        { showWeatherSettings(spec.widgetId) }
                    }
                    WidgetType.APP -> {
                        { showAppSettings(spec.widgetId) }
                    }
                    WidgetType.APPS -> {
                        { showAppsSettings(spec.widgetId) }
                    }
                    WidgetType.CAR -> {
                        { showCarSettings(spec.widgetId) }
                    }
                    WidgetType.TRIP -> {
                        { showTripSettings(spec.widgetId) }
                    }
                    WidgetType.ELM -> {
                        { showElmSettings(spec.widgetId) }
                    }
                    WidgetType.SPEED -> {
                        { showSpeedSettings(spec.widgetId) }
                    }
                    WidgetType.CLIMATE -> {
                        { showClimateSettings(spec.widgetId) }
                    }
                    WidgetType.CLIMATE_CONTROL -> {
                        { showClimateControlSettings(spec.widgetId) }
                    }
                    WidgetType.COMBO -> {
                        { showClockSettings(spec.widgetId) }
                    }
                    WidgetType.MUSIC, WidgetType.YANDEX_MUSIC -> {
                        { showMusicSettings(spec.widgetId, spec.type) }
                    }
                    WidgetType.SCREENSAVER -> {
                        { showScreensaverSettings(spec.widgetId) }
                    }
                    else -> {
                        { showLookSettings(spec.widgetId, spec.type) }
                    }
                }
            )
            frame.setContent(content)
            if (retro) RetroLook.applyText(content)
            val lp = DashboardGridLayout.LayoutParams(spec.x, spec.y, spec.w, spec.h, spec.type.minW)
            dashboard.addView(frame, lp)
            if (spec.type == WidgetType.APP) {
                // Блок переехал (раскладка, смена размера окна) — двигаем и навигатор.
                frame.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                    if ((l != ol || t != ot || r != or || b != ob) && isResumedNow()) {
                        frame.post { refreshNavi() }
                    }
                }
            }
        }
        applyScreensaverConfig(specs)
        if (isResumedNow()) refreshNavi()
    }

    /** Заставка — по первому виджету «Заставка»; нет виджета или выключена кнопкой — нет. */
    private fun applyScreensaverConfig(specs: List<WidgetSpec>) {
        screensaver.config = specs.firstOrNull { it.type == WidgetType.SCREENSAVER }
            ?.let { Screensaver.config(settingsStore, it.widgetId) }
            ?.takeIf { it.enabled }
    }

    private fun isResumedNow(): Boolean =
        lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)

    private fun setEditMode(enabled: Boolean) {
        editMode = enabled
        // Активный режим — подсветка иконки акцентом темы.
        if (enabled) {
            btnEdit.setColorFilter(theme.accent)
        } else {
            btnEdit.clearColorFilter()
        }
        // В режиме редактирования иконки заменяются панелью действий.
        barNormal.visibility = if (enabled) View.GONE else View.VISIBLE
        barEdit.visibility = if (enabled) View.VISIBLE else View.GONE
        for (i in 0 until dashboard.childCount) {
            (dashboard.getChildAt(i) as? WidgetFrame)?.setEditMode(enabled)
        }
        if (!enabled) persistLayout()
        // Блок навигатора мог переехать; в редактировании он — карточка.
        refreshNavi()
    }

    /** Кнопка-иконка без подписи — легко задеть, поэтому переспрашиваем. */
    private fun confirmClearDashboard() {
        DashDialog.Builder(this)
            .setTitle(R.string.clear_confirm_title)
            .setMessage(R.string.clear_confirm_text)
            .setPositiveButton(R.string.clear_confirm_ok) { _, _ -> clearDashboard() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    /** «Сбросить»: убрать все виджеты, дашборд остаётся пустым. */
    private fun clearDashboard() {
        store.clearAll()
        buildWidgets(emptyList())
        Toast.makeText(this, R.string.msg_cleared, Toast.LENGTH_SHORT).show()
    }

    /** Один пункт выбора виджета: тип, подпись, иконка. */
    private class PickerItem(
        val type: WidgetType,
        val labelRes: Int,
        val iconRes: Int,
        /** Цветная иконка — не тонируем. */
        val colored: Boolean = false
    )

    private fun pickerItems(): List<PickerItem> = listOf(
        PickerItem(WidgetType.COMBO, R.string.widget_clock, R.drawable.ic_clock),
        PickerItem(WidgetType.WEATHER, R.string.widget_weather, R.drawable.ic_cloud),
        PickerItem(WidgetType.CAR, R.string.widget_car, R.drawable.ic_fuel),
        PickerItem(WidgetType.MUSIC, R.string.widget_music, R.drawable.ic_play),
        PickerItem(WidgetType.YANDEX_MUSIC, R.string.widget_yandex_music, R.drawable.ic_ymusic),
        PickerItem(WidgetType.RADIO, R.string.widget_radio, R.drawable.ic_radio),
        PickerItem(WidgetType.WIFI, R.string.widget_wifi, R.drawable.ic_wifi),
        PickerItem(WidgetType.APP, R.string.widget_app, R.drawable.ic_nav),
        PickerItem(WidgetType.APPS, R.string.widget_apps, R.drawable.ic_apps),
        PickerItem(WidgetType.TRIP, R.string.widget_trip, R.drawable.ic_journal),
        PickerItem(WidgetType.SPEED, R.string.widget_speed, R.drawable.ic_gauge),
        PickerItem(WidgetType.OUTSIDE, R.string.widget_outside, R.drawable.ic_thermo),
        PickerItem(WidgetType.CLIMATE, R.string.widget_climate, R.drawable.ic_fan),
        PickerItem(WidgetType.CLIMATE_CONTROL, R.string.widget_climate_control, R.drawable.ic_fan),
        PickerItem(WidgetType.BUTTONS, R.string.widget_buttons, R.drawable.ic_grid),
        PickerItem(WidgetType.REFERENCE, R.string.widget_reference, R.drawable.ic_book),
        PickerItem(WidgetType.HEAT, R.string.widget_heat, R.drawable.ic_seat_heat),
        PickerItem(WidgetType.ELM, R.string.widget_elm, R.drawable.ic_obd),
        PickerItem(WidgetType.SCREENSAVER, R.string.widget_screensaver, R.drawable.ic_screensaver, colored = true),
        PickerItem(WidgetType.BRIGHTNESS, R.string.widget_brightness, R.drawable.ic_brightness),
        PickerItem(WidgetType.IGNITION, R.string.widget_ignition, R.drawable.ic_ignition),
    ).filterNot { it.type in HIDDEN_PICKER_TYPES }

    /**
     * Диалог «+ Добавить блок»: сетка карточек с иконкой и подписью.
     * Виджеты, которые уже стоят на главной, отмечены.
     */
    private fun showAddDialog() {
        val added = readSpecs().map { it.type }.toSet()
        lateinit var dialog: Dialog
        val grid = buildPickerGrid(pickerItems(), added) { type ->
            dialog.dismiss()
            addWidget(type)
        }
        dialog = DashDialog.Builder(this)
            .setTitle(R.string.add_title)
            .setView(grid)
            .setWidthDp(PICKER_WIDTH_DP)
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    /**
     * Сетка карточек; [onPick] вызывается по тапу. Уже добавленные — отмечены.
     * Ячейки квадратные: сторона — по ширине диалога, содержимое карточки
     * ужимается под неё ([PickerGrid]).
     */
    private fun buildPickerGrid(
        items: List<PickerItem>,
        added: Set<WidgetType>,
        onPick: (WidgetType) -> Unit
    ): View {
        val grid = PickerGrid(this, PICKER_COLUMNS, dp(10)).apply {
            setPadding(dp(24), 0, dp(24), dp(4))
        }
        items.forEach { item ->
            grid.addView(PickerCard(item, item.type in added, onPick))
        }
        // Вертикальная прокрутка: пунктов больше, чем влезает на экран.
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            addView(
                grid,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    /**
     * Карточка виджета: иконка сверху, подпись, отметка «Уже на главной».
     * Содержимое ужимается под квадратную ячейку ([PickerGrid.Cell]).
     */
    private inner class PickerCard(
        item: PickerItem,
        added: Boolean,
        onPick: (WidgetType) -> Unit
    ) : LinearLayout(this@MainActivity), PickerGrid.Cell {

        private val icon = ImageView(this@MainActivity)
        private val label = TextView(this@MainActivity)
        private val note = TextView(this@MainActivity)

        /** Последний применённый ужим — чтобы не дёргать вёрстку вхолостую. */
        private var applied = -1f

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = ThemeCard.card(this@MainActivity, theme)
            isClickable = true
            setOnClickListener { onPick(item.type) }
            icon.apply {
                setImageResource(item.iconRes)
                // FIT_CENTER, а не CENTER_INSIDE: вектор не увеличивается выше
                // своего размера (28 dp), а бокс иконки — крупнее.
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (!item.colored) setColorFilter(if (added) theme.accent else theme.primaryText)
            }
            label.apply {
                text = getString(item.labelRes)
                gravity = Gravity.CENTER
                maxLines = 2
                setTextColor(theme.primaryText)
            }
            note.apply {
                text = if (added) getString(R.string.add_on_dashboard) else ""
                gravity = Gravity.CENTER
                maxLines = 1
                setTextColor(theme.accent)
            }
            addView(icon, LayoutParams(dp(PICKER_ICON_DP), dp(PICKER_ICON_DP)))
            addView(
                label,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(PICKER_LABEL_GAP_DP) }
            )
            addView(
                note,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(PICKER_NOTE_GAP_DP) }
            )
            applyScale(1f)
        }

        /** Ужать содержимое под сторону ячейки: [scale] — доля полного размера. */
        override fun applyScale(scale: Float) {
            if (scale == applied) return
            applied = scale
            val s = scale
            setPadding(
                dp((PICKER_PAD_H_DP * s).toInt()), dp((PICKER_PAD_V_DP * s).toInt()),
                dp((PICKER_PAD_H_DP * s).toInt()), dp((PICKER_PAD_V_DP * s).toInt())
            )
            icon.layoutParams = LayoutParams(
                dp((PICKER_ICON_DP * s).toInt()), dp((PICKER_ICON_DP * s).toInt())
            )
            label.textSize = PICKER_LABEL_SP * s
            (label.layoutParams as LayoutParams).topMargin = dp((PICKER_LABEL_GAP_DP * s).toInt())
            note.textSize = PICKER_NOTE_SP * s
            (note.layoutParams as LayoutParams).topMargin = dp((PICKER_NOTE_GAP_DP * s).toInt())
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private fun addWidget(type: WidgetType) {
        val specs = readSpecs().toMutableList()
        val sameCount = specs.count { it.type == type }
        val (w, h) = when (type) {
            WidgetType.COMBO -> 60 to 20
            WidgetType.WEATHER -> 92 to 30
            WidgetType.CAR -> 92 to 24
            WidgetType.MUSIC -> 92 to 24
            WidgetType.YANDEX_MUSIC -> 92 to 30
            WidgetType.RADIO -> 92 to 24
            WidgetType.APP -> 92 to 40
            WidgetType.APPS -> 28 to 22
            WidgetType.TRIP -> 92 to 24
            WidgetType.CLIMATE -> 92 to 40
            WidgetType.CLIMATE_CONTROL -> 92 to 24
            WidgetType.REFERENCE -> 56 to 40
            WidgetType.HEAT -> 60 to 32
            WidgetType.OUTSIDE -> 40 to 20
            WidgetType.WIFI -> 40 to 18
            WidgetType.ELM -> 56 to 44
            WidgetType.SPEED -> 48 to 26
            WidgetType.BUTTONS -> 56 to 40
            WidgetType.CLOCK -> 40 to 24
            WidgetType.SCREENSAVER -> 40 to 14
            WidgetType.BRIGHTNESS -> 48 to 18
            WidgetType.IGNITION -> 56 to 40
        }
        // Каскадом от левого верхнего угла, кламп — в границы сетки.
        val spec = WidgetSpec(
            widgetId = "${type.id}_${sameCount + 1}",
            type = type,
            x = (4 + specs.size * 6) % (DashboardGridLayout.COLS - w),
            y = (6 + specs.size * 4) % (DashboardGridLayout.ROWS - h),
            w = w,
            h = h
        ).clamped()
        specs.add(spec)
        store.save(specs)
        buildWidgets(specs)
        Toast.makeText(this, R.string.msg_added, Toast.LENGTH_SHORT).show()
    }

    private fun removeWidget(widgetId: String) {
        val specs = readSpecs().filterNot { it.widgetId == widgetId }
        store.save(specs)
        buildWidgets(specs)
        Toast.makeText(this, R.string.msg_removed, Toast.LENGTH_SHORT).show()
    }

    /**
     * Настройки виджета погоды: число дней/часов (1–5) и единицы (°C/°F).
     * После сохранения виджеты перестраиваются.
     */
    private fun showWeatherSettings(widgetId: String) {
        var days = settingsStore.getDays(widgetId)
        var hours = settingsStore.getHours(widgetId)
        var unit = settingsStore.getUnit(widgetId)
        val range = WidgetSettingsStore.MIN_COUNT..WidgetSettingsStore.MAX_COUNT
        val list = SettingsList(this, theme)
            .section(getString(R.string.weather_sec_forecast))
            .stepper(R.drawable.ic_calendar, getString(R.string.set_days), null, days, range, { "$it" }) { days = it }
            .stepper(R.drawable.ic_clock, getString(R.string.set_hours), null, hours, range, { "$it" }) { hours = it }
            .segmented(
                R.drawable.ic_thermo, getString(R.string.weather_units), null,
                listOf("°C", "°F"), if (unit == "F") 1 else 0
            ) { unit = if (it == 1) "F" else "C" }
            .column()
        val saveTile = list.tileSection(widgetId)
        showWidgetSettings(WidgetType.WEATHER, list, 1400) {
            settingsStore.setDays(widgetId, days)
            settingsStore.setHours(widgetId, hours)
            settingsStore.setUnit(widgetId, unit)
            saveTile()
        }
    }

    /** Настройки виджета без своих опций — только вид и фон плитки. */
    private fun showLookSettings(widgetId: String, type: WidgetType) {
        val list = SettingsList(this, theme)
        // У «Радио» три вида, по умолчанию — «Современный».
        val saveTile = if (type == WidgetType.RADIO) {
            list.tileSection(
                widgetId,
                looks = listOf(
                    RetroLook.LOOK_MODERN to getString(R.string.look_modern),
                    RetroLook.LOOK_CLASSIC to getString(R.string.look_classic),
                    RetroLook.LOOK_RETRO to getString(R.string.look_retro)
                )
            )
        } else {
            list.tileSection(widgetId)
        }
        showWidgetSettings(type, list, 760) { saveTile() }
    }

    /**
     * Секция «Плитка»: вид «Обычный / Ретро» ([withLook]) и фон блока
     * ([WidgetBackground]: как в теме / градиент / прозрачный). Возвращает
     * «сохранить выбранное» — его зовут на «Готово».
     */
    private fun SettingsList.tileSection(
        widgetId: String,
        withLook: Boolean = true,
        looks: List<Pair<String, String>> = listOf(
            RetroLook.LOOK_CLASSIC to getString(R.string.look_classic),
            RetroLook.LOOK_RETRO to getString(R.string.look_retro)
        ),
        extra: SettingsList.() -> Unit = {}
    ): () -> Unit {
        section(getString(R.string.tile_section))
        extra()
        // Первый вид в списке — вид по умолчанию.
        val ids = looks.map { it.first }
        var look = settingsStore.getOption(widgetId, RetroLook.OPT_LOOK, ids.first())
            .takeIf { it in ids } ?: ids.first()
        if (withLook) {
            segmented(
                R.drawable.ic_palette, getString(R.string.look_title), null,
                looks.map { it.second },
                ids.indexOf(look)
            ) { look = ids[it] }
        }
        var bg = WidgetBackground.get(settingsStore, widgetId)
            .takeIf { it in WidgetBackground.ALL } ?: WidgetBackground.ALL.first()
        segmented(
            R.drawable.ic_layers, getString(R.string.bg_title), null,
            listOf(getString(R.string.bg_theme), getString(R.string.bg_gradient), getString(R.string.bg_transparent)),
            WidgetBackground.ALL.indexOf(bg)
        ) { bg = WidgetBackground.ALL[it] }
        return {
            if (withLook) settingsStore.setOption(widgetId, RetroLook.OPT_LOOK, look)
            settingsStore.setOption(widgetId, WidgetBackground.OPT_BG, bg)
        }
    }

    /**
     * Диалог настроек виджета: иконка и название типа в заголовке,
     * [list] внутри; [onDone] — сохранить, затем виджеты пересобираются.
     */
    private fun showWidgetSettings(
        type: WidgetType,
        list: SettingsList,
        widthDp: Int,
        iconRes: Int? = null,
        onDone: () -> Unit
    ) {
        val item = pickerItems().firstOrNull { it.type == type }
        DashDialog.Builder(this)
            .setIcon(iconRes ?: item?.iconRes?.takeIf { !item.colored } ?: R.drawable.ic_gear)
            .setTitle(item?.let { getString(it.labelRes) } ?: getString(R.string.set_look_title))
            .setWidthDp(widthDp)
            .setView(list.view)
            .setPositiveButton(R.string.edit_done) { _, _ ->
                onDone()
                buildWidgets(readSpecs())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    /**
     * Настройки виджетов музыки: блок спектра и его вид
     * ([SpectrumView.STYLES]), вид/фон плитки. Спектру нужен RECORD_AUDIO —
     * без него (или если звук идёт мимо микшера Android) рисуется имитация.
     */
    private fun showMusicSettings(widgetId: String, type: WidgetType) {
        var spectrumOn = SpectrumView.isOn(settingsStore, widgetId)
        var selected = SpectrumView.STYLES.indexOf(SpectrumView.style(settingsStore, widgetId)).coerceAtLeast(0)
        val styleLabels = listOf(
            R.string.spectrum_style_bars,
            R.string.spectrum_style_leds,
            R.string.spectrum_style_wave,
            R.string.spectrum_style_mirror,
            R.string.spectrum_style_dots
        ).map { getString(it) }
        val list = SettingsList(this, theme)
            .section(getString(R.string.spectrum_section))
            .switch(R.drawable.ic_spectrum, getString(R.string.spectrum_show), null, spectrumOn) { spectrumOn = it }
            .segmented(R.drawable.ic_palette, getString(R.string.spectrum_style_title), null, styleLabels, selected) {
                selected = it
            }
            .hint(getString(R.string.spectrum_simulated_hint))
            .column()
        val saveTile = list.tileSection(widgetId)
        showWidgetSettings(type, list, 1400) {
            settingsStore.setOption(widgetId, SpectrumView.OPT_SPECTRUM, if (spectrumOn) "on" else "off")
            settingsStore.setOption(widgetId, SpectrumView.OPT_SPECTRUM_STYLE, SpectrumView.STYLES[selected])
            saveTile()
            if (spectrumOn && !SpectrumHub.hasAudioPermission(this)) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_SPECTRUM_AUDIO
                )
            }
        }
    }

    /**
     * Настройки «Заставки»: через сколько без касаний включаться, яркость
     * экрана под заставкой и её фон (тёмный с часами, камин, северное сияние).
     */
    private fun showScreensaverSettings(widgetId: String) {
        val cur = Screensaver.config(settingsStore, widgetId)
        val timeouts = Screensaver.TIMEOUTS
        var timeout = cur.timeoutSec.takeIf { it in timeouts } ?: Screensaver.DEFAULT_TIMEOUT
        var level = cur.level
        val backgrounds = Screensaver.BACKGROUNDS
        var background = cur.background
        var rotate = cur.rotate
        val rotateIntervals = Screensaver.ROTATE_INTERVALS
        var rotateSec = cur.rotateSec.takeIf { it in rotateIntervals } ?: Screensaver.DEFAULT_ROTATE_SEC

        val list = SettingsList(this, theme)
            .section(getString(R.string.screensaver_sec_saver))
            .segmented(
                R.drawable.ic_clock, getString(R.string.screensaver_timeout_t),
                getString(R.string.screensaver_timeout_s),
                timeouts.map { Screensaver.timeoutLabel(it) },
                timeouts.indexOf(timeout)
            ) { timeout = timeouts[it] }
            .slider(
                R.drawable.ic_brightness, getString(R.string.screensaver_brightness_t),
                getString(R.string.screensaver_brightness_s),
                level, HuBrightness.MIN_LEVEL..HuBrightness.MAX_LEVEL, 1,
                { "$it / ${HuBrightness.MAX_LEVEL}" }
            ) { level = it }
            .segmented(
                R.drawable.ic_image, getString(R.string.screensaver_background_t), null,
                listOf(
                    getString(R.string.screensaver_bg_dark),
                    getString(R.string.screensaver_bg_fireplace),
                    getString(R.string.screensaver_bg_aurora)
                ),
                backgrounds.indexOf(background).coerceAtLeast(0)
            ) { background = backgrounds[it] }
            .column()
            .section(getString(R.string.screensaver_sec_rotate))
            .switch(
                R.drawable.ic_rotate, getString(R.string.screensaver_rotate_t),
                getString(R.string.screensaver_rotate_s), rotate
            ) { rotate = it }
            .segmented(
                R.drawable.ic_rotate, getString(R.string.screensaver_rotate_interval_t),
                getString(R.string.screensaver_rotate_interval_s),
                rotateIntervals.map { Screensaver.timeoutLabel(it) },
                rotateIntervals.indexOf(rotateSec)
            ) { rotateSec = rotateIntervals[it] }
        val saveTile = list.tileSection(widgetId)

        showWidgetSettings(WidgetType.SCREENSAVER, list, 1400, iconRes = R.drawable.ic_image) {
            settingsStore.setOption(widgetId, Screensaver.OPT_TIMEOUT, timeout.toString())
            settingsStore.setOption(widgetId, Screensaver.OPT_LEVEL, level.toString())
            settingsStore.setOption(widgetId, Screensaver.OPT_BACKGROUND, background)
            settingsStore.setOption(widgetId, Screensaver.OPT_ROTATE, if (rotate) "1" else "0")
            settingsStore.setOption(widgetId, Screensaver.OPT_ROTATE_SEC, rotateSec.toString())
            saveTile()
        }
    }

    /** Вид «Спидометра»: ретро-стрелочный, классика ВАЗ или цифровой. */
    private fun showSpeedSettings(widgetId: String) {
        val styles = listOf(SPEED_RETRO, SPEED_VAZ, SPEED_DIGITAL)
        var selected = styles.indexOf(settingsStore.getOption(widgetId, OPT_SPEED_STYLE, SPEED_RETRO))
            .coerceAtLeast(0)
        val list = SettingsList(this, theme)
            .section(getString(R.string.speed_style_title))
            .choices(
                listOf(
                    getString(R.string.speed_style_retro),
                    getString(R.string.speed_style_vaz),
                    getString(R.string.speed_style_digital)
                ),
                selected, R.drawable.ic_gauge
            ) { selected = it }
            .column()
        val saveTile = list.tileSection(widgetId, withLook = false)
        showWidgetSettings(WidgetType.SPEED, list, 1300) {
            settingsStore.setOption(widgetId, OPT_SPEED_STYLE, styles[selected])
            saveTile()
        }
    }

    /** Вид «Климата»: текстом или ретро-иконками, как на спидометре. */
    private fun showClimateSettings(widgetId: String) {
        val styles = listOf(CLIMATE_TEXT, CLIMATE_ICONS)
        var selected = styles.indexOf(settingsStore.getOption(widgetId, OPT_CLIMATE_STYLE, CLIMATE_TEXT))
            .coerceAtLeast(0)
        val list = SettingsList(this, theme)
            .section(getString(R.string.widget_climate))
            .segmented(
                R.drawable.ic_fan, getString(R.string.look_title), null,
                listOf(getString(R.string.climate_style_text), getString(R.string.climate_style_icons)),
                selected
            ) { selected = it }
            .column()
        val saveTile = list.tileSection(widgetId, withLook = false)
        showWidgetSettings(WidgetType.CLIMATE, list, 1300) {
            settingsStore.setOption(widgetId, OPT_CLIMATE_STYLE, styles[selected])
            saveTile()
        }
    }

    /**
     * Настройки «Управления климатом»: тема (классическая / ретро) и
     * состав кнопок с порядком — как у «Автомобиля» ([showCarSettings]).
     */
    private fun showClimateControlSettings(widgetId: String) {
        val themes = listOf(ClimateControlView.THEME_CLASSIC, ClimateControlView.THEME_RETRO)
        var themeSel = settingsStore.getOption(widgetId, ClimateControlView.OPT_THEME, ClimateControlView.THEME_CLASSIC)
        val order = ClimateControlView.order(settingsStore, widgetId).toMutableList()
        val checked = ClimateControlView.selected(settingsStore, widgetId).toMutableSet()
        val list = SettingsList(this, theme)
            .section(getString(R.string.cc_items_title))
            .reorder(order, checked, { getString(it.labelRes) })
            .column()
        val saveTile = list.tileSection(widgetId, withLook = false) {
            segmented(
                R.drawable.ic_palette, getString(R.string.cc_theme_title), null,
                listOf(getString(R.string.cc_theme_classic), getString(R.string.cc_theme_retro)),
                themes.indexOf(themeSel).coerceAtLeast(0)
            ) { themeSel = themes[it] }
        }
        showWidgetSettings(WidgetType.CLIMATE_CONTROL, list, 1400) {
            settingsStore.setOption(widgetId, ClimateControlView.OPT_THEME, themeSel)
            saveTile()
            settingsStore.setOption(
                widgetId, ClimateControlView.OPT_ORDER, order.joinToString(",") { it.key }
            )
            settingsStore.setOption(
                widgetId, ClimateControlView.OPT_ITEMS,
                order.filter { it in checked }.joinToString(",") { it.key }
            )
        }
    }

    /**
     * Настройки «Часов»: масштаб текста (×0.5 … ×2.0), жирный шрифт времени
     * и перекидной вид как у HTC (см. [ComboWidgetView.OPT_SCALE]).
     */
    private fun showClockSettings(widgetId: String) {
        val scales = ComboWidgetView.SCALES
        var selected = scales.indexOf(
            settingsStore.getOption(widgetId, ComboWidgetView.OPT_SCALE, ComboWidgetView.DEFAULT_SCALE)
        ).coerceAtLeast(0)
        var bold = ComboWidgetView.boldValue(settingsStore, widgetId)
        var flip = ComboWidgetView.styleValue(settingsStore, widgetId) == ComboWidgetView.STYLE_FLIP
        var invert = ComboWidgetView.invertValue(settingsStore, widgetId)
        val list = SettingsList(this, theme)
            .section(getString(R.string.widget_clock))
            .segmented(
                R.drawable.ic_text_size, getString(R.string.clock_scale), null,
                scales.map { "×$it" }, selected
            ) { selected = it }
            .switch(R.drawable.ic_bold, getString(R.string.clock_bold), null, bold) { bold = it }
            .switch(R.drawable.ic_flip, getString(R.string.clock_flip), null, flip) { flip = it }
            .switch(R.drawable.ic_contrast, getString(R.string.clock_flip_invert_t),
                getString(R.string.clock_flip_invert_s), invert) { invert = it }
            .column()
        val saveTile = list.tileSection(widgetId)
        showWidgetSettings(WidgetType.COMBO, list, 1400) {
            settingsStore.setOption(widgetId, ComboWidgetView.OPT_SCALE, scales[selected])
            settingsStore.setOption(widgetId, ComboWidgetView.OPT_BOLD, if (bold) "1" else "0")
            settingsStore.setOption(
                widgetId, ComboWidgetView.OPT_STYLE,
                if (flip) ComboWidgetView.STYLE_FLIP else ComboWidgetView.STYLE_DIGITAL
            )
            settingsStore.setOption(widgetId, ComboWidgetView.OPT_INVERT, if (invert) "1" else "0")
            saveTile()
        }
    }

    /**
     * Настройки виджета «Автомобиль»: какие метрики показывать и в каком
     * порядке (топливо / аккумулятор / двигатель / запас хода).
     * Видимость — [CarWidgetView.OPT_ITEMS] (пусто = все),
     * порядок всех метрик — [CarWidgetView.OPT_ORDER].
     */
    private fun showCarSettings(widgetId: String) {
        val labelIds = mapOf(
            "fuel" to R.string.car_item_fuel,
            "batt" to R.string.car_item_batt,
            "eng" to R.string.car_item_eng,
            "range" to R.string.car_item_range
        )
        val icons = mapOf(
            "fuel" to R.drawable.ic_fuel,
            "batt" to R.drawable.ic_battery,
            "eng" to R.drawable.ic_thermo,
            "range" to R.drawable.ic_range
        )
        val orderRaw = settingsStore.getOption(widgetId, CarWidgetView.OPT_ORDER, "")
        val savedOrder = orderRaw.split(',')
            .mapNotNull { it.trim().takeIf { s -> s in CarWidgetView.ITEM_KEYS } }
            .distinct()
        val order = (if (savedOrder.isEmpty()) CarWidgetView.ITEM_KEYS
        else savedOrder + CarWidgetView.ITEM_KEYS.filter { it !in savedOrder })
            .toMutableList()
        val itemsRaw = settingsStore.getOption(widgetId, CarWidgetView.OPT_ITEMS, "")
        // Пусто = по умолчанию показываем всё — в диалоге все отмечены.
        val checked = (if (itemsRaw.isEmpty()) order
        else order.filter { key ->
            itemsRaw.split(',').any { it.trim() == key }
        }).toMutableSet()
        val list = SettingsList(this, theme)
            .section(getString(R.string.car_items_section))
            .reorder(order, checked, { getString(labelIds.getValue(it)) }, { icons[it] ?: 0 })
            .column()
        val saveTile = list.tileSection(widgetId)
        showWidgetSettings(WidgetType.CAR, list, 1400) {
            settingsStore.setOption(widgetId, CarWidgetView.OPT_ORDER, order.joinToString(","))
            settingsStore.setOption(
                widgetId, CarWidgetView.OPT_ITEMS,
                order.filter { it in checked }.joinToString(",")
            )
            saveTile()
        }
    }

    /**
     * Настройки «Бортового компьютера»: какие колонки показывать и в каком
     * порядке (расход / пробег / ср. скорость / моточасы всего, в движении,
     * на холостом ходу), строка приборки внизу и сброс моточасов (сразу,
     * с подтверждением — не ждёт «Готово»).
     */
    private fun showTripSettings(widgetId: String) {
        val labelIds = mapOf(
            TripWidgetView.ITEM_CONS to R.string.trip_avg_cons,
            TripWidgetView.ITEM_DIST to R.string.trip_dist,
            TripWidgetView.ITEM_SPEED to R.string.trip_avg_speed,
            TripWidgetView.ITEM_HOURS to R.string.trip_engine_hours,
            TripWidgetView.ITEM_MOVING to R.string.trip_item_hours_moving,
            TripWidgetView.ITEM_IDLE to R.string.trip_item_hours_idle
        )
        val icons = mapOf(
            TripWidgetView.ITEM_CONS to R.drawable.ic_fuel,
            TripWidgetView.ITEM_DIST to R.drawable.ic_range,
            TripWidgetView.ITEM_SPEED to R.drawable.ic_gauge,
            TripWidgetView.ITEM_HOURS to R.drawable.ic_clock,
            TripWidgetView.ITEM_MOVING to R.drawable.ic_clock,
            TripWidgetView.ITEM_IDLE to R.drawable.ic_clock
        )
        val order = TripWidgetView.order(settingsStore, widgetId).toMutableList()
        val checked = TripWidgetView.checked(settingsStore, widgetId).toMutableSet()
        var footer = settingsStore.getOption(widgetId, TripWidgetView.OPT_FOOTER, "1") != "0"
        val list = SettingsList(this, theme)
            .section(getString(R.string.car_items_section))
            .reorder(order, checked, { getString(labelIds.getValue(it)) }, { icons[it] ?: 0 })
            .column()
            .section(getString(R.string.trip_engine_hours))
            .nav(
                R.drawable.ic_rotate, getString(R.string.trip_engine_reset),
                getString(R.string.trip_engine_reset_s),
                "%.1f %s".format(
                    java.util.Locale("ru"),
                    TripTracker.engineHours(this).totalMillis.coerceAtLeast(0L) / 3_600_000.0,
                    getString(R.string.trip_journal_h)
                )
            ) { TripWidgetView.confirmResetEngineHours(this) }
        val saveTile = list.tileSection(widgetId) {
            switch(
                R.drawable.ic_journal, getString(R.string.trip_footer_t),
                getString(R.string.trip_footer_s), footer
            ) { footer = it }
        }
        showWidgetSettings(WidgetType.TRIP, list, 1400) {
            settingsStore.setOption(widgetId, TripWidgetView.OPT_ORDER, order.joinToString(","))
            settingsStore.setOption(
                widgetId, TripWidgetView.OPT_ITEMS,
                order.filter { it in checked }.joinToString(",")
            )
            settingsStore.setOption(widgetId, TripWidgetView.OPT_FOOTER, if (footer) "1" else "0")
            saveTile()
        }
    }

    /**
     * Настройка виджета «OBD (ELM)»: строки-роутеры к выбору адаптера
     * и JSON-профиля, затем состав показателей (мультивыбор). Состав
     * хранится per-widget ([WidgetSettingsStore]), адаптер и профиль —
     * глобально ([ElmHub]): адаптер в машине один, а виджетов OBD может
     * быть несколько.
     */
    private fun showElmSettings(widgetId: String) {
        ElmHub.start(this) // подхватить сохранённый профиль, если виджет ещё не создавался
        val metrics = ElmWidgetView.items(this)
        val profileName = ElmHub.profile()?.name ?: getString(R.string.elm_profile_none)
        val selected = ElmWidgetView.selectedIds(settingsStore, widgetId).toMutableSet()
        lateinit var dialogSave: () -> Unit
        var dialog: android.app.Dialog? = null
        // Переход к адаптеру/профилю закрывает диалог — сначала сохраняем
        // то, что уже отметили, чтобы не потерять.
        fun leave(next: () -> Unit) {
            dialogSave()
            dialog?.dismiss()
            next()
        }
        val list = SettingsList(this, theme)
            .section(getString(R.string.elm_sec_connection))
            .nav(
                R.drawable.ic_bluetooth, getString(R.string.elm_adapter_t), ElmHub.address(),
                ElmHub.name() ?: getString(R.string.elm_adapter_none)
            ) { leave { showElmAdapterDialog() } }
            .nav(R.drawable.ic_book, getString(R.string.elm_profile_t), null, profileName) {
                leave { showElmProfileDialog(widgetId) }
            }
        val saveTile = list.tileSection(widgetId)
        list.column().section(getString(R.string.elm_sec_metrics))
        metrics.forEach { m ->
            list.switch(R.drawable.ic_obd, m.label, null, m.key in selected) { on ->
                if (on) selected.add(m.key) else selected.remove(m.key)
            }
        }
        dialogSave = {
            // Ничего не отмечено — сохраняем маркер «-»: пустая опция
            // означала бы «ещё не настраивали» (дефолт).
            val items = metrics.map { it.key }.filter { it in selected }
            settingsStore.setOption(
                widgetId,
                ElmWidgetView.OPT_ITEMS,
                if (items.isEmpty()) ElmWidgetView.NONE_ITEMS else items.joinToString(",")
            )
            saveTile()
        }
        val item = pickerItems().firstOrNull { it.type == WidgetType.ELM }
        dialog = DashDialog.Builder(this)
            .setIcon(item?.iconRes ?: R.drawable.ic_obd)
            .setTitle(item?.let { getString(it.labelRes) } ?: getString(R.string.set_elm_title))
            .setWidthDp(1400)
            .setView(list.view)
            .setPositiveButton(R.string.edit_done) { _, _ ->
                dialogSave()
                buildWidgets(readSpecs())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    /**
     * JSON-профиль OBD ([ProfileStore]): стандартный OBD-II, встроенные
     * (assets/profiles) и загруженные профили, загрузка нового — системным
     * выбором файла или поиском *.json на флешке/в памяти (на ГУ
     * DocumentsUI может не быть). Профиль глобальный: после выбора
     * виджеты пересобираются, и снова открываются настройки [widgetId] —
     * состав показателей теперь из профиля.
     */
    private fun showElmProfileDialog(widgetId: String) {
        val entries = ProfileStore.list(this)
        val current = ElmHub.profileId()
        val rows = ArrayList<String>()
        rows += getString(R.string.elm_profile_none) + if (current == null) "  ✓" else ""
        entries.forEach { e ->
            rows += buildString {
                append(e.name)
                append(" · ").append(getString(if (e.builtIn) R.string.elm_profile_builtin else R.string.elm_profile_loaded))
                if (e.error != null) append(" · ").append(getString(R.string.elm_profile_broken, e.error))
                if (e.id == current) append("  ✓")
            }
        }
        rows += getString(R.string.elm_profile_open)
        rows += getString(R.string.elm_profile_scan)
        val loaded = entries.filter { !it.builtIn }
        val builder = DashDialog.Builder(this)
            .setTitle(R.string.elm_profile_title)
            .setItems(rows.toTypedArray()) { _, which ->
                when (which) {
                    0 -> chooseElmProfile(null, widgetId)
                    in 1..entries.size -> chooseElmProfile(entries[which - 1].id, widgetId)
                    entries.size + 1 -> openElmProfileFile(widgetId)
                    else -> scanElmProfiles(widgetId)
                }
            }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> showElmSettings(widgetId) }
        if (loaded.isNotEmpty()) {
            builder.setNeutralButton(R.string.elm_profile_delete) { _, _ ->
                DashDialog.Builder(this)
                    .setTitle(R.string.elm_profile_delete)
                    .setItems(loaded.map { it.name }.toTypedArray()) { _, i ->
                        val wasSelected = ElmHub.profileId() == loaded[i].id
                        ProfileStore.delete(this, loaded[i].id)
                        if (wasSelected) chooseElmProfile(null, widgetId) else showElmProfileDialog(widgetId)
                    }
                    .setNegativeButton(R.string.dialog_cancel) { _, _ -> showElmProfileDialog(widgetId) }
                    .showFullScreen()
            }
        }
        builder.showFullScreen()
    }

    /** Применить профиль (null — стандартный OBD-II), пересобрать виджеты и вернуться в настройки. */
    private fun chooseElmProfile(id: String?, widgetId: String) {
        try {
            ElmHub.setProfile(this, id)
        } catch (e: Exception) {
            elmMessage(getString(R.string.elm_profile_load_failed, e.message ?: e.javaClass.simpleName))
            return
        }
        val p = ElmHub.profile()
        val text = when {
            p == null -> getString(R.string.elm_profile_picked, getString(R.string.elm_profile_none))
            p.warnings.isEmpty() -> getString(R.string.elm_profile_picked, p.name)
            else -> getString(R.string.elm_profile_picked_warn, p.name, p.params.size, p.warnings.size)
        }
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        buildWidgets(readSpecs())
        showElmSettings(widgetId)
    }

    /** Виджет, для которого открыт выбор файла/запрошен доступ к памяти. */
    private var elmProfileWidgetId: String? = null

    /** Системный выбор файла (если на ГУ есть DocumentsUI). */
    private fun openElmProfileFile(widgetId: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                android.content.Intent.EXTRA_MIME_TYPES,
                arrayOf("application/json", "text/plain", "application/octet-stream")
            )
        }
        if (intent.resolveActivity(packageManager) == null) {
            elmMessage(getString(R.string.elm_profile_no_picker))
            return
        }
        elmProfileWidgetId = widgetId
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_ELM_JSON)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_ELM_JSON) return
        val widgetId = elmProfileWidgetId ?: return
        if (resultCode != RESULT_OK) {
            showElmProfileDialog(widgetId)
            return
        }
        val uri = data?.data ?: return
        try {
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment ?: "profile.json"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException(getString(R.string.elm_profile_not_opened))
            if (bytes.size > ProfileStore.MAX_BYTES) throw IllegalArgumentException(getString(R.string.elm_profile_too_big))
            chooseElmProfile(ProfileStore.import(this, name, bytes.toString(Charsets.UTF_8)), widgetId)
        } catch (e: Exception) {
            elmMessage(getString(R.string.elm_profile_load_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    /** Поиск *.json на флешках и во внутренней памяти; нужен доступ к памяти. */
    private fun scanElmProfiles(widgetId: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            elmProfileWidgetId = widgetId
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_ELM_STORAGE)
            return
        }
        val files = ProfileStore.scanStorage()
        if (files.isEmpty()) {
            elmMessage(getString(R.string.elm_profile_scan_empty))
            return
        }
        DashDialog.Builder(this)
            .setTitle(R.string.elm_profile_scan_title)
            .setItems(files.map { "${it.name}  ·  ${it.parent}" }.toTypedArray()) { _, i ->
                try {
                    chooseElmProfile(ProfileStore.importFile(this, files[i]), widgetId)
                } catch (e: Exception) {
                    elmMessage("${files[i].name}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> showElmProfileDialog(widgetId) }
            .showFullScreen()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SPECTRUM_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                // Пересоздать вью — SpectrumHub переоткроет Visualizer.
                buildWidgets(readSpecs())
            } else {
                Toast.makeText(this, R.string.spectrum_no_mic_permission, Toast.LENGTH_LONG).show()
            }
            return
        }
        if (requestCode != REQ_ELM_STORAGE) return
        val widgetId = elmProfileWidgetId ?: return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) scanElmProfiles(widgetId)
        else elmMessage(getString(R.string.elm_profile_no_storage))
    }

    private fun elmMessage(text: String) {
        DashDialog.Builder(this)
            .setTitle(R.string.elm_profile_title)
            .setMessage(text)
            .setNegativeButton(R.string.dialog_close, null)
            .showFullScreen()
    }

    /**
     * Выбор спаренного Bluetooth-адаптера ELM327. Выбор сохраняется
     * глобально ([ElmHub]), а не per-widget — адаптер в машине один.
     */
    @SuppressLint("MissingPermission")
    private fun showElmAdapterDialog() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val paired = manager.adapter?.takeIf { it.isEnabled }
            ?.bondedDevices?.sortedBy { it.name ?: "" }?.toList() ?: emptyList()
        if (paired.isEmpty()) {
            Toast.makeText(this, R.string.elm_paired_none, Toast.LENGTH_SHORT).show()
            return
        }
        val current = ElmHub.address()?.let { addr -> getString(R.string.elm_current, addr, ElmHub.name() ?: "?") } ?: ""
        val items = paired.map { "${it.name ?: getString(R.string.elm_no_name)} — ${it.address}" }
            .toTypedArray()
        DashDialog.Builder(this)
            .setTitle(if (current.isEmpty()) getString(R.string.set_elm_adapter_title) else current)
            .setItems(items) { _, which ->
                val device = paired[which]
                ElmHub.setDevice(this, device.address, device.name)
                Toast.makeText(
                    this,
                    getString(R.string.elm_picked, device.name ?: getString(R.string.elm_no_name)),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showFullScreen()
    }

    private fun isAppInstalled(pkg: String): Boolean {
        return try {
            packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * «На приборку»: открывает навигацию на дисплее приборки.
     * Пакет — первое установленное навигационное приложение.
     */
    private fun openNavOnCluster() {
        val clusterId = NavApps.getClusterDisplayId(this)
        if (clusterId == null) {
            Toast.makeText(this, R.string.msg_no_cluster, Toast.LENGTH_SHORT).show()
            return
        }
        val pkg = firstNavPackage()
        if (pkg == null) {
            Toast.makeText(this, R.string.msg_no_nav, Toast.LENGTH_SHORT).show()
            return
        }
        com.dash.headunit.location.LocationHelper(this).requestFix { fix ->
            val lat = fix?.latitude ?: com.dash.headunit.location.LocationHelper.FALLBACK_LAT
            val lon = fix?.longitude ?: com.dash.headunit.location.LocationHelper.FALLBACK_LON
            try {
                val uri = android.net.Uri.parse("geo:$lat,$lon?q=$lat,$lon")
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW, uri
                ).apply {
                    setPackage(pkg)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val options = android.app.ActivityOptions.makeBasic()
                options.launchDisplayId = clusterId
                startActivity(intent, options.toBundle())
            } catch (e: Exception) {
                Toast.makeText(this, R.string.msg_no_nav, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Пакет навигации: первое установленное из известных. */
    private fun firstNavPackage(): String? {
        return NavApps.NAV_APPS.firstOrNull { isAppInstalled(it.second) }?.second
    }

    /**
     * Настройки виджета-приложения: выбор из всех приложений лаунчера
     * (виден только первое после установки — дальше список полный).
     */
    private fun showAppSettings(widgetId: String) {
        val current = settingsStore.getOption(
            widgetId, AppWidgetView.OPT_PKG, ""
        )
        val apps = launchableApps()
        // Пары (label, pkg); себя исключаем — встраивать самих себя нельзя.
        val options = mutableListOf(
            getString(R.string.app_none_selected) to ""
        ) + apps.filter { it.first != packageName }
            .map { it.second to it.first }
        var selected = options.indexOfFirst { it.second == current }.takeIf { it >= 0 } ?: 0
        val list = SettingsList(this, theme)
            .section(getString(R.string.set_app_title))
            .choices(options.map { it.first }, selected, R.drawable.ic_apps) { selected = it }
            .column()
        val saveTile = list.tileSection(widgetId)
        showWidgetSettings(WidgetType.APP, list, 1400) {
            settingsStore.setOption(widgetId, AppWidgetView.OPT_PKG, options[selected].second)
            saveTile()
        }
    }

    /**
     * Настройки виджета «Программы»: какие программы показывать и в каком
     * порядке. Список — все запускаемые приложения ГУ; выбранные идут
     * первыми (в выбранном порядке), остальные — по алфавиту. Переключатель
     * в строке показывает/прячет программу, стрелки меняют порядок.
     */
    private fun showAppsSettings(widgetId: String) {
        // Пары (pkg, label); себя исключаем — запускать самих себя незачем.
        val all = launchableApps()
            .filter { it.first != packageName }
            .associate { it.first to it.second }
        val chosen = AppsWidgetView.selected(settingsStore, widgetId).filter { it in all }
        val order = (chosen + all.keys.filter { it !in chosen }).toMutableList()
        val checked = chosen.toMutableSet()
        val list = SettingsList(this, theme)
            .section(getString(R.string.apps_section))
            .reorder(order, checked, { all[it] ?: it }) { key, on ->
                if (!on) return@reorder false
                // Включённая программа — в конец выбранных, а не в общий
                // список: так её сразу видно и можно переставить стрелками.
                order.remove(key)
                val at = order.indexOfFirst { it !in checked }
                order.add(if (at < 0) order.size else at, key)
                true
            }
            .column()
        val saveTile = list.tileSection(widgetId)
        showWidgetSettings(WidgetType.APPS, list, 1400) {
            settingsStore.setOption(
                widgetId, AppsWidgetView.OPT_APPS,
                order.filter { it in checked }.joinToString(",")
            )
            saveTile()
        }
    }

    /** Все запускаемые приложения (label + package), по алфавиту. */
    private fun launchableApps(): List<Pair<String, String>> {
        return try {
            val main = android.content.Intent(
                android.content.Intent.ACTION_MAIN
            ).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            packageManager.queryIntentActivities(main, 0)
                .mapNotNull { ri ->
                    val label = ri.loadLabel(packageManager)?.toString()
                    val pkg = ri.activityInfo?.packageName
                    if (label.isNullOrBlank() || pkg.isNullOrBlank()) null
                    else pkg to label
                }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun resetLayout() {
        store.reset()
        buildWidgets(store.defaultLayout())
    }

    private fun persistLayout() {
        store.save(readSpecs())
    }

    private fun readSpecs(): List<WidgetSpec> {
        val specs = mutableListOf<WidgetSpec>()
        for (i in 0 until dashboard.childCount) {
            val frame = dashboard.getChildAt(i) as? WidgetFrame ?: continue
            val lp = frame.layoutParams as? DashboardGridLayout.LayoutParams ?: continue
            specs.add(
                WidgetSpec(
                    widgetId = frame.widgetId,
                    type = frame.widgetType,
                    x = lp.cellX, y = lp.cellY, w = lp.cellW, h = lp.cellH
                )
            )
        }
        return specs
    }

    // ---------- геолокация ----------

    private fun ensureLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED &&
            coarse != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                REQ_LOCATION
            )
        }
    }

    companion object {
        /** onStop в этот срок после запуска навигатора под себя — не уход с экрана. */
        private const val UNDERLAY_GRACE_MS = 5000L
        private const val CLUSTER_OFF = 0
        private const val CLUSTER_YANDEX = 1
        private const val CLUSTER_OURS = 2
        private const val YANDEX_FOCUS_BACK_MS = 2000L
        /** Двигатель не работал дольше — считаем новым пуском (стоянка/сон ГУ). */
        private const val ENGINE_RESTART_GAP_MS = 60_000L

        private const val REQ_LOCATION = 1001
        private const val REQ_ELM_JSON = 1002
        private const val REQ_ELM_STORAGE = 1003
        private const val REQ_SPECTRUM_AUDIO = 1004

        /** Шаг ползунка яркости заставки, %. */

        /** Строки-роутеры в начале настроек OBD: адаптер и профиль. */

        /** Опция «Спидометра»: вид (см. [showSpeedSettings]). */
        private const val OPT_SPEED_STYLE = "style"
        private const val SPEED_RETRO = "retro"
        private const val SPEED_VAZ = "vaz"
        private const val SPEED_DIGITAL = "digital"

        /** Опция «Климата»: вид (см. [showClimateSettings]). */
        private const val OPT_CLIMATE_STYLE = "style"
        private const val CLIMATE_TEXT = "text"
        private const val CLIMATE_ICONS = "icons"

        /** Сетка выбора виджета: колонок в ряду и ширина диалога под них. */
        private const val PICKER_COLUMNS = 7
        private const val PICKER_WIDTH_DP = 1140

        /**
         * Временно скрытые виджеты в диалоге «Добавить блок»: карточки не
         * показываем. Уже стоящие на главной виджеты убираются крестиком
         * прямо на дашборде.
         */
        private val HIDDEN_PICKER_TYPES = setOf(
            WidgetType.REFERENCE, WidgetType.BRIGHTNESS, WidgetType.BUTTONS,
            WidgetType.CLIMATE
        )

        /**
         * Карточка выбора виджета в полном размере: иконка, кегль подписи и
         * отметки, отступы. Сетка ужимает их под сторону ячейки
         * ([PickerGrid.Cell]), поэтому полный размер считаем с запасом —
         * он и должен влезать в квадрат.
         */
        private const val PICKER_ICON_DP = 56
        private const val PICKER_LABEL_SP = 18f
        private const val PICKER_NOTE_SP = 13f
        private const val PICKER_PAD_H_DP = 8
        private const val PICKER_PAD_V_DP = 6
        private const val PICKER_LABEL_GAP_DP = 6
        private const val PICKER_NOTE_GAP_DP = 2

        /** Виджеты со своим ретро-видом в настройках — общий [RetroLook] им не нужен. */
        private val OWN_RETRO = setOf(
            WidgetType.SPEED, WidgetType.CLIMATE, WidgetType.CLIMATE_CONTROL, WidgetType.HEAT, WidgetType.RADIO
        )
    }
}
