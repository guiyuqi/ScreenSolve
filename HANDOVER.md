# 交接文档（HANDOVER）

> 面向"下一个接手的人/会话"。读完这份就能无缝继续，不必回翻聊天记录。
> 最后更新：M4 完成、M5 待开发时。

---

## 1. 项目是什么

安卓悬浮窗截图搜题：悬浮球 → 截屏 → 拖框选区 → 调 DeepSeek 视觉模型 → 答案卡片（LaTeX 渲染）。
仓库：`github.com/guiyuqi/ScreenSolve`（SSH 走 Windows 代理推送，见 §6）。

**用户偏好（重要）**
- 体积要小、启动要快 → 已拆 lite/full 双版本，默认 lite（release **1.37 MB**）
- 答案要**严格 LaTeX + 端上渲染**，参考 latexlive.com 的效果
- 历史记录**连题目图一起存本地**（不上传）
- 提问界面希望都在**悬浮卡片**里完成（不要在多个页面间跳）
- 尽量**单题单查**：多题同屏会分散模型注意力、降低质量

---

## 2. 当前进度

| 里程碑 | 状态 |
|---|---|
| M0 环境 | ✅ JDK 21 + Android SDK 全局 `/opt/android-sdk`（详见 `TOOLCHAIN.md`） |
| M1 悬浮窗 | ✅ 真机验证通过（拖动/吸边/位置记忆/前台服务/通知） |
| M2 持续截屏 | ✅ 一次授权、连续抓帧（frame 计数持续增长，未再弹授权） |
| M3 选区+识题 | ✅ 拖框选区 → 裁剪压缩 → DeepSeek → 答案卡片 |
| M4-A 双通道 | ✅ lite（视觉直传）/ full（+ ML Kit 离线 OCR） |
| M4-B 流式 | ✅ SSE，`reasoning_content` 实时显示（正文 JSON 收完再渲染） |
| M4-C 历史 | ✅ 最近 10 条 + 本地题目图 |
| **M5 多轮追问** | ⬜ **下一步就做这个**（方案见 §5） |

---

## 3. 代码地图

```
app/src/main/java/com/fragranstea/screensolve/
├─ capture/
│  ├─ ProjectionSession.kt    持续投屏会话：必须"持续排空 ImageReader"否则拿到旧帧
│  ├─ ImagePipeline.kt        裁剪 → 长边1600 → JPEG85 → dataURL
│  └─ FrameHolder.kt          抓帧/裁剪结果的进程内交接（不塞 Intent，避免 TransactionTooLarge）
├─ data/
│  ├─ SecureKeyStore.kt       API Key：Android Keystore AES-GCM 加密，只存密文
│  ├─ SettingsStore.kt        channel(VISION/OCR) / deep_thinking / custom_prompt
│  ├─ HistoryStore.kt         最近 10 条 + filesDir/history/<id>.jpg + 追问 turns
│  └─ BubblePositionStore.kt  悬浮球位置记忆
├─ network/DeepSeekClient.kt  HttpURLConnection；chatCompletion + chatCompletionStream(SSE)
├─ recognize/
│  ├─ PromptBuilder.kt        系统提示词（视觉版 / OCR 版），骨架不可被用户覆盖
│  ├─ SolveEngine.kt          拼 messages → 流式调用 → 解析
│  ├─ AnswerParser.kt         JSON 解析（容错：剥 markdown 包裹、失败降级纯文本）
│  ├─ SolveOutcome.kt         SolveError 枚举 + Answer 数据类
│  └─ OcrRecognizer.kt        **只在 src/full 与 src/lite 各一份**（同名同签名）
├─ ui/
│  ├─ consent/ConsentActivity.kt       无界面授权中转（MediaProjection 只能 Activity 发起）
│  ├─ overlay/OverlayService.kt        核心：悬浮球 + 会话 + 抓帧 + 识题编排（~640 行）
│  ├─ overlay/AnswerCardOverlay.kt     答案卡片：贴底满宽、高度锚点、流式思考、追问输入行（窗口可聚焦）
│  ├─ overlay/FloatingBubbleView.kt    自绘悬浮球
│  ├─ region/RegionSelectView.kt       预选框 + 四角拖拽 + 框内平移 + 框外重画
│  ├─ region/RegionSelectActivity.kt   选区界面
│  ├─ history/HistoryActivity.kt       历史列表（点条目进会话视图）
│  ├─ history/ConversationActivity.kt  **会话视图**：看图 + 读答案 + 接着追问（重建 vision 会话）
│  ├─ settings/SettingsActivity.kt     Key / 通道 / 深度思考 / 自定义提示词
│  └─ main/MainActivity.kt             权限引导 + 总开关 + 会话状态 + 入口
└─ util/
   ├─ MathTextRenderer.kt     **公式渲染的核心**：整段 Markwon 管 markdown，公式换 token → jlatexmath ImageSpan（详见 §9 踩坑）
   └─ DisplayUtils.kt         屏幕尺寸（用 maximumWindowMetrics，保证等于整屏）
```

---

## 4. 已验证 & 已知问题

### 已验证（真机 HONOR DIO-AN00 / Android 12 / 1080×2388）
- 一次授权后连续抓帧，`fresh=true`；滚动+静置 8 秒后抓帧与真实屏幕逐像素差异 2.7%
- 拖四角改选区（`951×1815 → 928×1681`）；答案卡片高度锚点（窗口 `1385 → 1586px`）
- 严格 LaTeX 输出 + 端上渲染：分式、定积分（上下限）、`$\frac{8}{3}$`、`1-e^{-1}` 均正确排版
- **M5 追问**：卡片内输入行可见 → 点击弹出键盘 → 发送 → "追问回复"渲染，上下文正确
  （详见 §9）
- 渲染细节：markdown 加粗正常（不再出现字面量 `**`）、`$$ / \[ / \( / $` 四种定界符都能渲染、
  超宽公式自动缩放或改块级排版（不再被右边界裁掉）
- Key 加密落盘（只有密文）；改其它设置保存**不会**覆盖 Key
- 历史详情 LaTeX 渲染正常；全屏看原图可打开
- lite release 1.37 MB / full release 14.5 MB（差异只有 ML Kit）

### 已知问题（按优先级）
1. **历史详情还是弹窗**，且"查看题目图"返回后会回到列表（反逻辑）。目标：历史直接进会话视图
   （看图、追问、复制），见 §9「下一个里程碑」。
2. **追问没有入历史**：现在只有首轮问答落库，追问内容关掉卡片就没了。
3. **多题同屏会降低质量**。选区提示已加"建议只框一道题"，提示词要求多题时只答最完整的一道。
4. **全屏原图不能缩放**（未做，低优先级）。
5. 排版打磨：答案区公式偏大（22sp）；极少数情况下公式后的"。"会单独占一行。

---

## 5. M5：多轮追问（下一步实现方案）

### 5.1 目标
在**同一个答案卡片**里继续提问，模型能带着"首轮题目图 + 已得答案"的上下文回答。
历史记录点击后也进入同一个"会话视图"（复用同一套 UI），从那里可以看图、追问。

### 5.2 数据模型（建议）
```kotlin
// 会话消息，按顺序保存即可直接序列化成 API 的 messages 数组
sealed interface Turn {
    data class System(val text: String) : Turn
    data class UserText(val text: String) : Turn
    data class UserImage(val jpeg: ByteArray, val text: String) : Turn
    data class Assistant(val json: String) : Turn   // 原样回传模型输出的 JSON 字符串
}

class SolveSession(
    val systemPrompt: String,
    val turns: MutableList<Turn>,
)
```
要点：
- assistant 那轮要保存**模型原始 JSON 字符串**（不是解析后的对象），下一轮原样回传，模型才能延续结构
- 图片只在首轮出现一次；追问只发文本（省 token、也避免重复传图）
- 会话长度要控：超过 10 轮时丢掉最早的非首轮消息（首轮必须保留，否则模型不知道在解哪道题）

### 5.3 改造点
1. `SolveEngine` 增加：
   ```kotlin
   fun followUp(session: SolveSession, question: String, onReasoning: (String) -> Unit): SolveOutcome
   ```
   内部把 `session.turns` 与新的 user 文本拼成 messages，其余与 `execute` 相同。
2. `AnswerCardOverlay` 底部加输入行：`EditText` + 「追问」按钮（有答案后才显示）。
   - 注意 `FLAG_NOT_FOCUSABLE`：要能输入文字**必须**去掉这个 flag（或用 `FLAG_ALT_FOCUSABLE_IM`）。
     这是最容易踩的坑 —— 悬浮窗默认不接收输入法焦点。
   - 输入法弹起时卡片位置/高度要处理（`WindowManager` 不自动 resize）。
   - 建议：点输入框时把卡片临时切成 `FLAG_NOT_FOCUSABLE` 关闭 + `focusable` 打开，并加 `SOFT_INPUT_ADJUST_RESIZE`。
3. 流式复用：追问同样走 `chatCompletionStream`，思考过程继续用 `showReasoning`。
4. 历史记录：`HistoryStore` 目前只存最终答案；M5 需要**存会话**（至少首轮图 + 首轮问答 + 后续追问），
   建议 `history/<id>.json` 存会话、`history/<id>.jpg` 存图；点击历史 → 打开"会话视图"。
5. 会话视图建议做成 **Activity + 悬浮卡片的混合**：
   - 最简单可行：历史点击 → 进入一个全屏会话页（普通 Activity，列表形式的多轮气泡 + 输入框 + 顶部"查看题目图"），
     而不是复用悬浮窗（悬浮窗做输入太重）。
   - 悬浮窗里的追问保持轻量：只追加本轮问答，不展示完整历史。

### 5.4 验收清单
- [ ] 卡片里追问，模型能引用首轮题目作答（例如"再详细讲讲第二步"）
- [ ] 追问时输入法能正常输入、卡片不被键盘遮挡
- [ ] 历史点击进入会话视图，能看到题目图、能继续追问
- [ ] 会话超过 10 轮不会无限增长 token

---

## 6. 环境与工具链（踩过的坑都在这）

### 构建
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/opt/android-sdk
cd ~/dsh-workspace/ScreenSolve
./gradlew assembleLiteDebug        # 默认版本（只视觉）
./gradlew assembleFullDebug        # 含离线 OCR
./gradlew assembleLiteRelease      # 1.37 MB
```
- **AGP 9.4.0 + Gradle 9.7.1（≥9.6 硬要求）+ 内置 Kotlin**：不要再声明 `org.jetbrains.kotlin.android`
- `build.gradle.kts` 里 `abiFilters = arm64-v8a`：ML Kit 每个 ABI 带 10MB 级原生库，不裁会飙到 54MB
- **DSH 沙箱若切到 workspace-write，Gradle 会因 `~/.gradle` 只读而直接失败（报错还是空日志）**，
  需要在全权限下构建

### 真机（WSL 无 USB 直通，必须借 Windows 侧 adb）
```bash
cp app/build/outputs/apk/lite/debug/app-lite-debug.apk /mnt/c/Users/FragransTea/screensolve-lite.apk
"/mnt/c/Users/FragransTea/platform-tools/adb.exe" install -r "C:/Users/FragransTea/screensolve-lite.apk"
```
- 路径必须写 Windows 风格 `C:/...`（`/mnt/c/...` 会 "failed to stat"）
- 安装会弹系统确认框，**脚本要轮询 `uiautomator dump` 找「继续/安装」按钮并点**
- 截图：`adb.exe exec-out screencap -p > card.png`，然后 **用 `read_image` 直接看图**（当前模型支持视觉）

### 自动化辅助脚本（写在 /tmp，重启会丢，可按需重建）
- `/tmp/findbtn2.py <文本或resource-id>` → 输出控件中心坐标（子串匹配，取面积最小的候选）
- `/tmp/bubble_xy.py` → 从 `dumpsys window windows` 里解析悬浮球窗口的实际屏幕中心
  （**不要硬编码悬浮球坐标**，用户拖动过就变了；也不要从屏幕边缘开始滑动，会被系统当成返回手势）
- 输入中文用 `input text` 不支持，只用于 ASCII；空格要写成 `%s`
- 软键盘会挡住底部按钮：点按钮前先 `input keyevent KEYCODE_BACK` 收键盘

---

## 7. 关键设计决策（别轻易推翻）

1. **持续投屏会话**替代"每次点一下授权一次"：必须持续排空 `ImageReader`（只留最新帧），
   否则缓冲满后停止产帧，拿到的是几十秒前的旧图。
2. **隐藏悬浮球后等一个新帧再抓**：保证截图里没有自己的圆球。
3. **公式渲染自己实现**：Markwon 4.6.2 的 `ext-latex` 即便 `inlinesEnabled(true)` + 显式注册
   `MarkwonInlineParserPlugin` 仍不解析 `$...$`（静默失效），所以 Markwon 只做 markdown，
   `$...$` 交给 jlatexmath 画成 Drawable 再 ImageSpan 插入。
4. **JSON 输出 + 流式只显示思考**：正文是 JSON 碎片，对用户没意义；只把 `reasoning_content` 实时显示。
5. **Key 的安全边界**：Keystore 加密；解密失败**绝不能自动 clear**（踩过一次，把用户 Key 抹了）；
   `save("")` 也不删，删除只走 `clear()`。
6. **提示词骨架不可被用户覆盖**：用户自定义只影响"风格段"，JSON 结构与硬性要求固定，否则解析层崩。

---

## 8. 立刻能做的下一步（按顺序）

1. **历史 → 会话视图**（消除"看图后回到目录"的反逻辑；复用卡片会话模型）
2. **追问入历史**（`HistoryEntry` 加 `turns`），会话视图里能看到完整问答
3. 打磨：全屏原图缩放、公式字号、孤立标点
4. 发布准备：签名 release、`full` 版 OCR 通道回归、README 使用说明

---

## 9. M5 当前状态（重要，接手先看这里）

### 已完成并**真机验证**（HONOR DIO-AN00 / Android 12）
卡片内多轮追问全流程通了：
1. 答案卡片底部"接着问点什么…"输入行**可见**（上一轮"输入行不显示"是自己看错截图机位，
   `dumpsys window windows` 里 `mAttrs=(36,72)(1008x1385)`、`frame=[36,931][1044,2316]` 都是对的）
2. 点输入框弹出键盘：`mServedView=android.widget.EditText`、`mInputShown=true`；
   卡片按 `ADJUST_RESIZE` 让位到键盘上方（`frame` 变 `[36,99][1044,1484]`），输入行始终贴键盘
3. 输入 → 点「追问」→ 流式 → 卡片显示"追问回复"，上下文（首轮题目图 + 已生成答案）保留
4. 日志证据：`追问：why do we subtract F0 from F1` / `追问成功 answer=因为定积分只统计区间 [a,b] 内的净增量…`

### 这一轮修掉的关键坑（别再踩）
1. **悬浮窗想收输入法焦点，必须让窗口本身可聚焦**。
   原来在 EditText 的 `setOnClickListener` 里临时去掉 `FLAG_NOT_FOCUSABLE` 再 `requestFocus()`，
   同帧执行太早，键盘不弹。现在**创建卡片窗口时就不带 `FLAG_NOT_FOCUSABLE`**（保留 `NOT_TOUCH_MODAL`，
   卡片外触摸仍落到下层应用），`softInputMode = ADJUST_RESIZE | STATE_UNCHANGED`，点一下自然弹键盘。
2. **`ImageSpan` 不会给传入的 Drawable 设 bounds**。`DynamicDrawableSpan.getSize()` 取的是
   `drawable.bounds.right`，bounds 为 0 → 公式占位宽度 0 → 屏幕上只剩空白（且不报错）。
   包装 Drawable 时**必须在 init 里 `setBounds(0,0,w,h)`**（`util/MathTextRenderer.FittedDrawable`）。
   旧代码"能显示"只是因为 `JLatexMathDrawable` 自己带了初始 bounds。
3. **markdown 不能按公式切开分段渲染**。`**加粗 $x$ 加粗**` 被切成两段后 `**` 配对失败，
   屏幕上出现字面量 `**`。现在先把公式换成 `@@MATH0@@` token → 整段交给 Markwon → 再把 token 换成 ImageSpan。
4. **Markdown 里单个 `\n` 是软换行**（渲染成空格），块级公式前后必须用两个 `\n` 才能真正独立成段。
5. **ReplacementSpan 跟在文字后面时换行不可靠**，宽公式会冲出控件右边界被裁。
   规则：公式自身宽度 > 整行 40% 就改按块级（独占一段）排版（`MathTextRenderer.BLOCK_RATIO`）。
6. **`setTypeface(当前粗体实例, NORMAL)` 在部分 ROM 上不生效**，要直接 `typeface = Typeface.DEFAULT`。
7. 追问回复多半不是 JSON：`AnswerParser` 只在内容以 `{`（或 ```json 围栏）开头时才当 JSON 解析，
   否则走 `plainText` 分支，用"追问回复"标题 + 正常字重，而不是"置信度 0%"。

### 已完成（同轮，真机验证）
- **历史 → 会话视图**：`ui/history/ConversationActivity`。点历史条目直接进会话页：
  题目图缩略图（点击全屏）、题目/答案/解析、每轮追问、底部输入框可**继续追问**、一键复制全部。
  继续追问的做法：历史里存了本地题目图，`newVisionSession(jpeg)` 重建会话，再把已有问答回放进去。
- **追问入历史**：`HistoryEntry.turns`（每轮 question/answer/时间），
  `HistoryStore.appendTurn`；`OverlayService` 用 `activeHistoryId` 把追问写到同一条记录。
- 排版收敛：宽公式用 markdown 硬换行（不再分段）并吸收紧跟的纯标点，
  消掉了"公式后面孤零零一个句号"和"公式后突然一大块空白"。

### 下一个里程碑（M6 候选）
- 全屏原图支持双指缩放
- 答案区公式字号略小（22sp 偏大）
- 发布准备：release 签名、`full` 版 OCR 通道回归、README 使用说明

### 不要动的东西
- `capture/` 的常驻抓帧 + `awaitFrameAfter`（抓帧新鲜度是靠这个保证的）
- `data/SecureKeyStore`：`save("")` 必须保持 no-op，`load()` 失败绝不自动清空
- 状态渲染入口 `showLoading/showAnswer/showError` 都负责 `followUpRow` 的可见性
