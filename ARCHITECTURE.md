# 悬浮窗截图搜题 APK — 技术架构方案

> 环境前提见 `ENV_CHECK.md`。本方案**不包含任何代码实现**，只定义架构、选型、时序、风险与里程碑，供你决策后再开工。
>
> 决策记录（已确认）：
> - 识题**双通道可切换**：视觉直传（多模态） / 端侧 OCR + 文本模型
> - API Key：**App 内设置页手填 + 本地加密存储**（不进代码、不进 APK）
> - 调试设备：**Android 真机 USB**（经 Windows 侧 adb）

---

## 1. 目标与范围

### MVP 必须做到
1. 一个常驻悬浮球/悬浮条，可拖动、可贴边收起，其他应用上层可见
2. 点一下就进入**截图 → 拖框选区 → 自动识题 → 出答案**的闭环
3. 答案以悬浮卡片展示，支持滚动、复制、追问（多轮）
4. 设置页：填 API Key、切换识题通道、选择模型与思考模式、选区后是否自动提交

### 明确不做（避免范围膨胀）
- 不做题库检索/拍照搜原题（那是另一条链路，需要题库服务）
- 不做云端账号体系、不做多设备同步
- 不做自动点击/自动填答案（无障碍自动化，合规风险高）
- 不做长截图拼接、录屏、OCR 批量识别

---

## 2. 总体架构

```
┌──────────────────────────────────────────────────────────────┐
│  表现层 Presentation                                          │
│  ├─ MainActivity / SettingsActivity（Compose，设置与历史）     │
│  ├─ OverlayBubbleView（悬浮球，View 体系，跑在 Service 里）    │
│  ├─ RegionSelectorView（选区覆盖层，View 体系，全屏）          │
│  └─ ResultCardView（答案卡片，View 体系，可滚动/可追问）        │
├──────────────────────────────────────────────────────────────┤
│  会话编排 CaptureSessionController（唯一状态机，核心）          │
│  权限校验 → 隐藏自身 UI → 抓帧 → 选区 → 裁剪 → 识别 → 展示     │
├──────────────────────────────────────────────────────────────┤
│  采集层 Capture                                                │
│  ├─ ProjectionManager（MediaProjection 会话生命周期 + FGS）    │
│  ├─ ScreenFrameSource（VirtualDisplay + ImageReader 抓帧）     │
│  └─ ImagePipeline（裁剪/缩放/压缩/base64）                     │
├──────────────────────────────────────────────────────────────┤
│  识别层 Recognizer（策略接口，运行时可切换）                    │
│  ├─ VisionLlmRecognizer     截图 → base64 → deepseek-flash    │
│  └─ OcrThenLlmRecognizer    截图 → ML Kit OCR → 文本 → flash   │
├──────────────────────────────────────────────────────────────┤
│  网络层 DeepSeekClient（OkHttp，SSE 流式，重试，错误映射）      │
├──────────────────────────────────────────────────────────────┤
│  数据层 Storage                                                │
│  ├─ SecureKeyStore（API Key，Android Keystore 加密）           │
│  ├─ SettingsRepository（DataStore）                            │
│  └─ HistoryRepository（历史记录，Room 或 JSON 文件）            │
└──────────────────────────────────────────────────────────────┘
```

### 为什么这么切
- **只有一个状态机**：截图类应用的 bug 几乎全部来自"状态错乱"（悬浮窗没恢复、投影没释放、选区层残留）。所以把流程收敛到 `CaptureSessionController` 一个类里，其余模块都是无状态的执行者。
- **View 体系做悬浮窗，Compose 只做 Activity**：把 Compose 塞进 `WindowManager` 的 overlay 需要手工提供 `LifecycleOwner / SavedStateRegistryOwner / ViewModelStoreOwner`（`setViewTreeLifecycleOwner` 那一套），在 Service 里极易踩坑且难调试。悬浮球/选区框用 XML + 自定义 View 更稳、启动更快（悬浮球要秒开）。设置页和历史页用 Compose 提效。
- **识别层抽成策略接口**：因为你选了双通道可切换，接口必须一开始就定好，否则后面切换要动全身。

---

## 3. 工程结构

### 3.1 模块划分
单模块起步（`:app`），按包分层。理由是此项目规模小、无多端复用需求，多模块只会拖慢构建与心智负担。若后续要抽 OCR/网络为库再拆。

```
app/src/main/java/com/example/screensolve/
├── app/            Application、DI 装配（手写 ServiceLocator，不引 Hilt，见 §7）
├── ui/
│   ├── main/       MainActivity：权限引导、总开关
│   ├── settings/   设置页（API Key、通道切换、模型、思考模式）
│   ├── history/    历史列表 + 详情
│   └── overlay/    OverlayBubbleView、RegionSelectorView、ResultCardView
├── session/        CaptureSessionController、SessionState、错误类型
├── capture/        ProjectionManager、ScreenFrameSource、ImagePipeline
├── recognize/      Recognizer 接口、VisionLlmRecognizer、OcrThenLlmRecognizer、
│                   PromptBuilder、AnswerParser
├── network/        DeepSeekClient、dto/、SseParser、ApiErrorMapper
├── data/           SecureKeyStore、SettingsRepository、HistoryRepository、db/
└── util/           权限工具、DisplayMetrics 换算、Bitmap 工具
```

### 3.2 关键接口（先定契约，再写实现）

```kotlin
// 识别层：双通道都实现它，设置页切换实现类
interface Recognizer {
    /** 是否离线可用（OCR 通道 true；视觉通道 false） */
    val requiresNetwork: Boolean
    /**
     * @param image 裁剪后的题目图（已压缩到长边 ≤1600、JPEG）
     * @param ocrText OCR 通道预识别出的文本；视觉通道忽略
     * @param question 用户可能的追问（首轮为 null）
     */
    fun recognize(
        image: Bitmap,
        ocrText: String? = null,
        question: String? = null,
    ): Flow<RecognizeEvent>   // Streaming / Done(answer) / Failed(reason)
}

sealed interface RecognizeEvent {
    data class Streaming(val delta: String) : RecognizeEvent
    data class Done(val answer: ParsedAnswer) : RecognizeEvent
    data class Failed(val error: SolveError) : RecognizeEvent
}

// 会话状态机：唯一可变状态持有者
sealed interface SessionState {
    data object Idle : SessionState
    data object RequestingConsent : SessionState
    data object Capturing : SessionState
    data class Selecting(val frame: Bitmap) : SessionState
    data object Recognizing : SessionState
    data class Showing(val answer: ParsedAnswer) : SessionState
    data class Error(val error: SolveError) : SessionState
}
```

> 设计要点：`Recognizer.recognize` 返回 `Flow`，这样"流式打字机效果"是天然支持的，而不是事后加。

---

## 4. 关键技术方案

### 4.1 悬浮窗

- **窗口类型**：`TYPE_APPLICATION_OVERLAY`（API 26+ 唯一可用选择，旧 `TYPE_PHONE` 在新系统上会被拒）
- **权限**：`SYSTEM_ALERT_WINDOW`，需 `Settings.canDrawOverlays()` 检查 + `ACTION_MANAGE_OVERLAY_PERMISSION` 跳转授权（无法用运行时权限弹窗申请）
- **载体**：**前台服务**（`OverlayService`）持有 `WindowManager` 引用；Activity 只负责开关与配置
- **交互**：
  - 拖动：`onTouchEvent` 跟手 `updateViewLayout`，抬起时按 X 位置吸附到左/右边缘，并记录到 DataStore（下次恢复位置）
  - 点击 vs 拖动的判定：位移阈值（如 8dp）或时间阈值，避免拖完误触发截图
  - 展开态：点击后展开为"搜题/历史/设置"小面板；或长按拖动、单击直接进入选区
- **避坑**：`LayoutParams.flags` 里不要加 `FLAG_NOT_TOUCH_MODAL` 之外的花样；悬浮球需要触摸就要 `FLAG_NOT_FOCUSABLE`（否则抢占输入法焦点）

### 4.2 截图（本方案最大的系统约束所在）

**权限与生命周期链路**（顺序错了必抛 `SecurityException`）：

```
用户点"搜题"
  → 检查 SYSTEM_ALERT_WINDOW
  → 启动/确保 OverlayService 是前台服务（先 startForeground，且 type 含 mediaProjection）
  → MediaProjectionManager.createScreenCaptureIntent()  → 系统授权弹窗
  → onActivityResult 拿到 resultCode + data Intent
  → 把 resultCode/data 通过 Service 传给 ProjectionManager
  → getMediaProjection(resultCode, data)  → 注册 MediaProjection.Callback（Android 14+ 必须）
  → 创建 VirtualDisplay + ImageReader
  → 从 ImageReader.acquireLatestImage() 取一帧 → 转 Bitmap
```

**Manifest 必需项**：

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" /> <!-- 13+ 通知权限 -->

<service
    android:name=".ui.overlay.OverlayService"
    android:exported="false"
    android:foregroundServiceType="mediaProjection" />
```

**必须处理的系统限制**：

| 版本 | 限制 | 应对 |
|---|---|---|
| Android 10 (29) | 持续截屏必须绑定前台服务 | 常驻通知（低优先级渠道） |
| Android 13 (33) | 通知需 `POST_NOTIFICATIONS` 运行时权限 | 首次进入引导申请 |
| Android 14 (34) | ① 新增 FGS 类型 `mediaProjection` 且必须先 `startForeground` 再 `getMediaProjection`，否则 `SecurityException`；② **不能缓存/复用授权返回的 Intent**，每个 capture session 都要重新征得同意；③ 必须注册 `MediaProjection.Callback`（不注册，投影会在应用退到后台时被静默停止） | 见下方"授权次数优化" |
| Android 15 (35) | 引入**部分屏幕共享**（用户可选只共享某个应用窗口），回调返回的尺寸可能小于全屏 | 一律以 `ImageReader` 实际宽高为准，不要假设等于屏幕尺寸 |
| Android 16 (36) | 对屏幕捕获权限与前台服务的审查进一步收紧 | 以 API 36 为目标一次性适配，真机必测 |

> **捕获会话策略（已确认：持续捕获，不是"点一次授权一次"）**
> 首次授权后，`MediaProjection` 会话**一直存活**（由前台服务持有），配合常驻的 `VirtualDisplay → ImageReader`；
> 用户点悬浮球时直接从同一个 `ImageReader` 取"当前这一帧"。于是一次授权 = 一整轮使用，不再反复弹系统框。
>
> ⚠️ **必须持续排空 ImageReader，不能只在点击时取**（这是本方案最容易写错的一处）：
> `ImageReader` 只有 2 个缓冲。空闲期间若不去取帧，队列填满后 SurfaceFlinger 就**停止产帧**，
> 留在缓冲里的是"上一次产帧时刻"的画面 —— 用户滚动十几秒再点，拿到的是十几秒前的旧图，搜题必然搜错。
> 正确做法：注册 `setOnImageAvailableListener`，每来一帧 `acquireLatestImage()`，**保留最新帧、关掉上一帧**（同一把锁保护状态）；
> 点击时把保留帧拷成 `Bitmap` 后再放锁。空闲时只做 acquire/close（不拷贝、不解码、不编码），代价极小；
> 屏幕完全静止时没有 damage 事件，也就没有额外开销。
>
> 三点澄清：
> 1. **不需要 MediaCodec，也不写文件**：我们要的是静帧，不是真的"录屏"。`VirtualDisplay` 直接输出到 `ImageReader` 即可，比录屏省得多。
> 2. 系统会一直显示"正在屏幕共享/投射"的状态栏提示 —— 这是 Android 的隐私设计，去不掉，是持续方案必须接受的代价。
> 3. 会话可能被中断：用户在状态栏点"停止共享"、OEM 省电策略杀服务、`getMediaProjection()` 的 token 不可二次使用（Android 14+ 重复调用会抛 `SecurityException`）。所以**只能创建一次**，并在 `MediaProjection.Callback.onStop()` 里回到"无会话"状态、引导用户重新授权。
>
> **隐藏自身悬浮球的时序也要跟着改**：持续捕获下，如果"隐藏悬浮球 → 立刻取帧"，拿到的可能仍是隐藏前那一帧（里面带着悬浮球）。
> 正确做法：隐藏悬浮球后，**等下一个新帧到达**再取（隐藏动作本身会产生一次 damage 事件，所以下一帧一定会来），并设一个兜底超时。
>
> `virtualDisplay.setSurface(null)` 空闲省电留作 **M5 可选优化**，不作为首选：重新挂载后要等新帧，多一层时序不确定性，先把"持续排空"这个正确性基础做对。
>
> **M2 的真机验证点相应变为**：授权一次后会话能存活多久？锁屏 / 切后台 / 长时间静止后是否被系统或 OEM 断掉？被断时重新授权流程是否顺畅？

**防止把自己截进去**：抓帧前把悬浮球与结果卡片设为 `View.GONE`，`postDelayed` 1~2 帧后再抓帧，抓到后恢复。
不用 `FLAG_SECURE` 规避——那会在截图里留下**黑色矩形**（比隐藏更糟）。

### 4.3 区域选择

- **载体**：独立的全屏 Activity（`RegionSelectActivity`，`Theme.Translucent.NoTitleBar` + 半透明遮罩），比 overlay window 更容易拿到正确的触摸与生命周期
- **输入素材**：把刚抓到的全屏 Bitmap 作为背景铺满（而不是用透明窗口看真实屏幕）——这样遮罩、暗化、放大镜都在同一张图上做，坐标换算简单可靠 ✅
- **交互**：
  - 手指拖动出矩形；松手后矩形保留 8 个句柄可微调（MVP 可只做"重新拖"）
  - 遮罩：全屏 40% 黑，仅选区镂空（`PorterDuff.Mode.CLEAR` 或 `Path` 差集）
  - 放大镜（可选，体验加分）：拖动时在手指上方显示 2x 局部放大
  - 右下角悬浮按钮：`重选` / `确认` / `取消`
- **坐标体系**（最易出 bug 的地方，务必写单元测试）：一律用 **屏幕物理像素** 计算裁剪矩形（`Rect`），最后按 `Bitmap` 实际尺寸 clamp 一次；不要混用 dp。

### 4.4 图像处理（`ImagePipeline`）

```
全屏 Bitmap
  → 按选区裁剪（Rect）
  → 长边缩放到 ≤ 1600px（题目文字在这个分辨率下足够清晰）
  → JPEG 质量 80 压缩到 ByteArray
  → Base64 编码（体积会膨胀约 33%）
  → 交给网络层
```

要点：
- DeepSeek 视觉接口**请求体上限 48 MiB**、外链图片上限 32 MiB，我们压到几百 KB 即可，完全不是瓶颈；压缩是为了**上传速度与 token 消耗**
- 大图必须走 `BitmapFactory.Options.inSampleSize` 或分步缩放，避免 7.4G 内存的开发机与低端手机上 OOM
- 用后 `recycle()`，并保留"上一帧"引用做重试（不必重新授权/重截）
- BASE64 用 `android.util.Base64.encodeToString(bytes, NO_WRAP)`，不要用 Java 的 `Base64`（性能差）

### 4.5 识别层：双通道设计

| | 通道 A：视觉直传（默认） | 通道 B：端侧 OCR + 文本 |
|---|---|---|
| 链路 | 裁剪图 → base64 → `deepseek-flash` 视觉 | 裁剪图 → ML Kit OCR → 题干文本 → `deepseek-flash` 文本 |
| 联网 | 必须 | OCR 离线，出答案仍需联网 |
| 准确率 | 高（原生多模态，公式/图表/几何图都能理解） | 受 OCR 质量影响，公式、手写体、竖排易错 |
| 成本 | 按图片 token 计费，略高 | 输入 token 少，成本更低 |
| 体积 | 无额外依赖 | +2.0 MB（中文 bundled AAR 实测 2,036,186 字节） |
| 适合 | 数学公式、几何图、表格 | 纯文字题、弱网、想省 token |

**实现方式**：设置页一个开关 → `RecognizerFactory.create(mode, client)` 返回不同实现。切换是**运行时**行为，不做编译期裁剪（用 `build.gradle` 的 `BuildConfig` 可以，但没必要）。

**OCR 依赖选择（已核实两个 artifact 都存在）**：

| artifact | 版本 | 特点 |
|---|---|---|
| `com.google.mlkit:text-recognition-chinese` | `16.0.1` | **模型内置**，离线可用，不依赖 Google Play 服务；AAR 约 2.0 MB ✅ 推荐 |
| `com.google.android.gms:play-services-mlkit-text-recognition-chinese` | `16.0.1` | 精简版，模型由 Play 服务按需下载；**国内无 GMS 的机器会失败** |

因为你的真机环境不确定是否有 GMS，**选 bundled 版**（多 2 MB 换确定性）。注意 ML Kit 中文识别器同时支持拉丁字母，中英混排题目没问题。

### 4.6 网络层（`DeepSeekClient`）

- **客户端**：OkHttp 5.5.0（直连 REST，不引 openai-java SDK——本项目只需一个接口，自己拼 JSON 更轻、更可控）
- **序列化**：kotlinx-serialization-json 1.11.0（Kotlin 原生、无反射、体积小）
- **流式**：`Accept: text/event-stream`，按 SSE 逐行 `data:` 解析，边收边渲染（打字机效果）；首字延迟体验差异巨大
- **超时**：连接 10s / 读 60s（流式要设长读超时）；`callTimeout` 不设或设很大
- **重试**：仅对 5xx / 网络中断 / 429 重试，指数退避（1s→2s→4s，最多 2 次）；**不要在流已经开始输出后静默重试**
- **错误映射**：`401` → "API Key 无效，去设置页检查"；`402` → "余额不足"；`429` → "请求过快，稍后再试"；`400` → 打印服务端 message 便于定位；网络异常 → "网络不可用"
- **取消**：新一次搜题要 `cancel()` 掉上一次请求（`RecognizeEvent` 的 Flow 用 `collectLatest`）

**视觉请求体形状**（对照[官方 Vision 指南](https://api-docs.deepseek.com/guides/vision/)）：

```json
POST https://api.deepseek.com/chat/completions
Authorization: Bearer <用户填的 Key>
Content-Type: application/json

{
  "model": "deepseek-flash",
  "messages": [
    { "role": "system", "content": "<搜题系统提示词>" },
    { "role": "user", "content": [
        { "type": "text", "text": "请解答图片中的题目。" },
        { "type": "image_url",
          "image_url": { "url": "data:image/jpeg;base64,<BASE64>" } }
    ]}
  ],
  "thinking": { "type": "disabled" },
  "stream": true,
  "response_format": { "type": "json_object" }
}
```

**模型与思考模式**（已核实）：

| 模型 | 视觉 | 备注 |
|---|---|---|
| `deepseek-flash`（DeepSeek-V4.1-Flash） | ✅ 支持 | 上下文 1M，价格低，**默认开启思考模式（effort=high）** |
| `deepseek-v4-pro` | ❌ 不支持 | 只能文本，本场景不用 |

- 思考模式开关：`{"thinking": {"type": "enabled" | "disabled"}}`，配合 `reasoning_effort: low/high/max`
- **思考模式默认开启**，这对搜题是双刃剑：数学题准确率更高，但首字延迟明显变长。建议：设置页给三档——`极速（thinking 关闭）` / `均衡（effort=low）` / `精解（effort=high）`，默认"均衡"
- 注意：思考模式下 `temperature`、`presence_penalty`、`frequency_penalty` 无效（传了不报错但被忽略）；`top_p` 有 0.95 下限
- 思考内容在 `reasoning_content` 字段（与 `content` 同级）——**可以用来做"正在思考…"的实时提示**，体验很好
- 流式下若开启思考，先在 `reasoning_content` 上滚动提示，再输出正式答案

### 4.7 提示词与输出结构

系统提示词要求模型**只输出 JSON**（`response_format: {"type":"json_object"}`），结构：

```json
{
  "subject": "数学",
  "question": "题干（识别出的原文，用于用户核对是否识别正确）",
  "answer": "最终答案（简洁，如 A / x=3 / 12cm）",
  "steps": ["步骤1", "步骤2"],
  "conclusion": "一句话解析",
  "knowledge_points": ["一元二次方程求根"],
  "confidence": 0.0
}
```

设计要点：
- **`question` 字段是刚需**：让用户一眼看出"识别对不对"。识别错了，答案必然错——这是搜题类产品最重要的一处 UX 防线。
- **`confidence` 低时展示"识别可能不准，建议重选区域"**的提示
- 提示词里明确：题干不清楚就直说"图片不清晰，无法识别"，**禁止编题**（抑制幻觉）
- 数学公式渲染：MVP 用等宽字体显示 LaTeX 原文即可；后续可接 KaTeX（WebView 或 Compose 的数学渲染库），不要一开始就做
- 多选题：要求 `answer` 直接给选项字母 + 内容；解答题：`steps` 分步
- **提示词可自定义（你的要求）**：设置页给一个"自定义提示词"编辑框，默认内容即「给出答案 + 分步解析」，用户可改成"只给答案"、"讲得细一点"、"用初中生能听懂的话"等。
  **实现要点**：自定义文本只作为 system prompt 里的**风格指令段**拼接在固定骨架之后；骨架（JSON 输出格式、禁止编题、`question` 原文回显、字段定义）**不允许被用户覆盖**——否则用户随手一改就把结构化输出改坏，解析层直接崩。设置页要有"恢复默认"按钮。

### 4.8 结果展示

- 结果卡片做成**可拖动、可缩放的悬浮窗**（`TYPE_APPLICATION_OVERLAY`，`FLAG_NOT_FOCUSABLE` 但要能滚动 → 需要精确的 flags 组合，滚动用 `NestedScrollView`）
- 顶部固定：题干（可折叠）+ 复制答案按钮；中部滚动：步骤/解析；底部：追问输入框（点开才获取焦点，避免常驻抢占输入法）
- 追问 = 把历史消息（含图片）继续追加进 `messages`，形成多轮（注意上下文增长与费用）
- 关闭卡片 = 回到悬浮球态，`SessionState` 回 `Idle`
- 历史记录：每次成功识别落库（题干 + 答案 + 时间 + 缩略图可选）。**默认不保存原图**（截图可能含隐私），设置里可开

### 4.9 存储与安全

| 数据 | 方案 | 说明 |
|---|---|---|
| API Key | **Android Keystore（AES-GCM）+ DataStore** | 见下方说明 |
| 设置项 | `androidx.datastore:datastore-preferences:1.2.1` | 键值配置 |
| 历史记录 | Room（或先用 JSON 文件，MVP 够用） | 含题干/答案/时间 |
| 截图缓存 | 仅内存，进程退出即失 | 隐私优先 |

> **关于 `EncryptedSharedPreferences` 的重要提醒**：你选的"本地加密存储"最直觉的实现是 `androidx.security:security-crypto:1.1.0` 的 `EncryptedSharedPreferences`，它确实已发到 1.1.0 稳定版，**但该库（JetSec）已被官方弃用**，社区已有[迁移指南](https://proandroiddev.com/goodbye-encryptedsharedpreferences-a-2026-migration-guide-4b819b4a537a)与[社区 fork](https://github.com/ed-george/encrypted-shared-preferences)。
> 因此方案定为：**用 Android Keystore 生成/保管 AES 密钥，自己用 `Cipher`（AES/GCM/NoPadding）加密后存进 DataStore**。代码量约 60~80 行，无弃用风险。若你想更快出 MVP，用 `security-crypto:1.1.0` 也完全能跑，只是要有心理准备将来迁移。

---

## 5. 完整时序（成功路径 + 关键分支）

```
[悬浮球] 单击
   │
   ├─ overlay 权限缺失 ──────────────► 跳系统授权页，回来重试
   │
   ├─ 已有会话？ ── 是 ──┐
   │                    │
   └─ 否 ─► 启动 FGS ─► 请求 MediaProjection 授权 ─► 用户拒绝 ─► 提示"需要截图权限"并回 Idle
                              │
                              └─ 同意 ─► 建 VirtualDisplay + ImageReader
   ┌──────────────────────────┘
   ▼
隐藏悬浮球/卡片 ──(postDelayed 2 帧)──► 抓帧 acquireLatestImage ──► Bitmap
   │
   ├─ 抓帧失败 ─► 重试 1 次 ─► 仍失败 ─► 提示"截图失败，请重试"+ 释放会话
   ▼
恢复悬浮球 ─► 启动 RegionSelectActivity(frame)
   │
   ├─ 用户取消 ─► 回 Idle（不释放会话，下次可直接抓帧）
   ▼
用户框选确认 ─► ImagePipeline 裁剪+压缩+base64
   │
   ▼
RecognizerFactory(mode)
   ├─ 通道 A：base64 直传 deepseek-flash（SSE 流式）
   └─ 通道 B：ML Kit OCR → 文本 → deepseek-flash（SSE 流式）
   │
   ├─ 401/402/429/网络错误 ─► 错误卡片（含"去设置"按钮）
   ├─ JSON 解析失败 ─► 降级为纯文本展示（不要报错白屏）
   ▼
ResultCard 流式渲染 ─► 完成，写历史 ─► 可追问（多轮）或关闭回悬浮球
```

**会话释放时机**（内存与隐私的关键）：`OverlayService.onDestroy()` / 用户关闭总开关 / `MediaProjection.Callback.onStop()` 回调（系统回收，比如用户在状态栏点了"停止共享"）三处都要释放 `VirtualDisplay`、`ImageReader`、`MediaProjection`。

---

## 6. 权限清单（一次性对齐）

| 权限 | 用途 | 申请方式 |
|---|---|---|
| `SYSTEM_ALERT_WINDOW` | 悬浮球/卡片 | 跳系统设置页 |
| `FOREGROUND_SERVICE` | 常驻服务 | 普通权限 |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 截屏前台服务类型（34+ 必需） | 普通权限 + Manifest `type` |
| `POST_NOTIFICATIONS` | 前台服务通知（33+） | 运行时权限 |
| `INTERNET` | 调 API | 普通权限 |

**不需要**的权限（避免被应用商店/用户警觉）：无障碍、读取相册、读取短信、设备信息。本项目一个都不需要 —— 这点正好说明架构是干净的。

---

## 7. 版本与依赖选型（全部已联网核实存在）

| 组件 | 选定版本 | 核实来源 |
|---|---|---|
| compileSdk / targetSdk | **36**（仓库中最高 platform） | Google SDK repo（`platforms;android-36` 存在） |
| build-tools | 36.x（仓库最高 37.0.0，AGP 会自动挑） | 同上（`build-tools;36.1.0` 存在） |
| minSdk | **29**（Android 10） | 覆盖 MediaProjection FGS 时代，减少历史分支 |
| AGP | **9.4.0** | 实测通过 |
| Kotlin | **不声明插件** —— AGP 9 起内置 Kotlin 编译器 | 实测：显式声明 `org.jetbrains.kotlin.android` 会被直接拒绝 |
| Gradle | **9.7.1**（AGP 9.4.0 硬性要求 ≥ 9.6.0） | 华为云镜像，实测通过 |
| JDK | **21**（已装 OpenJDK 21.0.12） | apt |
| Compose BOM | ⚠️ **2026.09.00 要求 compileSdk 37，本项目暂不可用**（若要用 Compose，得挑更老的 BOM 或升到 `android-37.1`） | 实测 AAR 元数据 |
| core-ktx | **1.18.0**（1.19.0 要求 compileSdk 37） | 实测 AAR 元数据 |
| appcompat | 1.8.0（minCompileSdk=34） | 实测 AAR 元数据 |
| OkHttp | 5.5.0 | Maven Central |
| kotlinx-serialization-json | 1.11.0 | Maven Central |
| DataStore | 1.2.1 | maven.google.com |
| ML Kit 中文 OCR | 16.0.1（bundled） | maven.google.com |
| security-crypto | 1.1.0（仅作备选，已弃用） | maven.google.com |

> 依赖注入用**手写 ServiceLocator**，不引 Hilt：本项目类数量不到 30 个，Hilt 的注解处理会让构建更慢、报错更难读，收益为负。
> 版本组合已由冒烟构建**实测锁定**（详见 `TOOLCHAIN.md`）。其中两条与 AGP 8 时代的主流教程不同，是踩坑得来的：
> 1. **AGP 9.4.0 硬性要求 Gradle ≥ 9.6.0**（用 9.4.0 直接构建失败）；
> 2. **AGP 9 内置 Kotlin**，不能再声明 `org.jetbrains.kotlin.android` 插件；`android.kotlinOptions{}` 要改成 `kotlin{ compilerOptions{} }`，而 `jvmTarget` 默认跟随 `compileOptions.targetCompatibility`，通常根本不用配。
> 另外 Gradle 发行版**必须走镜像**：`services.gradle.org` 会 307 重定向到被墙的 GitHub Releases。
> 3. **Compose 仍要显式声明编译器插件**：AGP 9 内置的是 Kotlin 编译器，不是 Compose 编译器。只开 `buildFeatures { compose = true }` 会报 `Starting in Kotlin 2.0, the Compose Compiler Gradle plugin is required`；实测必须同时加 `id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"`（与内置 Kotlin 不冲突，能构建通过）。

---

## 8. 构建与真机调试链路

WSL 里没有 USB 直通、也没有 `/dev/kvm`（详见 `ENV_CHECK.md`），所以调试走 Windows 侧 adb。你已确认有真机可 USB 连 Windows。

**推荐接法（先简单、后顺滑）**：

1. **简单可靠**：`./gradlew assembleDebug` 出 APK → 复制到 `/mnt/c/` 下 → WSL 里直接调
   ```bash
   "/mnt/c/Program Files/ASUS/GlideX/adb.exe" install -r /mnt/c/tmp/app-debug.apk
   ```
   （已验证：该 adb 可用，版本 1.0.41 / platform-tools 31.0.2）
2. **顺手但需配置**：Windows 侧 `adb.exe -a nodaemon server`，WSL 里 `ADB_SERVER_SOCKET=tcp:192.168.192.1:5037`，之后 `gradlew installDebug` 一条龙（需 Windows 防火墙放行 5037 入站）

**Gradle 资源限制**（本机 7.4 G 内存，必须设）：`gradle.properties` 里 `org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m`、`org.gradle.workers.max=4`、开启 `org.gradle.parallel` 与 `org.gradle.caching`。

**网络限制（已实测，完整清单见 `TOOLCHAIN.md`）**：`github.com` 与 `developer.android.com` 均不可达，因此 **Gradle 发行版必须走镜像**——`services.gradle.org` 会把下载 307 重定向到 GitHub Releases，wrapper 于是 `Connection timed out`；换成华为云镜像后正常（9.6 MB/s）。
好消息有两个：**`developer.android.google.cn`（Google 中国镜像）可达**，Android 官方文档可以正常查阅，不必只靠搜索摘要；**Maven 系全部直连可用**（`dl.google.com`、`maven.google.com`、`repo1.maven.org`、`plugins.gradle.org`），依赖解析不需要镜像。

---

## 9. 里程碑拆分

| 里程碑 | 内容 | 验收标准 |
|---|---|---|
| **M0 环境** ✅ **已完成** | JDK 21 + Android SDK 全局装到 `/opt/android-sdk`，最小项目构建成功（详见 `TOOLCHAIN.md`） | `./gradlew assembleDebug` 通过并产出 6.4 MB APK ✅；`adb devices` 看到手机 ⬜ 待插真机 |
| **M1 悬浮窗** ✅ **真机验证通过** | `OverlayService`（前台服务 + 常驻通知 + 通知栏"关闭"）、`FloatingBubbleView`（自绘悬浮球：拖动/松手吸边/点击）、`MainActivity`（权限引导 + 总开关）、位置记忆 | 其他应用上层稳定显示、切后台不消失、开关能彻底关掉（详见 §12） |
| **M2 截图 + 选区** ✅ **真机验证通过** | `ProjectionSession`（一次授权、会话常驻、持续排空保留最新帧）、`ConsentActivity`（无界面授权中转） | 连续 3 次抓帧不再弹授权；滚动+静置 8 秒后抓帧与真实屏幕逐像素差异 2.7%（详见 §12） |
| **M3 打通视觉识别** 🟡 **代码完成，待真机验证** | `RegionSelectView`/`RegionSelectActivity`（拖框选区）、`ImagePipeline`（裁剪→缩放→JPEG→base64）、`SecureKeyStore`（Keystore AES-GCM 加密存 Key）、`SettingsActivity`（Key / 通道 / 深度思考 / 自定义提示词）、`DeepSeekClient`（HttpURLConnection）、`PromptBuilder` + `AnswerParser`、`AnswerCardOverlay`（题干回显/答案/解析/置信度/复制） | 真机跑通：拖框 → 识别 → 答案卡片；Key 错误/断网/余额不足都有可操作提示。**SSE 流式输出留到 M4**（当前是同步请求 + loading 卡片） |
| **M4 通道切换 + 流式 + 历史** 🟡 **OCR 通道已完成** | ✅ ML Kit 中文 OCR（bundled，离线，不依赖 GMS）+ 设置页通道切换 + `SolveEngine.solveText` 文本通道；⬜ SSE 流式输出；⬜ 历史记录 | OCR 通道真机跑通：离线出字 → 文本交模型 → 模型如实指出 OCR 错字（"申诗/载屏/設置"）并降低结论可靠性 ✅；⬜ 流式打字机；⬜ 历史记录 |
| **M5 体验打磨** | 追问多轮（同窗口带上下文继续问，见用户需求第 5 点）、历史记录、思考模式三档、错误重试、开屏引导 | 连续使用 30 分钟不崩、不卡、不烫手 |

**M0–M2 已真机验证通过，M3 代码完成待验收**。M3 的实现取舍：先用同步请求 + "正在识题…"卡片保证正确性，SSE 流式打字机效果放到 M4 与 OCR 通道一起做。

---

## 10. 风险清单（按优先级）

| # | 风险 | 影响 | 应对 / 验证方式 |
|---|---|---|---|
| 1 | **持续捕获会话能否长期存活**（Android 14+ 每次会话都要重新征得同意、授权 Intent 不可复用；部分 OEM 还会主动杀投影） | 产品可用性 | M2 首件事：真机验证「授权一次 → 连续抓帧能用多久、锁屏/切后台/长时间静止后是否被断」。被断时靠 `MediaProjection.Callback.onStop()` 引导重新授权，而不是退回"每次搜题都弹授权" |
| 1b | **持续捕获拿到旧帧**（ImageReader 缓冲填满导致停止产帧） | 识题结果错误、且很难复现 | 按 §4.2 持续排空并保留最新帧；M2 加一条日志（帧时间戳 vs 点击时间）在真机上确认延迟 |
| 2 | FGS 类型/启动顺序不对 → `SecurityException` | 功能直接不可用 | 严格按 §4.2 顺序：`startForeground` → `getMediaProjection` → 注册 Callback |
| 3 | 系统回收投影（用户点"停止共享"）后应用状态错乱 | 崩溃/黑屏 | 在 `MediaProjection.Callback.onStop()` 里统一回 `Idle` 并释放资源 |
| 4 | 安卓 15+ 部分屏幕共享导致抓帧尺寸 ≠ 屏幕尺寸 | 选区坐标错位 | 一律用 `ImageReader` 实际尺寸做坐标基准与 clamp |
| 5 | 连续搜题导致 Bitmap 泄漏 / OOM | 崩溃 | 每次会话结束 `recycle()`；抓帧只保留最新一帧；`inSampleSize` 降采样 |
| 6 | API Key 被反编译提取 | 资费损失 | Keystore 加密存储；不上传代码仓库；必要时加 App 内"额度提醒" |
| 7 | 模型幻觉（编题、编答案） | 正确性 | JSON 输出 + `question` 原文回显 + 低置信度提示 + 禁止编题的系统提示词 |
| 8 | 视觉 token 成本随截图分辨率上升 | 费用 | 长边压到 1600px、JPEG 80；默认"均衡"思考档；历史追问限制轮数 |
| 9 | Android 16 对截屏/悬浮窗的进一步收紧 | 兼容性 | targetSdk 36 一次性适配，真机必测；关注 SDK 的 `behavior-changes-16` |

---

## 11. 已确认的决策（你已拍板）

| # | 决策项 | 结论 |
|---|---|---|
| 1 | **包名** | `com.fragranstea.screensolve` |
| 2 | **界面语言** | 中文为默认，**同时支持英文**（资源分 `values/` 与 `values-en/`，不做运行时切换 UI，跟随系统语言） |
| 3 | **识题默认通道** | **默认视觉直传**（`deepseek-flash` 多模态）；OCR 通道在设置页可切换 |
| 4 | **答案风格** | **提示词用户可自定义**；默认输出「答案 + 分步解析」（详见 §4.7） |
| 5 | **答案自动复制到剪贴板等增强** | **留到后续版本**，MVP 不做 |
| 6 | **工具链安装方式** | ✅ 已完成：JDK 21 走 apt，Android SDK 由我装到全局 `/opt/android-sdk`（详见 `TOOLCHAIN.md`） |
| 7 | **调试设备** | Android 真机，USB 连 Windows，经 Windows 侧 adb 安装 |

### 仍未定、可以边做边定的小项
- Windows 侧是否换新版 platform-tools（现在是 GlideX 自带的 31.0.2，偏老）
- 历史记录用 Room 还是先用 JSON 文件（MVP 建议 JSON，省一个依赖）
- 是否要"答案自动复制"之外的下一批增强（悬浮球透明度、快捷方式、通知栏快捷入口…）

---

## 12. 真机验证记录

**测试机**：HONOR `DIO-AN00`，**Android 12 / API 31**，1080×2388 @ 480dpi，arm64-v8a。
（经 Windows 侧 adb 37.0.1 安装，见 `TOOLCHAIN.md`）

### M1 验收（全部通过）
| 项 | 结果 |
|---|---|
| 悬浮窗权限跳转 | ✅ 点击后进入 `Settings$OverlaySettingsActivity` |
| 悬浮球显示 | ✅ `ty=APPLICATION_OVERLAY`，`frame=[900,895,1068,1063]`，`visible=true` |
| 前台服务 + 通知 | ✅ `isForeground=true`、通知 id=1001 已发出 |
| 总开关 | ✅ 按钮文案与「截屏会话」状态随服务状态实时更新 |

### M2 验收（全部通过）
| 项 | 结果 / 证据 |
|---|---|
| 授权与会话建立 | ✅ `MediaProjectionPermissionActivity` 拉起并完成，`dumpsys media_projection` 显示 `TYPE_SCREEN_CAPTURE` |
| VirtualDisplay 尺寸 | ✅ 日志 `create Virtual Display [screensolve-capture] 1080x2388`，与屏幕物理分辨率一致 |
| **一次授权多次抓帧** | ✅ 连续 3 次点击均成功：`frame=1 → 31 → 62`，未再弹授权 |
| **帧是否新鲜（内容级验证）** | ✅ 滚动主界面 + 静置 8 秒后点击，把预览卡片里渲染的截图抠出（473×1046）缩放回 1080×2388 与点击前真实屏幕逐像素比对：**平均差异 6.91/255（2.7%）**，差异仅来自缩放重采样 → 抓到的就是当前屏幕 |
| 截图里不含自身悬浮球 | ✅ `fresh=true`（隐藏悬浮球后等到了新帧） |
| 释放会话 | ✅ `dumpsys media_projection` 变为 `null`，界面显示"未建立" |
| 释放后重新授权 | ✅ 授权页再次出现，`frame` 从 1 重新开始 |
| 系统授权框的实际表现 | 授权页由系统弹出，**需要用户手动点确认**（已与用户确认，不是 ROM 自动通过）。用户点完后会话建立，`frame` 从 1 开始 |

### 本轮修掉的 3 个真 bug（都是真机才暴露的）
1. **持续排空缺失会拿到旧帧** —— 设计阶段已预判并实现（见 §4.2），真机日志 `fresh=true` 证实生效。
2. **预览卡片遮挡悬浮球** —— 卡片原为 86% 宽居中，正好盖住右侧悬浮球，导致"再抓一次"点在卡片上没反应。改为 72% 宽并停靠在悬浮球的**另一侧**。
3. **预览窗口泄漏** —— `show()` 里调用 `remove()`，而 `remove()` 会回调 `onClosed` 把宿主的引用置空，结果每次抓帧都残留一个悬浮窗（实测同时存在 2 个卡片）。改为内部 `detach()`（不通知宿主）+ 用户关闭走 `remove()`。修复后连续 3 次抓帧窗口数稳定为 2（悬浮球 + 1 预览）。

### M3 验收（代码路径全部跑通，只差真实 Key 的 200 响应）
全流程由 adb 驱动完成（`input tap/swipe` + `uiautomator dump` 定位控件），日志为证据：

| 项 | 结果 / 证据 |
|---|---|
| 悬浮球 → 授权 → 选区 | ✅ `MediaProjectionPermissionActivity` 弹出并确认 → `RegionSelectActivity` 前台 |
| 拖框 → 确认 → 裁剪压缩 | ✅ 日志 `开始识题 jpeg=43889 bytes`（1080×2388 全屏框选后 43 KB） |
| **请求真的到达服务端** | ✅ 用假 Key 得到服务端真实回包：`INVALID_KEY / Authentication Fails, Your api key: **** is invalid (request_id: 5a436f9c-...)`。服务端返回的是 **401 而不是 400**，说明接口地址、鉴权头、JSON 请求体结构、TLS 链路全部正确 |
| 未填 Key 的分支 | ✅ `识题失败 error=NO_API_KEY`，卡片显示"回主界面 → 设置填入 Key" |
| Key 加密落盘 | ✅ `run-as ... cat shared_prefs/secure_settings.xml` 只有 `<string name="api_key_blob">/9L9bj…==</string>`（12 字节 IV + GCM 密文），**无 `sk-` 明文** |
| 清除 Key | ✅ 点"清除已保存的 KEY" 后文件变成 `<map />`，界面回到"未设置" |
| 会话跨 UI 导航存活 | ✅ 折腾设置页/选区/卡片期间 `frame` 累加到 552，始终 `fresh=true`，未再弹授权 |

**未验证**：真实 Key 的 200 响应（需要用户自己的 Key，不经过 agent）—— 这是唯一剩余项。

### M3 暴露的两个 UX 问题（待优化，不影响功能）
1. **答案卡片会盖住应用自身 UI**：卡片是 72%×60% 的居中悬浮窗，覆盖主界面的"设置"按钮等控件，用户需先关卡片才能点下面。可选改进：点击卡片外部关闭 / 应用回到前台时自动收起。
2. **设置页软键盘遮挡"保存"按钮**：输入 Key 后按钮被键盘盖住，需手动收起键盘或滚动。改进：输入完成自动收键盘，或把保存按钮固定到底部。

### ⚠️ 本机未覆盖、换机需重验的路径
测试机是 Android 12，以下「仅 Android 13/14+ 生效」的代码路径**未被真机验证**（代码已实现，但没跑过）：
- Android 14+：`startForeground` 带 `mediaProjection` 类型的**顺序要求**（先 startForeground 再 getMediaProjection）
- Android 13+：`POST_NOTIFICATIONS` 运行时权限申请
- Android 15+：部分屏幕共享导致抓帧尺寸小于全屏
- Android 14+：`getMediaProjection` token 不可复用、必须注册 `MediaProjection.Callback` 的实际表现

---

## 附：本方案中的关键事实来源

- DeepSeek 视觉能力与传图格式：<https://api-docs.deepseek.com/guides/vision/>
- DeepSeek 模型、价格、视觉支持矩阵：<https://api-docs.deepseek.com/quick_start/pricing>
- DeepSeek 思考模式开关与 `reasoning_content`：<https://api-docs.deepseek.com/guides/thinking_mode>
- Android 14 每次捕获会话都需征得同意、不可复用授权 Intent：<https://developer.android.com/about/versions/14/behavior-changes-14>
- Android 15 部分屏幕共享：<https://developer.android.com/about/versions/15/features>
- Android 14 前台服务类型 `mediaProjection` 必填：<https://developer.android.com/about/versions/14/changes/fgs-types-required>
- EncryptedSharedPreferences 已弃用与迁移：<https://proandroiddev.com/goodbye-encryptedsharedpreferences-a-2026-migration-guide-4b819b4a537a>、[社区 fork](https://github.com/ed-george/encrypted-shared-preferences)
- ML Kit 中文文本识别：<https://developers.google.com/ml-kit/vision/text-recognition/v2/android>
