import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.dash.headunit"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    // Ключ OpenWeatherMap (запасной источник погоды) берём из local.properties
    // (openweathermap.key), чтобы не хранить его в git.
    // Пример: см. local.properties.example.
    val owmKey: String = try {
        val props = Properties()
        val localFile = rootProject.file("local.properties")
        if (localFile.exists()) {
            localFile.inputStream().use { props.load(it) }
        }
        (props.getProperty("openweathermap.key") ?: "").trim()
    } catch (e: Exception) {
        ""
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.dash.headunit"
        // ГУ 703002573AA работает на Android 9 (API 28).
        // targetSdk держим = 28 осознанно, как у VDLauncher (у них 27):
        // скрытый ActivityView (встраивание приложений) доступен только
        // через greylist старых платформ; на новых API он в чёрном списке.
        // Приложение только для сайдлоада на ГУ, требований Play нет.
        minSdk = 28
        targetSdk = 28
        versionCode = 6
        versionName = "1.5"

        buildConfigField("String", "OPENWEATHER_KEY", "\"$owmKey\"")
    }

    // Подпись релиза debug-ключом: приложение сайдлоадное, а APK должен
    // обновляться поверх уже установленной на ГУ сборки (та же подпись —
    // сохраняются авторизация adb и раскладка виджетов). Ключ — стандартный
    // debug (alias androiddebugkey, пароль android), поэтому лежит в репе,
    // чтобы локальная и CI-сборки давали одинаковую подпись.
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Два варианта поставки:
    //   dev  — как сейчас: все виджеты (для разработки);
    //   prod — продовый: только часы, погода, автомобиль, музыка,
    //          навигатор, бортовой компьютер, спидометр.
    // Набор продовых виджетов — WidgetAvailability (флаг PROD_BUILD).
    flavorDimensions += "channel"
    productFlavors {
        create("dev") {
            dimension = "channel"
            buildConfigField("boolean", "PROD_BUILD", "false")
        }
        create("prod") {
            dimension = "channel"
            buildConfigField("boolean", "PROD_BUILD", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // targetSdk 28 выбран осознанно (ГУ Android 9, скрытый ActivityView),
    // Play не используется — гасим требование «targetSdk ≥ 33» в release-lint.
    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}

// Имя APK: tenet_t7_dash-<flavor>-<buildType>.apk вместо app-….apk.
base {
    archivesName = "tenet_t7_dash"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.json.java)
}
