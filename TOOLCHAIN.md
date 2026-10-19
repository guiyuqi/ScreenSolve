# Android 工具链安装记录（全局环境）

**验证状态：端到端跑通** —— 一个最小 Kotlin Android 项目成功构建出已签名的 APK（`compileSdk 36 / targetSdk 36`）。

安装位置选在 `/opt/android-sdk`，属于**全局**环境：所有项目、所有 shell、所有用户都能直接用，不需要每个项目单独配 SDK。

---

## 一、装了什么、装在哪

| 组件 | 版本 | 路径 | 来源 |
|---|---|---|---|
| JDK | OpenJDK **21.0.12**（21.0.12+8-1-26.04） | `/usr/lib/jvm/java-21-openjdk-amd64` | `apt`（你执行） |
| **Android SDK Root** | — | **`/opt/android-sdk`**（总占用 1.2 G） | 本目录由你 `sudo` 创建并 `chown` 给 `fragranstea` |
| cmdline-tools | 23.0.0（`android` CLI 1.0.16261425） | `/opt/android-sdk/cmdline-tools/latest` | `commandlinetools-linux-16111833_latest.zip` |
| platform-tools | **37.0.1**（adb 1.0.41） | `/opt/android-sdk/platform-tools` | 同上 |
| platforms | **android-34 / 35 / 36** | `/opt/android-sdk/platforms` | `sdkmanager` |
| build-tools | **35.0.1 / 36.0.0 / 36.1.0 / 37.0.0** | `/opt/android-sdk/build-tools` | `sdkmanager` |
| licenses | 已全部接受 | `/opt/android-sdk/licenses` | `sdkmanager --licenses` |

> **关键设计点**：`/opt/android-sdk` 的属主是 `fragranstea` 而不是 root。所以以后**加装 SDK 组件（platform、build-tools、NDK…）都不需要 sudo**，我可以直接装。
>
> 未安装（有意）：`emulator` 与系统镜像（WSL 无 `/dev/kvm`，装了也跑不起来，纯占空间）、NDK（本项目不需要）。

`/home/fragranstea/android-stage/` 里还留着两个安装包（共 182 MB：cmdline-tools + platform-tools），如需给其他机器用可以拷走，否则可以删。

---

## 二、环境变量

### 全局（所有用户、登录 shell）
`/etc/profile.d/android-sdk.sh`（你执行的那段 `sudo tee` 写入）：

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

### 补充（你的交互式 shell）
`~/.bashrc` 末尾追加了一段：把**最新 build-tools** 也加进 `PATH`，这样 `aapt2` / `apksigner` / `zipalign` 能直接敲。

### 验证方式（已实测通过）
```bash
bash -lc 'which java adb sdkmanager'   # 登录 shell：java / adb / sdkmanager 都在
bash -ic 'which aapt2 apksigner zipalign'  # 交互式 shell：build-tools 37.0.0 下的三个工具
```

> ⚠️ **注意**：`/etc/profile.d` 只对**登录 shell** 生效。非交互式执行（脚本、`bash -c`、CI）拿不到 `JAVA_HOME`，需要在脚本里显式 `export`。这也是我后续写构建脚本时会显式导出的原因。

---

## 三、已验证可用的构建配置（可直接复制）

| 项 | 值 | 说明 |
|---|---|---|
| JDK | 21 | Gradle 9 需要 JDK 17+ |
| Gradle | **9.7.1** | AGP 9.4.0 **硬性要求 ≥ 9.6.0** |
| AGP | **9.4.0** | Maven 上最新稳定版 |
| Kotlin | **不声明插件** | AGP 9 起 Kotlin 内置（见第五节） |
| compileSdk / targetSdk | **36** / 36 | Android 16 |
| minSdk | 29 | Android 10 |
| 构建产物 | `app-debug.apk` 6.4 MB | V2 签名（debug 证书） |

最小可用 `app/build.gradle.kts`（**这就是实测通过的样子**）：

```kotlin
plugins {
    id("com.android.application")   // 注意：没有 kotlin 插件
}

android {
    namespace = "com.fragranstea.screensolve"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.fragranstea.screensolve"
        minSdk = 29
        targetSdk = 36
        versionCode = 1        // 示例脚手架，本项目实际已到 13 / 1.1.2
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21   // Kotlin 的 jvmTarget 默认跟随这个值
    }
}
```

根 `build.gradle.kts`：

```kotlin
plugins {
    id("com.android.application") version "9.4.0" apply false
}
```

`gradle.properties`（本机 7.4 G 内存，必须限制）：

```properties
org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8
org.gradle.workers.max=4
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
```

`gradle/wrapper/gradle-wrapper.properties`（**必须走镜像**，见第四节）：

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://mirrors.huaweicloud.com/gradle/gradle-9.7.1-bin.zip
networkTimeout=180000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

---

## 四、⚠️ 网络限制与镜像（最重要的一节）

这台机器（WSL2，NAT 模式，无代理）对部分站点不可达，**直接影响构建**：

| 目标 | 状态 | 影响 |
|---|---|---|
| `github.com` | ❌ **超时** | 最致命的坑，见下 |
| `services.gradle.org` | ⚠️ 返回 307 | 它把发行版下载**重定向到 `github.com/gradle/gradle-distributions/releases/...`** → 于是 wrapper 报 `Connection timed out` |
| `raw.githubusercontent.com` | ✅ 200 | 意外地通！可以用来取 GitHub 上的单个文件（我们就是这样拿到 `gradlew` 和 `gradle-wrapper.jar`） |
| **`mirrors.huaweicloud.com/gradle/`** | ✅ **9.6 MB/s** | **Gradle 发行版镜像（推荐）** |
| `mirrors.cloud.tencent.com/gradle/` | ✅ 8.0 MB/s | 备用镜像 |
| `dl.google.com` | ✅ 200 | Android SDK 组件下载正常 |
| `maven.google.com` / `dl.google.com/dl/android/maven2` | ✅ 200 | AGP 与 AndroidX 依赖正常 |
| `repo1.maven.org` / `plugins.gradle.org` | ✅ 200 | 普通依赖与插件门户正常 |
| `jitpack.io` | ✅ 200 | 可用（但其构建依赖 GitHub，尽量别用 JitPack 库） |
| `developer.android.com` | ❌ 超时 | 官方文档不可直接访问 |
| **`developer.android.google.cn`** | ✅ **200** | **Google 中国镜像，Android 官方文档可正常查阅** ✅ |
| `archive.ubuntu.com` | ✅ 200 | apt 源正常 |
| `api.deepseek.com` | ✅ 401（无鉴权） | 目标 API 正常 |

### 结论
1. **Gradle 发行版必须用镜像**，不能依赖 `services.gradle.org` 的默认跳转。镜像已写进 wrapper 配置。
2. **Maven 依赖不需要镜像**，`dl.google.com` / `maven.google.com` / `repo1.maven.org` 全部直连可用，速度也够（5–9 MB/s）。
3. 查 Android 官方文档**用 `developer.android.google.cn`**（把 `developer.android.com` 的路径直接换域名即可）。另外 Google 官方文档在 `raw.githubusercontent.com/android/skills/...` 有镜像，也能用。
4. `kotl.in` 短链会跳向被墙的 `developer.android.com`，所以别依赖它。

---

## 五、AGP 9 的破坏性变化（本次实测踩到 2 个）

这几条是网上多数教程还停留在 AGP 8 时代、容易踩坑的地方，**已实测确认**：

### 1. Gradle 最低版本硬性提升到 9.6.0
```
Failed to apply plugin 'com.android.internal.version-check'.
> Minimum supported Gradle version is 9.6.0. Current version is 9.4.0.
```
→ 用 Gradle **9.7.1**（当前稳定版）解决。

### 2. Kotlin 支持内置，**不能再声明 Kotlin 插件**
```
Failed to apply plugin 'org.jetbrains.kotlin.android'.
> The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0.
```
AGP 9.0 起内置 Kotlin 编译器（构建中间产物目录就叫 `built_in_kotlinc`），配套变化：

| AGP 8 时代的写法 | AGP 9 的正确写法 |
|---|---|
| `plugins { id("org.jetbrains.kotlin.android") }` | **删掉**，什么都不用声明 |
| 在根 build 里 `id("org.jetbrains.kotlin.android") version "2.x" apply false` | **删掉** |
| `android { kotlinOptions { jvmTarget = "21" } }` | `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_21 } }`，或干脆不配（默认跟随 `compileOptions.targetCompatibility`） |
| `kotlin("kapt")` | 迁移到 KSP；实在不行用 `com.android.legacy-kapt`（版本跟 AGP 一致） |
| `kotlin.sourceSets` 加源码目录 | 只能用 `android.sourceSets` 的 `kotlin` 源集 |

### 3. `sdkmanager` 已被标记弃用
会打印：`The SDK Manager CLI tool (sdkmanager) is deprecated. Android CLI will be used instead.`
新命令是同一个目录下的 `android`（`android sdk ...`）。**旧的 `sdkmanager` 仍能正常工作**，而且新版连 `--licenses` 都不再需要了（提示 `The --licenses option is no longer needed`）。我后续装组件会优先用 `sdkmanager`，因为它语义明确且在脚本里更稳。

---

## 六、常用命令速查

```bash
# 看已装组件
sdkmanager --list_installed

# 加装（不需要 sudo，因为 /opt/android-sdk 属主是你）
sdkmanager --install "platforms;android-36" "build-tools;36.1.0"
sdkmanager --install "platforms;android-35"          # 老项目兼容用

# 构建（注意非交互式 shell 要显式导出）
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug

# 手验 APK
aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk
apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk
zipalign -c -v 4 app-debug.apk

# Linux 侧 adb（能连网络 adb，但看不到 USB 设备）
adb devices
```

---

## 七、真机调试链路（WSL 的限制与走法）

| 事实 | 说明 |
|---|---|
| WSL 看不到 USB | 无 `/dev/bus/usb`，手机插 Windows 上，**Linux 侧 adb 看不到设备** |
| 无 KVM | 无 `/dev/kvm` → WSL 内跑 Android 模拟器不可行 |
| Windows 侧有 adb | `C:\Program Files\ASUS\GlideX\adb.exe`（**platform-tools 31.0.2，2021 年版，偏老**） |
| WSL interop | ✅ 已启用，能从 WSL 直接执行 Windows 的 exe |

**建议**：给 Windows 换一个新版 platform-tools（37.0.1），因为 adb 31 对 Android 14/15/16 的部分新特性支持不全。下载 `platform-tools-latest-windows.zip`（约 10 MB）解压到 Windows 侧一个目录即可（例如 `C:\Users\FragransTea\platform-tools`）。我可以直接帮你下好放过去。

**接法二选一**：
```bash
# A. 简单可靠：APK 拷到 /mnt/c 下，用 Windows 的 adb 安装
"/mnt/c/Users/FragransTea/platform-tools/adb.exe" install -r /mnt/c/tmp/app-debug.apk

# B. 顺手：Windows 侧开 adb server，WSL 用 ADB_SERVER_SOCKET 连过去，之后 gradlew installDebug 一条龙
#    Windows: adb.exe -a nodaemon server   （需放行 5037 入站）
export ADB_SERVER_SOCKET=tcp:192.168.192.1:5037   # 192.168.192.1 = WSL 看到的 Windows 主机
```

---

## 八、这台机器的固有限制（写代码时要照顾）

1. **内存 7.4 G** → Gradle 必须限堆（见第三节 `gradle.properties`），否则 Kotlin/AGP 守护进程容易把机器压到交换。
2. **无 KVM / 无 USB 直通** → 任何"跑起来看效果"的验证都得靠真机（USB 插 Windows）。
3. **磁盘**：Linux 侧 940 G 可用很宽裕；Windows C 盘只剩 51 G，**别把 SDK/构建缓存放到 `/mnt/c`**（跨文件系统 I/O 也慢）。
4. **首次构建耗时长**（本次冒烟约 4 分钟，含 Gradle 发行版 140 MB + AGP/Kotlin 依赖下载）；`~/.gradle` 缓存已预热，后续项目会快很多。

---

## 九、冒烟验证怎么复现

```
/tmp/smoke/                      # 临时验证项目（可删）
├── gradlew, gradle/wrapper/     # wrapper（从 GitHub raw 取的 gradlew + jar）
├── settings.gradle.kts
├── build.gradle.kts             # 只声明 AGP 9.4.0
├── gradle.properties            # 内存限制
├── local.properties             # sdk.dir=/opt/android-sdk
└── app/
    ├── build.gradle.kts         # 无 kotlin 插件，compileSdk 36
    └── src/main/...             # MainActivity.kt（Kotlin）
```

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
cd /tmp/smoke && ./gradlew assembleDebug
```

**实测结果**：
- `BUILD SUCCESSFUL in 52s`（34 个任务）
- 产物 `app/build/outputs/apk/debug/app-debug.apk`，6.4 MB
- `aapt2 dump badging`：`package='com.fragranstea.smoke'`、`compileSdkVersion='36'`、`targetSdkVersion='36'`
- `apksigner verify`：V2 签名（Android Debug 证书）
- Kotlin 确实被编译：中间产物 `app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/fragranstea/smoke/MainActivity.class`，且类已进 `classes2.dex`

---

## 十、⚠️ 依赖版本的 compileSdk 上限（实测，非常容易踩）

AndroidX 的 AAR 里带 `minCompileSdk` 元数据。**依赖版本比编译平台新一档，构建就会直接失败**（报 `Dependency 'X' requires ... compile against version N or later`）。本机 SDK 只装到 **android-36**，所以下面这些上限是硬约束：

| 依赖 | 可用版本上限 | minCompileSdk |
|---|---|---|
| `androidx.core:core-ktx` | **1.18.0** | 1.19.0 要求 **37** ❌ / 1.17.0、1.18.0 要求 36 ✅ |
| `androidx.appcompat:appcompat` | 1.8.0 | 34 ✅ |
| `androidx.activity:activity` | 1.13.0 | 36 ✅ |
| `androidx.compose:compose-bom` | **2026.09.00 不可用** | 其 `material-ripple-android:1.12.1` 要求 **37** ❌ |

**为什么不上 compileSdk 37**：SDK 仓库里**没有纯 `platforms;android-37`**，只有版本化的 `platforms;android-37.1` 与 `android-37.2`。要用得配合 AGP 的 minor SDK 版本支持（`compileSdkMinor`），属于新机制。
**结论**：本项目锁定 `compileSdk = 36` + 上表版本；等以后真要上 Compose 或新 AndroidX 时，再单独评估"升到 37.1"这条路。

查某个依赖的 minCompileSdk 的方法（不用下载整个 AAR）：

```bash
curl -sS -o /tmp/x.aar https://dl.google.com/dl/android/maven2/androidx/core/core/1.18.0/core-1.18.0.aar
unzip -p /tmp/x.aar "META-INF/com/android/build/gradle/aar-metadata.properties" | grep minCompileSdk
```

---

## 十一、Windows 侧 adb（已装好）

| 项 | 值 |
|---|---|
| 位置 | **`C:\Users\FragransTea\platform-tools\`**（从 WSL 看是 `/mnt/c/Users/FragransTea/platform-tools/`） |
| 版本 | platform-tools **37.0.1**（与 Linux 侧一致） |
| 验证 | 已从 WSL 执行 `adb.exe version` 成功；旧 server 已 kill |

原来只有 ASUS GlideX 自带的 platform-tools 31.0.2（2021 年版，对 Android 14/15/16 支持不全），现在这条链路用的是新版。

```bash
ADB="/mnt/c/Users/FragransTea/platform-tools/adb.exe"
"$ADB" devices -l
"$ADB" install -r /mnt/c/tmp/app-debug.apk
```

---

## 十二、GitHub 推送与代理（每个新 DSH 会话都能直接推送）

### 事实基础（全部实测）
- GitHub 从这台 WSL **可以访问**，但前提是**你的 Windows 代理处于开启状态**。
- WSL 是 **NAT 模式**：Windows 的 `127.0.0.1:10090` 在 WSL 里**看不到**；NAT 模式下**默认网关就是 Windows 主机**（当前 `192.168.192.1`）。
- 代理端口 **10090 是 mixed 端口**：HTTP 与 SOCKS5 都可直接用（实测两者访问 `api.github.com` 均返回 200）。
- **直连不通、走代理才通**：`raw.githubusercontent.com`、`objects.githubusercontent.com`（GitHub 的 release 资源域名 —— 这也正是当初 Gradle 发行版下载失败的原因）。

### 已做好的持久化配置

| 用途 | 机制 | 位置 |
|---|---|---|
| `git push` / `git pull`（SSH） | `ProxyCommand` 调用 `ssh-proxy-github` | `~/.ssh/config` |
| 远端地址 | `git@github.com:guiyuqi/ScreenSolve.git` | 仓库 `.git/config` |
| 按需的 HTTPS 代理 | `github-proxy` 命令 | `~/.local/bin/github-proxy` |

**关键设计：网关 IP 绝不写死。** WSL 重启后 NAT 网段可能变化，所以两个脚本都在**运行时动态探测**：先试 `127.0.0.1`（将来若切到 WSL 镜像网络模式就命中它），再试默认网关，都不可用则**回退直连**——避免"配了代理反而把 SSH 弄坏"。探测一律带 1 秒超时，否则代理没开、包被防火墙静默丢弃时 SSH 会卡两分钟。

验证方式（实测输出）：
```
debug1: Executing proxy command: exec /home/fragranstea/.local/bin/ssh-proxy-github github.com 22
Authenticated to github.com (via proxy) using "publickey".
Hi guiyuqi! You've successfully authenticated, but GitHub does not provide shell access.
```

### agent 该怎么做
**推送代码：什么都不用做**，`git push` 自动走代理：
```bash
cd ~/dsh-workspace/ScreenSolve && git push
```

**需要用 HTTPS 访问 GitHub 时**（curl 拉 release 资源、`git clone https://...` 等），用 `github-proxy` 现取地址。

⚠️ **注意路径**：agent 跑的是**非交互式 shell**，`~/.local/bin` **不在 PATH 里**，所以必须用绝对路径（交互式终端里才能省成 `github-proxy`）：

```bash
PROXY="$(~/.local/bin/github-proxy)"
curl -x "$PROXY" -L -o file.zip https://github.com/owner/repo/releases/download/v1/file.zip
git -c http.proxy="$PROXY" clone https://github.com/owner/repo.git
```

⚠️ **不要把代理设成全局环境变量**。全局 `http_proxy` 会把 Maven / `dl.google.com` 一起绕进代理，而它们直连有 5～9 MB/s，绕代理反而慢、且代理一关就全断。只对需要的命令按需指定。

### 故障排查
| 现象 | 处理 |
|---|---|
| `git push` 卡住 / `Connection refused` | Windows 代理没开。开启后重试，脚本会自动探测到 |
| 代理开着却探测不到 | 代理客户端里打开**"允许局域网连接"**；或改用 WSL 镜像网络模式（`.wslconfig` 加 `networkingMode=mirrored` 后 `wsl --shutdown`——注意会重启 WSL、当前会话会断） |
| 换了代理端口 | `export GITHUB_PROXY_PORT=<新端口>`；要永久改就改 `~/.ssh/config` 与两个脚本里的默认值 |
| SSH 认证失败 | 确认公钥仍在 https://github.com/settings/keys （本机公钥：`~/.ssh/id_ed25519.pub`） |

## 约定：产物只留在 WSL，不往 Windows 侧复制

adb 用的是 Windows 侧的 `adb.exe`，它会"读不到 WSL 路径"，但这**不构成**把 apk 拷到 `/mnt/c` 的理由。
Windows 进程可以直接读 WSL 里的文件，用 UNC 路径即可：

```bash
cd ~/dsh-workspace/ScreenSolve
APK='\\wsl.localhost\Ubuntu\home\fragranstea\dsh-workspace\ScreenSolve\app\build\outputs\apk\lite\debug\app-lite-debug.apk'
"/mnt/c/Users/FragransTea/platform-tools/adb.exe" install -r "$APK"
```

（已用 PowerShell `Test-Path` / `Get-Item` 验证该 UNC 路径从 Windows 可读，字节数与 WSL 内一致。）

- 构建产物统一放在 `app/build/outputs/apk/<flavor>/<buildType>/`，**不要再复制到 `/mnt/c/Users/...`**
- 签名证书备份在 WSL：`~/screensolve-signing-backup/`（jks + 口令 + 说明）
  —— 注意 WSL 目录**不算异地备份**，需要自己再存一份到网盘/U 盘
