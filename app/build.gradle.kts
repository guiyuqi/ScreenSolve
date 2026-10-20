import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
}

/**
 * release 签名配置从仓库根部的 keystore.properties 读（该文件与 keystore/ 都不进 git）。
 * 没有这个文件时 release 包不签名 —— CI 或别人 clone 下来照样能编译，只是出不了可发布包。
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

android {
    namespace = "com.fragranstea.screensolve"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fragranstea.screensolve"
        minSdk = 29
        targetSdk = 36
        versionCode = 14
        versionName = "1.1.3"

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

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 真机调试用：./gradlew assembleLiteDebug -PreleaseSignedDebug
            // 让 debug 包用**发布证书**签名，于是能覆盖安装到已装 release 的手机上（API Key / 历史不丢），
            // 同时 is_debug_build 仍为 true，渲染层调试日志照开。
            if (project.hasProperty("releaseSignedDebug") && keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        release {
            // 有 keystore.properties 就签，没有就留空（不挡住编译）
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }

            // 冒烟测试开关：./gradlew assembleFullRelease -PsmokeTest
            // 会给包名加 .smoke 后缀，于是能和已装的 debug 包共存 ——
            // 目的是验证"R8 混淆后的 release 包"本身能不能启动，不用先卸载丢数据。
            if (project.hasProperty("smokeTest")) {
                applicationIdSuffix = ".smoke"
                versionNameSuffix = "-smoke"
            }
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

    // 拍照模式用 CameraX 自建相机：**不经过系统相机 App**，
    // 照片只进内存，绝不写入相册（用户明确要求），也没有 OEM 相机那套确认页
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

    // Markdown 用 Markwon（0.13 MB）；公式用 jlatexmath（0.62 MB）直接画成 Drawable，
    // 不引 WebView、也不用 Markwon 那个不可靠的 ext-latex 插件
    implementation("io.noties.markwon:core:4.6.2")
    implementation("ru.noties:jlatexmath-android:0.2.0")

    // 端侧中文 OCR 只在 full 版本里（bundled 模型，不依赖 Google Play 服务）
    "fullImplementation"("com.google.mlkit:text-recognition-chinese:16.0.1")

    // 纯 JVM 单元测试
    testImplementation("junit:junit:4.13.2")
}
