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
        versionCode = 4
        versionName = "1.3"

        buildConfigField("String", "OPENWEATHER_KEY", "\"$owmKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
