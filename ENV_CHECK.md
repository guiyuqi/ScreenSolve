# WSL2 Android 开发环境检查报告

检查时间：本会话 · 目标项目：悬浮窗截图搜题 APK
工作区：`/home/fragranstea/dsh-workspace/ScreenSolve`（当前为空目录，非 git 仓库）

---

## 一、一句话结论

**系统与网络都没问题，但 Android 工具链是「完全空白」：没有 JDK、没有 Gradle、没有 Android SDK、没有 adb，也没有可用的本地设备。**

- 工具链装好后即可正常开发（网络全通、磁盘充足）；
- 但**没有 root 权限**（沙箱内）和**没有 KVM / USB 直通**，所以：
  - 安装建议走「用户态免 root」路径（或用你在自己终端里 `sudo apt`）；
  - 真机调试只能**借道 Windows 侧的 adb**（好消息：现成可用）。

---

## 二、系统与资源（✅ 正常）

| 项目 | 结果 |
|---|---|
| 发行版 | Ubuntu 26.04 LTS (resolute), x86_64 |
| WSL 内核 | 6.18.33.1-microsoft-standard-WSL2 |
| Windows 版本 | 10.0.26200.9445（`/mnt/c` 已挂载） |
| CPU / 内存 | 32 vCPU / 7.4 GiB（可用约 6.3 GiB） |
| Linux 根分区 | 1007 G，可用 **940 G** ✅ |
| Windows C 盘 | 925 G，可用 **51 G** ⚠️（偏紧，别把 SDK 放 C 盘） |
| 用户 | `fragranstea`（uid 1000，在 `sudo` 组中） |

> 内存只有 7.4 G：Gradle + AGP + Kotlin 编译默认配置容易吃紧，构建时需要限制堆大小与并行 worker 数（建议 `-Xmx2g`、`org.gradle.workers.max=4`）。

---

## 三、工具链现状（❌ 缺失项）

| 组件 | 状态 |
|---|---|
| `java` / `javac` | ❌ 未安装，`JAVA_HOME` 为空，`/usr/lib/jvm` 为空 |
| `gradle` | ❌ 未安装（项目可用 wrapper，但 wrapper 也要先有 JDK） |
| `kotlinc` | ❌ 未安装（写 Kt 不需要，Gradle 会拉编译器） |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | ❌ 均为空 |
| Android SDK 目录 | ❌ `~/Android/Sdk` 等常见位置均不存在 |
| `adb` / `sdkmanager` / `avdmanager` / `emulator` / `aapt2` / `apksigner` / `zipalign` | ❌ 全部不在 PATH |
| `~/.gradle` 缓存 | ❌ 不存在 → **首次构建需联网下载全部依赖** |
| `unzip` / `zip` / `7z` | ❌ 均未安装（可用 `python3 -m zipfile` 代替解压）✅ 已验证 |

**已具备的可用工具**：`node v24.19.0`、`npm 11.17.0`、`pnpm 11.7.0`、`python3 3.14.4`、`git 2.53.0`、`curl 8.18`、`wget 1.25`、`xz 5.8.3`。

**apt 情况**：软件源索引已就绪，可直接安装
- `openjdk-21-jdk` → 候选 `21.0.12+8-1~26.04`（也提供 17 / 25 / 26）
- `unzip` → 候选 `6.0-29ubuntu1`

**关于 sudo ⚠️**：你在 `/etc/group` 的 `sudo` 组成员里（即真实终端里 `sudo` 应可用），但**在我当前的执行沙箱内 `sudo` 被 `NoNewPrivs` 阻止**，我无法代你执行 `apt install`。所以：
- 要么**你自己在 WSL 终端跑一条 apt 命令**（最快、最省空间）；
- 要么让我做**纯用户态安装**（下载 JDK/SDK 压缩包到你的家目录，完全不需要 root，我可以全自动做完）。

---

## 四、构建依赖网络可达性（✅ 全通）

无代理，NAT 模式（默认网关 `192.168.192.1` = Windows 主机），DNS `10.255.255.254`。

| 目标 | 状态 |
|---|---|
| `archive.ubuntu.com` | ✅ 200 |
| `dl.google.com`（Android SDK/NDK） | ✅ 200 |
| `maven.google.com`（AGP） | ✅ 200 |
| `repo1.maven.org` / `repo.maven.apache.org` | ✅ 200 |
| `plugins.gradle.org` / `services.gradle.org` | ✅ 200 |
| `api.adoptium.net`（Temurin JDK） | ✅ 200 |
| `api.deepseek.com` | ✅ 可达（无鉴权返回 401，符合预期） |

**实测持续下载速度 ≈ 5.6 MB/s**（首次拉全套工具链约 1.5–2 GB，预计 5–10 分钟）。

---

## 五、设备 / 调试链路（⚠️ 需要你介入）

| 检查项 | 结果 |
|---|---|
| Linux 侧 USB 直通 | ❌ 无 `/dev/bus/usb`（WSL2 默认不透传 USB） |
| KVM 加速 | ❌ 无 `/dev/kvm` → **本地 Android 模拟器不可用**（除非改 `.wslconfig` 开嵌套虚拟化，成本高、收益低） |
| Windows 侧 Android SDK / Android Studio | ❌ 未找到（标准路径与目录扫描均无） |
| Windows 侧 adb | ✅ **有可用 adb**：`C:\Program Files\ASUS\GlideX\adb.exe`（Android Debug Bridge 1.0.41 / platform-tools 31.0.2），已能从 WSL 直接调用 |
| 当前已连接设备 | ❌ `adb devices` 为空（Windows 侧守护进程已启动于 tcp:5037） |
| WSL interop | ✅ 已启用，WSL 里可直接执行 Windows 程序 |
| WSLg 图形 | 存在（`/mnt/wslg`，X0 socket），但本方案用不上 |

**真机调试的两条可行路径（二选一，届时再定）**：
1. **最省事**：把 APK 复制到 `/mnt/c/...`，直接从 WSL 调 `adb.exe install`（走 Windows 驱动栈，手机 USB 插 Windows 即可）。
2. **更顺手**：Windows 侧 `adb.exe -a nodaemon server` 监听，WSL 侧 Linux adb 用 `ADB_SERVER_SOCKET=tcp:192.168.192.1:5037`，这样 `gradle installDebug` 一条龙（需要在 Windows 放行 5037 端口）。

---

## 六、关键发现：DeepSeek API 已支持视觉，**不需要 OCR**

核对官方文档（[Vision 指南](https://api-docs.deepseek.com/guides/vision/)、[模型与定价](https://api-docs.deepseek.com/quick_start/pricing)）：

- `deepseek-flash`（基座 **DeepSeek-V4.1-Flash**）**原生支持图片输入**，走 OpenAI 兼容的 `POST /chat/completions`；
- 传图方式：`content` 用数组，图片作 `{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}`；
- 支持 **JPEG / PNG / GIF / WebP**（按文件内容识别）；请求体上限 **48 MiB**；上下文 1M；
- 旧模型名 `deepseek-v4-flash`、`deepseek-v4-flash-vision-exp` 已退役但仍接受，由最新 Flash 模型承接；
- `deepseek-v4-pro` **不支持视觉**，只能文本。

👉 对搜题场景的意义：**截图选区裁剪 → 转 base64 → 直接问 `deepseek-flash`** 即可识别题目并作答，架构大幅简化，无需集成 ML Kit / PaddleOCR，也避免了 OCR 错字导致的错误答案。

---

## 七、需要预留的技术风险（实现阶段注意）

1. **权限模型**：悬浮窗需 `SYSTEM_ALERT_WINDOW`；截图需 `MediaProjection` + 前台服务 `FOREGROUND_SERVICE_MEDIA_PROJECTION`（Android 14+ 强制声明类型且必须由用户确认授权）；后台弹窗/前台服务在 Android 12+ 有额外限制。
2. **首次授权流程**：MediaProjection 每次（Android 14+ 每个会话）都要用户点一次「开始录制」，无法静默截图——这是系统限制，产品交互要按此设计。
3. **包体与网络**：base64 内联会让截图体积膨胀约 33%，建议裁剪后压到 1080p 内、JPEG 质量 80 左右，控制在几百 KB。
4. **内存**：7.4 G 内存下 Gradle 构建需限制堆与 worker 数。
5. **网速**：5.6 MB/s，首次构建的依赖下载是主要等待项；后续有 `~/.gradle` 缓存会快很多。
6. **targetSdk**：当前最新平台（android-36）对前台服务与截图权限最严格，建议直接以 android-36 为目标，一次到位。

---

## 八、结论：下一步三选一

| 方案 | 说明 | 我的工作量 | 你的工作量 |
|---|---|---|---|
| **A. 用户态安装（推荐）** | 我在家目录下载 Temurin JDK 21 + Android cmdline-tools + platform-tools/build-tools/platform-36 + Gradle wrapper，不需要 root，全自动 | 全自动 | 无 |
| **B. apt 安装** | 你在 WSL 终端执行 `sudo apt install -y openjdk-21-jdk unzip`，其余 SDK 部分我补 | 补 SDK | 跑 1 条命令 |
| **C. 只出方案不装** | 先看架构/代码方案，等你决定 | 出方案 | 无 |

设备方面请确认：是否有 Android 真机可以 USB 连到 Windows（决定我能不能帮你做「装到手机上看效果」这一步）。

---

## 附：本次检查用到的关键命令（可复现）

```bash
java -version; echo $JAVA_HOME; ls /usr/lib/jvm
which adb sdkmanager gradle; echo $ANDROID_HOME
ls /dev/kvm /dev/bus/usb                     # 模拟器加速 / USB 直通
"/mnt/c/Program Files/ASUS/GlideX/adb.exe" devices -l
curl -sI https://dl.google.com/android/repository/platform-tools-latest-linux.zip
curl -sS -o /dev/null -w '%{http_code}\n' https://api.deepseek.com/
dpkg -l | grep -iE 'jdk|android|unzip'
```
