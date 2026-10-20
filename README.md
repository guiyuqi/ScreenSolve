# ScreenSolve · 搜题助手

一个 Android 悬浮球截图搜题工具：**在任何 App 上点一下悬浮球 → 框选一道题 → 直接出答案和分步解析**。
答案里的公式是真 LaTeX 渲染（不是一堆反斜杠），还支持在答案卡片里继续追问、从历史记录里接着聊。

> 需要自备 [DeepSeek](https://platform.deepseek.com/) API Key（应用内填写，只存在你手机本地）。

**下载安装包**：[GitHub Releases](https://github.com/guiyuqi/ScreenSolve/releases/latest)（`ScreenSolve-lite-release.apk` / `ScreenSolve-full-release.apk`）

## 两个版本，二选一装

| 版本 | 体积 | 识题方式 | 适合 |
|---|---|---|---|
| **lite** | 约 2.3 MB | 把框选图直接交给多模态模型 | 日常用，启动快、装包小 |
| **full** | 约 16.7 MB | 额外带 ML Kit 中文离线 OCR，可只发文字不发原图 | 想省流量 / 不想把原图发出去 |

体积差全部来自 ML Kit。**注意：做数学题请用 lite（视觉直传）**——OCR 会把公式的运算符和积分上下限认丢，
full 版的 OCR 通道更适合纯文字题。

## 安装

1. 拷 `ScreenSolve-lite-release.apk` 到手机，点安装（国产 ROM 可能要求"允许安装未知来源应用"）。
2. 打开应用 → 按页面提示授予 **悬浮窗权限**（Android 13+ 可能还要通知权限）。
3. 点「打开设置」→ 粘贴 DeepSeek API Key → 保存。Key 用 Android Keystore 加密存储，不落明文。
4. 回到主界面点「启动悬浮球」。

> 换版本 / 换签名安装时必须先卸载旧版，否则会提示"应用未安装"。卸载会清掉已存的 Key 和历史记录。

## 怎么用

- **搜题方式**：设置里可选「截屏 + 框选」（默认）或「拍照 + 框选」。
  拍照模式用**应用内自建相机**，拍的照片只进内存、**不会存进系统相册**，识完题只有裁好的题目图进历史记录。
- **悬浮球样式**：设置里可切「默认蓝色放大镜（56dp）」或「极简小白点（视觉 10dp，可点区域仍留够）」，
  切换立即生效、位置不变。
- **搜题**：点悬浮球 → 首次会请求一次录屏授权（一次授权后就会持续可用，不用每次点）→
  拖四角框住**一道题**→ 点「识别这道题」→ 答案卡片弹出。
- **追问**：答案卡片底部输入框里接着问（比如"第二步再讲讲"），模型带着题目上下文回答。
- **看原图**：卡片会回显识别到的题干，错了说明框偏了；历史里点缩略图可全屏看图并双指缩放。
- **历史**：最近 10 次，点进去是会话视图，能看图、能继续追问、能一键复制全部。
- **外观**：设置页可切「跟随系统 / 白天 / 夜间」，主界面、历史、会话、相机取景统一生效。
- **长公式**：一屏放不下的公式不会缩小，而是**单独成块、左右滑动查看**（悬浮卡片与历史会话都支持）。
- **看图**：全屏看题目图支持双指/双击缩放、放大后拖动、右上角 **⟳ 旋转 90°**。
- **拍照补光**：取景页右上角 💡 开关手电（暗光拍纸质题目用）。
- **调参**：设置页可切识题来源、识题通道、悬浮球样式，开"深度思考"（更准更慢）、改提示词（比如要求"只给答案不要解析"）。

框选建议：**只框一道题**，多题同屏会明显降低质量（模型注意力被分散）。

## 隐私

- API Key：Android Keystore AES-256-GCM 加密后存在应用私有目录，**只有密文**；应用不联网发送它。
- 题目图：历史记录里的题目图只存在应用私有目录（`filesDir/history/`），随卸载消失，不上传任何地方。
- 拍照模式：照片在内存里直接进框架，**不写系统相册、也不落临时文件**；只有裁好的题目图进历史。
- 截图：只有你点「识别这道题」时，**框选区域**才会发给 DeepSeek 用于识题；lite 版发图，full 版 OCR 通道只发识别出的文字。
- 应用不申请通讯录/位置/存储等无关权限，也没有第三方统计 SDK。

## 从源码构建

需要 JDK 21 + Android SDK 36（编译工具链细节见 `TOOLCHAIN.md`）。

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk

./gradlew assembleLiteDebug      # 调试包（主界面会显示开发进度区块）
./gradlew assembleLiteRelease    # 正式包（不带任何构建/里程碑字样）
./gradlew assembleFullRelease    # full 版正式包
```

- release 签名配置从仓库根目录的 `keystore.properties` 读（配合 `keystore/screensolve-release.jks`），
  两者都**不在仓库里**。缺少它们时 release 包不签名，但仍能编译。
- 想在不卸载已装 debug 包的前提下验证混淆后的 release 包：
  `./gradlew assembleFullRelease -PsmokeTest`，包名会变成 `...screensolve.smoke`，可与 debug 版共存。

## 目录

| 文件 | 内容 |
|---|---|
| `HANDOVER.md` | 交接文档：进度、代码地图、已验证项、踩坑清单 |
| `ARCHITECTURE.md` | 架构设计：抓帧、选区、识题、渲染、错误处理 |
| `TOOLCHAIN.md` | 本机工具链与镜像配置（WSL2 环境踩过的坑） |
| `ENV_CHECK.md` | 环境体检报告 |

## 已知限制

- 只支持 arm64-v8a（`abiFilters`），老旧 32 位机型装不了。
- 需要 Android 10（API 29）及以上。
- 公式渲染基于 jlatexmath，极少数模型自造的非法 LaTeX 命令会自动降级成源码文本（不会白屏）。
- 一次只保证一道题的质量。

## 许可证

本项目以 **MIT 许可证**发布，全文见 [LICENSE](LICENSE)。

```
Copyright (c) 2026 guiyuqi
```

简单说：你可以自由使用、修改、商用、再发布，**只要保留版权声明与许可证副本**；作者不为使用后果负责。

打包进 APK 的第三方库遵循它们各自的许可证，版权归各自作者：

| 依赖 | 用途 | 许可证 |
|---|---|---|
| [Markwon](https://github.com/noties/Markwon) | Markdown 渲染 | Apache-2.0 |
| [jlatexmath-android](https://github.com/noties/jlatexmath-android) | LaTeX 公式渲成图片 | 见其仓库（底层 jlatexmath 来自 Scilab） |
| [AndroidX CameraX](https://developer.android.com/jetpack/androidx/releases/camera) | 拍照模式取景 | Apache-2.0 |
| [ML Kit](https://developers.google.com/ml-kit)（仅 full 版） | 中文离线 OCR | 见 Google 条款 |
