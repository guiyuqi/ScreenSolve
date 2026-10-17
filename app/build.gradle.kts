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
        versionCode = 4
        versionName = "0.4.0-m4"

        // 只保留真机需要的 ABI。ML Kit 的中文 OCR 会给每个 ABI 各带一份
        // libmlkit_google_ocr_pipeline.so（arm64 10.6MB / x86 11MB / x86_64 11.1MB / v7a 6.5MB），
        // 不裁剪的话 APK 会从 8.5MB 涨到 54.8MB。
        // 以后要在模拟器（x86_64）或老 32 位机（armeabi-v7a）上跑，把对应 ABI 加回来即可。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
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

    // 端侧中文 OCR：bundled 版（模型打进 APK，约 +1.9MB），不依赖 Google Play 服务
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // 纯 JVM 单元测试：LaTeX/markdown 清洗器这类字符串逻辑必须能在本机验证，不必上真机
    testImplementation("junit:junit:4.13.2")
}
