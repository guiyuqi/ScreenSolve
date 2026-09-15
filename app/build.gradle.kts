plugins {
    id("com.android.application")
}

android {
    namespace = "com.fragranstea.screensolve"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fragranstea.screensolve"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-m1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // AGP 9 内置 Kotlin：jvmTarget 默认跟随 targetCompatibility，无需再配 kotlinOptions
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    // 注意版本上限：core-ktx 1.19.0 的 AAR 元数据要求 minCompileSdk=37，
    // 而本机 SDK 只有到 android-36（android-37 只有 37.1/37.2 版本化平台）。
    // 所以锁 1.18.0（minCompileSdk=36）。
    implementation("androidx.core:core-ktx:1.18.0")      // ContextCompat / ServiceCompat
    implementation("androidx.appcompat:appcompat:1.8.0") // AppCompatActivity + DayNight 主题（minCompileSdk=34）
}
