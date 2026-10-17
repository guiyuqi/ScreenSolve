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
        versionCode = 5
        versionName = "0.5.0-m4"

        // 只保留真机需要的 ABI（ML Kit 会给每个 ABI 各带一份 10MB 级原生库）
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // 只打包中英文资源：AndroidX / Material 自带几十种语言的字符串，不裁的话白白占体积
        resourceConfigurations += listOf("zh", "en")
    }

    /**
     * 两个版本，二选一安装：
     * - lite（默认）：只有视觉直传通道，APK 约 2.5 MB —— 日常用这个，启动快、体积小
     * - full：额外带端侧离线 OCR（ML Kit 中文），APK 约 15 MB —— 需要离线出字/不发原图时才装
     *
     * 差异只由 src/lite、src/full 两个源集各自提供的 OcrRecognizer 决定，
     * 业务代码完全共用，不存在两套维护分支。
     */
    flavorDimensions += "ocr"
    productFlavors {
        create("lite") {
            dimension = "ocr"
        }
        create("full") {
            dimension = "ocr"
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

    // Markdown 用 Markwon（0.13 MB）；公式用 jlatexmath（0.62 MB）直接画成 Drawable，
    // 不引 WebView、也不用 Markwon 那个不可靠的 ext-latex 插件
    implementation("io.noties.markwon:core:4.6.2")
    implementation("ru.noties:jlatexmath-android:0.2.0")

    // 端侧中文 OCR 只在 full 版本里（bundled 模型，不依赖 Google Play 服务）
    "fullImplementation"("com.google.mlkit:text-recognition-chinese:16.0.1")

    // 纯 JVM 单元测试
    testImplementation("junit:junit:4.13.2")
}
