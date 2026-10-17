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
│  ├─ HistoryStore.kt         最近 10 条 + filesDir/history/<id>.jpg
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
│  ├─ overlay/AnswerCardOverlay.kt     答案卡片：贴底满宽、层级排版、高度锚点、流式思考
│  ├─ overlay/FloatingBubbleView.kt    自绘悬浮球
│  ├─ region/RegionSelectView.kt       预选框 + 四角拖拽 + 框内平移 + 框外重画
│  ├─ region/RegionSelectActivity.kt   选区界面
│  ├─ history/HistoryActivity.kt       历史列表 + 详情弹窗 + 全屏看原图
│  ├─ settings/SettingsActivity.kt     Key / 通道 / 深度思考 / 自定义提示词
│  └─ main/MainActivity.kt             权限引导 + 总开关 + 会话状态 + 入口
└─ util/
   ├─ MathTextRenderer.kt     **公式渲染的核心**：Markwon 管 markdown，$...$ 用 jlatexmath 画成 ImageSpan
   └─ DisplayUtils.kt         屏幕尺寸（用 maximumWindowMetrics，保证等于整屏）
```

---

## 4. 已验证 & 已知问题

### 已验证（真机 HONOR DIO-AN00 / Android 12 / 1080×2388）
- 一次授权后连续抓帧，`fresh=true`；滚动+静置 8 秒后抓帧与真实屏幕逐像素差异 2.7%
- 拖四角改选区（`951×1815 → 928×1681`）；答案卡片高度锚点（窗口 `1385 → 1586px`）
- 严格 LaTeX 输出 + 端上渲染：`$\frac{8}{3}$`、`$\int_{0}^{2}$`、`1-e^{-1}` 均正确排版
- Key 加密落盘（只有密文）；改其它设置保存**不会**覆盖 Key
- 历史详情 LaTeX 渲染正常；全屏看原图可打开
- lite release 1.37 MB / full release 14.5 MB（差异只有 ML Kit）

### 已知问题（按优先级）
1. **思考结束时会输出一堆 `null`**（用户反馈，不影响使用）
   根因：Android `org.json` 的 `optString` 遇到 JSON `null` 会返回字符串 `"null"`。
   流结束时 DeepSeek 可能发 `delta.reasoning_content = null` → 被当成文本拼进思考显示。
   修复：取 `reasoning_content` 时判断 `delta.isNull("reasoning_content")`，
   或过滤 `"null"` 字符串；`chatCompletionStream` 里两处 `optString` 都要处理。
2. **历史详情是弹窗、且"查看题目图"返回后会回到列表**（反逻辑）。用户期望：**从历史直接进入会话视图**，
   在里面看图、追问、复制等一切操作（见 §5.3）。
3. **多题同屏会降低质量**。已加提示词要求逐题作答，但用户实测质量仍不如单题。
   建议：选区界面提示"建议只框一道题"；提示词改为"多题时只答最完整的一道并在 conclusion 说明"。
4. **全屏原图不能缩放**（未做，低优先级）。
5. 答案卡片里的分式偏大（22sp 的公式抢戏）；公式后紧跟的"。"会被挤到下一行（排版打磨项）。

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

1. **修 `null` 输出**（§4.1，5 行代码）
2. **选区提示单题**：`region_hint_*` 文案加"建议只框一道题"；提示词多题时只答最完整的一道
3. **M5 多轮追问**（§5，先做悬浮卡片里的追问，再做历史 → 会话视图）
4. 历史详情从弹窗改为"进入会话视图"（消除"查看图片后回到目录"的反逻辑）
5. 图片缩放、公式字号微调（打磨项）

---

## 9. M5 当前状态（重要，接手先看这里）

### 已完成（代码已写、编译通过、已装机）
- `recognize/SolveSession.kt`：会话持有 API 的 messages 数组（system → user(首图/OCR文本) → assistant(原始JSON) → 追问…），
  assistant 存**模型原始输出**，超过 5 轮追问后丢弃最早的追问（保留首轮三条锚点）
- `SolveEngine`：`newVisionSession / newTextSession / followUp(messages, onReasoning)`
- `OverlayService`：识题改走会话（`activeSession`），成功后 `appendAssistant(answer.raw)`；
  新增 `handleFollowUp(question)`（流式 + 思考显示）；释放会话时清空 `activeSession`
- `AnswerCardOverlay`：底部加追问输入行（EditText + 追问按钮），有答案后才显示；
  `setImeEnabled()` 在点击时切换 `FLAG_NOT_FOCUSABLE` 并 `updateViewLayout`（悬浮窗做输入的关键）
- 字符串：`answer_follow_up_hint` / `answer_follow_up_send`

### 未解决（下一轮第一件事）
**追问输入行在真机上没出现**：`showAnswer` 里确实执行了 `followUpRow?.visibility = View.VISIBLE`
（代码已确认），但截图里卡片在"复制答案/关闭"按钮下方就结束了。
怀疑点（按可能性排序）：
1. 卡片窗口高度不足，输入行被排到窗口可视区之外被裁掉 —— 先把卡片默认高度从 0.58 提到 0.70 试
2. `inputRow` 的测量高度为 0（EditText 用了 `LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)` 的 width weight）
3. 卡片参数高度与实际不符（截图里卡片看起来只有 ~0.45 屏高，而不是 0.58，需要确认 `params.height` 实际值）

调试建议：用 `dumpsys window windows` 读卡片窗口的 `mAttrs=(...)(宽x高)`，与 `(bounds.height()*0.58)` 对比；
再在 `showAnswer` 里打一行 `Log.i` 输出 `followUpRow?.height` 和 `root?.height`。

### 还没做
- 历史点击 → 进入会话视图（复用同一套会话模型，见 §5.3）
- 追问结果入历史（现在只有首轮落库）
- 图片缩放、公式字号微调
