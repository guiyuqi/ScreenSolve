# 交接文档（HANDOVER）

> 面向"下一个接手的人/会话"。读完这份就能无缝继续，不必回翻聊天记录。
> 最后更新：v1.1.3（悬浮球双样式 / 选区悬浮窗 / 拍照模式 / 外观三档 / 图片旋转 / 相机补光）。

---

## 0. 发布状态（1.1.3）

**1.1.3：长公式改横向滑动（已真机验证，待发布）**

真机验证（lite，HONOR DIO-AN00 / Android 12）：
- 长公式**保持字号、可横向滑动**，滚动条观感正常；短公式排版与气泡收缩行为未受影响 ✅
- 日志确认横滑生效：`宽=1908/1134/977 > 阈值=636` 的三条长公式都单独成块
- LaTeX 命令兜底生效：`\pmod{9}` → `(\mathrm{mod}\ 9)`，`136 ≡ 1 (mod 9)` 正常显示
- 设备已切回**正式签名 release 包**（`flags` 无 `DEBUGGABLE`，渲染日志 0 条）✅
- `v1.1.3` tag 已移动到最终提交 `2eaa4ee`（原先停在 0bf4885，不含 \pmod 修复与日志）

- 需求来源：用户反馈"长公式压缩在一屏里字符太小"。做法对齐 DeepSeek App——保持字号、公式自己横滑。
- 新增 `util/MathContent.kt`：把内容拆成「文本块 + 公式块」铺进 `LinearLayout`；
  公式自然宽度 > 可用宽度 × 0.92 时单独成块，用 `HorizontalScrollView + ImageView(FIT_START)` 显示，不缩放。
- `MathTextRenderer` 新增 `mathMatches()`（按位置取公式）、`drawableNaturalSize()`（不缩放出图）、`toBitmap()`。
- 接入点（**两处都改了**）：`AnswerCardOverlay`（题目/答案/步骤改成容器 + `renderBox`）、
  `ConversationActivity`（`addSection` 段落与 `addBubble` 气泡；气泡用 `fillWidth=false` 保持收缩观感）。
- **待真机确认**：
  1. 悬浮卡片里长公式能左右滑动、短公式排版不变；
  2. 历史会话里同上；气泡宽度对短文本仍然收缩；
  3. 公式块高度正常、滚动条不影响阅读；文字与公式字号协调。
- 已知取舍：段落被长公式切开后，跨段的 markdown 结构（有序列表）不再连续；每个公式会先按目标字号
  画一次来量宽度（公式少，开销可接受）。

## 0.1 发布状态（1.1.2）

**1.1.2 修复 6 项（已真机回归，HONOR DIO-AN00 / Android 12）**

回归结论（2026-10-19 深夜，lite release 1.1.2 覆盖安装到 1.1.0，数据保留）：

| # | 项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 长公式被裁 | ✅ 修好 | 会话里 `a²−b²=(a−b)(a+b)=km(a+b)`、12 项同余链均完整，不再出气泡右边（长式子会被等比缩小到可容纳） |
| 2 | 图片旋转 | ✅ | 全屏看图右上角 ⟳，点一次转 90°，浮层不关闭 |
| 3 | 夜间输入框不可见 | ✅ | 夜间模式输入 `dark input check` 清晰可见 |
| 4 | 追问套用题目/答案模板 | ✅ | 卡片与历史会话的回复都是直接作答，不再有"题目：… 答案：…"前缀 |
| 5 | 外观三档 | ✅ | 设「夜间」主界面/历史/会话立即变暗；改回「跟随系统」后随系统 auto-night 变暗 |
| 6 | 相机手电 | ✅ | 点 💡 后 `dumpsys media.camera` 出现 `[TORCH ]`、画面变亮；退出取景页 `torch mode changed: false` |

顺带确认：拍照模式的选区**等比显示（上下黑边、不拉伸）**；装机走 UNC 路径可行（未再拷贝到 C 盘）。
~~遗留观察：极长公式被等比缩小~~ → 1.1.3 已改为横向滚动（保持字号）。

1. **历史会话里公式被截断**：`ConversationActivity` 传给 `MathTextRenderer` 的是气泡**外框**宽度，
   比内容宽度大 → 公式画出气泡右边。改为传内容宽度（外框 − 内边距），`MathTextRenderer.BLOCK_RATIO` 0.40 → 0.33。
2. **图片旋转**：`ImagePreview` 右上角加 **⟳ 旋转 90°**（`rotateBitmap()`，原图保留在内存里以便转回）。
3. **会话页输入框看不见字**：输入行背景自绘固定色、文字色跟随主题 → 夜间白底白字。
   现在 `setTextColor/setHintTextColor` 显式指定，颜色统一走 `R.color.{page_bg,surface,text_primary,text_hint,bubble_*}`。
4. **追问把追问当新题**：追问时追加 `PromptBuilder.followUpInstruction()`——
   只回答这一问、不重复题干、`question` 留空。两处入口（悬浮卡片 / 历史会话）都套上了。
5. **外观设置**：`SettingsStore.ThemeMode{SYSTEM,LIGHT,DARK}` + `ScreenSolveApp`（Application）里
   `AppCompatDelegate.setDefaultNightMode`；主题基类是 `Theme.AppCompat.DayNight.NoActionBar`，日/夜各一套颜色资源
   （`values/colors_history.xml`、`values-night/colors_history.xml`）。设置页「外观」三选一，保存即生效。
6. **相机补光**：`PhotoCaptureActivity` 右上角 💡 开关手电（`CameraControl.enableTorch`），
   无闪光灯设备给提示，退出页面自动关灯。

**产物**：lite release 2.28 MB / full release 16.70 MB / lite debug 11.8 MB / full debug 27.4 MB（versionCode 13）
**GitHub**：v1.0.0、v1.1.0、**v1.1.1 均已发布**；**v1.1.2 待发**（需要 Contents 写权限的 token，或用户自行 `./scripts/publish-release.sh`）。

## 0.1 发布状态（1.1.1）

**1.1.1 修复（代码已改、四端产物已构建；未在真机回归 —— 设备当时从 USB 掉线）**
- 选区视图 `RegionSelectView` 从"拉伸铺满视图"改为**等比缩放居中 + 黑边**：
  `dstRect` 改为 `RectF` 并新增 `computeDstRect()`，`selectionInBitmap()` 改为
  `(selRect - dstRect) * (位图尺寸/显示尺寸)`，预选框也改成落在图片区域内。
  影响面：拍照（4:3）以前在选区里会被横向拉伸；截屏因为比例正好一致看不出来。
- `PhotoCaptureActivity` 的 `ImageCapture` 目标分辨率 `2048×2048`（正方形）→ `2048×1536`（4:3，传感器原生比例）。
- 需要真机确认的点：拍照后选区里画面比例正常、裁剪结果与所见一致、截屏模式行为不变。

## 0.2 发布状态（1.1.0）

**1.1.0 新增（均已真机验证）**
1. **悬浮球两种样式**：`SettingsStore.BubbleStyle{sizeDp, visualSizeDp}` —— 默认 56dp 蓝色放大镜 / 极简白点（视觉 10dp、可点区域 28dp）。
   设置页切完立即生效（`OverlayService.restyle` → 重建悬浮窗，位置保持）。
2. **选区改成悬浮窗**（`RegionSelectOverlay`）：不再启动 Activity，点悬浮球**没有转场动画**、
   `mResumedActivity` 始终是用户的 App；答案卡片继续以悬浮窗盖在上面。
   旧 `RegionSelectActivity` 及其布局已删除。
3. **拍照模式**（`Source.CAMERA`）：设置里切换；点悬浮球进**应用内 CameraX 取景页**（`PhotoCaptureActivity`），
   拍完直接弹选区 → 识题。照片**只进内存、不写相册、不落临时文件**，只有裁好的题目图进历史。
   为什么不用系统相机：OEM 相机会额外往相册存一份（用户明确不要）、还有 ✕/✓ 确认页，且无法自动化回归。

**待办**：把 1.1.0 的 lite/full release 传到 GitHub Release（需要一次带 Contents 写权限的 token）。

## 0.1 发布状态（1.0.0，历史）

- **v1.0.0（versionCode 10）已发布**：当时 `lite` 1.79 MB / `full` 16.2 MB，均为 V2 签名（体积随后续版本变化，见 §0）。
- 正式包与调试包的界面差异由 `R.bool.is_debug_build` 控制（`app/src/debug/res/values/bools.xml` 覆盖为 true）：
  **"当前进度"区块、带版本号的调试版副标题只在 debug 包出现**，release 包副标题只有「悬浮窗截图搜题」。
- 发布前自查已过：manifest 无 `debuggable`、`allowBackup=false`、除启动页外无导出组件、
  权限只有悬浮窗/前台服务/通知/网络（full 版多一个 ML Kit 需要的 ACCESS_NETWORK_STATE）、
  不打印任何 Key 内容、release 包真机全链路（识题→公式→追问→历史→缩放）已验收。
- GitHub Release 已发布：<https://github.com/guiyuqi/ScreenSolve/releases/tag/v1.0.0>
  资产名 `ScreenSolve-lite-release.apk` / `ScreenSolve-full-release.apk`，
  平台上记录的 sha256 与本地产物一致（lite `15ca5137…be3a9`、full `fa574ccd…b632f57f`）。
- token 存放：`./scripts/set-token.sh` 一次性写入 `~/.config/screensolve/token`（600），
  发布脚本自动读取，不用每次手输；细节见 TOOLCHAIN「发布 token 存哪里」。
- 发布脚本：`scripts/publish-release.sh`（`GITHUB_TOKEN=... ./scripts/publish-release.sh`，
  需要 fine-grained token 的 **Contents: Read and write**；token 只经环境变量传递，不落文件）。
- 使用说明见 `README.md`。

## 0.5 许可证

MIT（`LICENSE`，Copyright (c) 2026 guiyuqi）。第三方依赖各自许可，清单见 `README.md` 末尾。

## 0.3 工作约定（重要）

- **构建产物只留在 WSL**（`app/build/outputs/apk/...`）。装机直接用 Windows 的 `adb.exe` +
  UNC 路径 `\\wsl.localhost\Ubuntu\home\fragranstea\dsh-workspace\ScreenSolve\app\build\outputs\apk\...`，
  **不要再把 apk 复制到 `/mnt/c/Users/FragransTea/`**（已经清理过一次）。
- 签名证书备份在 WSL：`~/screensolve-signing-backup/`（jks + keystore.properties + 说明）。
- **调试包可以复用发布证书**：`./gradlew assembleLiteDebug -PreleaseSignedDebug` →
  能覆盖安装到已装 release 的手机上（数据不丢），且仍是 debuggable、调试日志照开。
  但**发布包只能来自 `assemble*Release`**，绝不能拿这个 debug 包对外发布（带 DEBUGGABLE 标志）。
  细节与发版前校验命令见 `TOOLCHAIN.md`「调试包与发布包的签名」。
- 详见 `TOOLCHAIN.md` 末节。

## 1. 项目是什么

安卓悬浮窗截图搜题：悬浮球 → 截屏 → 拖框选区 → 调 DeepSeek 视觉模型 → 答案卡片（LaTeX 渲染）。
仓库：`github.com/guiyuqi/ScreenSolve`（SSH 走 Windows 代理推送，见 §6）。

**用户偏好（重要）**
- 体积要小、启动要快 → 已拆 lite/full 双版本，默认 lite（release **2.28 MB**，含 CameraX）
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
│  ├─ SettingsStore.kt        channel(VISION/OCR) / source(SCREEN/CAMERA) / bubbleStyle / themeMode / deep_thinking / custom_prompt
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
│  ├─ overlay/FloatingBubbleView.kt    自绘悬浮球（两种样式：默认蓝底 / 极简小白点）
│  ├─ region/RegionSelectView.kt       预选框 + 四角拖拽 + 框内平移 + 框外重画（等比显示 + 黑边）
│  ├─ region/RegionSelectOverlay.kt    **选区悬浮窗**（不启动 Activity，故无转场、不离开用户页面）
│  ├─ region/PhotoCaptureActivity.kt   拍照模式取景页（CameraX 自建相机 + 手电开关，照片只进内存）
│  ├─ history/HistoryActivity.kt       历史列表（点条目进会话视图）
│  ├─ history/ConversationActivity.kt  **会话视图**：看图 + 读答案 + 接着追问（重建 vision 会话）
│  ├─ settings/SettingsActivity.kt     Key / 通道 / 识题来源 / 悬浮球样式 / 外观 / 深度思考 / 提示词
│  └─ main/MainActivity.kt             权限引导 + 总开关 + 会话状态 + 入口
├─ ScreenSolveApp.kt          Application：把「跟随系统/白天/夜间」落到 AppCompatDelegate
└─ util/
   ├─ ImagePreview.kt         全屏看题目图（黑底 + 缩放 + 旋转 90° + 关闭）
   ├─ ZoomImageView.kt        双指/双击缩放、放大后拖动、边界回弹（不引 photoview）
   ├─ MathContent.kt          把内容切成「文本块 + 长公式块」铺进 LinearLayout（长公式横滑，见 §4.12）
   ├─ MathTextRenderer.kt     **公式渲染的核心**：整段 Markwon 管 markdown，公式换 U+FFFC 占位 → jlatexmath 出图 →
   │                          `BaselineMathSpan`（自绘 ReplacementSpan，撑行高避免裁字）+ 超宽等比缩放（详见 §9 踩坑）
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
- 历史详情 LaTeX 渲染正常；全屏看原图可打开，并支持**双指/双击缩放、放大后拖动**（已验证 2.5 倍放大后居中不偏移）
- 历史会话视图按聊天气泡排版：**自己问的靠右（浅蓝）、模型答的靠左（白底描边）**（坐标实测：用户气泡 x652-1002，回答气泡 x78-842）
- **full 版 OCR 通道真机回归**：ML Kit 正常出字（51 字），但**数学公式会丢运算符/上下限**
  （`(3x²+2x)dx` 被识别成 `(3x 2x) dx`），模型据此给出 20% 置信度的"无法识别"并说明缺什么 —— 行为正确，
  也印证了"数学题走视觉直传"这个默认选择是对的
- R8 keep 规则（`app/proguard-rules.pro`）：jlatexmath / Markwon 整包保留不混淆 ——
  它们靠类名与 assets 里的字体名工作，混淆后公式会画不出来。代价是 lite release 从 1.48 → 1.79 MB，
  这个交换值得：公式渲染是核心功能，300 KB 换"一定不炸"。
- **release 包真机全链路已验证**（lite 1.79 MB，V2 签名，R8 混淆）：
  识题成功 answer=2 / 置信度 99%，题干与解析里的公式全部正常渲染（说明 keep 规则有效，没被裁没被改名）、
  追问回复（"追问回复"标题 + 正常字重 + 公式）正常、历史会话视图气泡正常
  （实测提问气泡 `x 391–1002` 靠右、回答气泡 `x 78–842` 靠左）、全屏题图缩放正常。
- 冒烟开关 `-PsmokeTest`（包名加 `.smoke` 后缀）保留，用于"不卸载 debug 包"验证 R8 后能否启动。
- release 签名：`assembleLiteRelease` / `assembleFullRelease` 均为 V2 签名（CN=ScreenSolve），
  最终体积 **lite 2.28 MB / full 16.69 MB**（差额全在 ML Kit）

### 已知问题（按优先级）
1. **多题同屏会降低质量**。选区提示已加"建议只框一道题"，提示词要求多题时只答最完整的一道。
2. **full 版 OCR 对数学公式识别率低**（见上），别把它当数学题主通道；它的价值在"不发原图、省 token"。
3. 排版打磨：极少数情况下公式后的"。"仍会单独占一行（宽公式已改硬换行 + 吸收纯标点，多数场景已解决）。
4. 公式行高：`ImageSpan(ALIGN_BASELINE)` 不会为 `bounds.top>=0` 的图片扩行高，单行答案（如「2」）顶部会被裁；
   现已改用自绘 `BaselineMathSpan`（getSize 里把 `fm.ascent/top` 压到 `-图片高度`）。
5. **debug ↔ release 不能覆盖安装**（签名不同），切换必须 `adb uninstall`，
   会清掉已存 API Key 与历史 —— Android 机制，不是 bug。

---

## 5. M5：多轮追问（设计方案留档，已实现）

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
./gradlew assembleLiteRelease      # 2.28 MB（已签名）

# release 签名：仓库根部的 keystore.properties + keystore/ 一起决定（两者都不进 git）
# 缺失时 release 包不签名，但依然能编译 —— 换个机器 clone 下来不会卡住
# 冒烟测试（不覆盖已装的 debug 包）：
./gradlew assembleFullRelease -PsmokeTest   # 包名变成 ...screensolve.smoke

# 证书备份在 Windows 侧：C:\Users\FragransTea\ScreenSolve-签名备份\
# 丢了它就只能换包名重发（老用户数据全丢），务必留好
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

### 已完成（M5 收尾这一轮，真机验证）
- 全屏看题图支持缩放：`util/ZoomImageView`（双指/双击、放大后拖动、边界回弹）+ `util/ImagePreview`
  （黑底、顶部提示条、右上角关闭按钮 —— 放大后单击不关闭，必须留显式出口）
- 答案区公式按 0.85 倍渲染（追问回复 0.92），不再"抢戏"
- 历史会话里追问按气泡排版：问在右、答在左
- release 签名链路打通；`full` 版 OCR 通道回归完成（结论见 §4）

### 下一个里程碑（M6 候选）
- README 使用说明 + 截图
- 答案卡片支持"复制为 Markdown/纯文本"两种
- OCR 通道改为"视觉 + OCR 双通道结果互相校验"（可选，收益不确定，先不做）

### 不要动的东西
- `capture/` 的常驻抓帧 + `awaitFrameAfter`（抓帧新鲜度是靠这个保证的）
- `data/SecureKeyStore`：`save("")` 必须保持 no-op，`load()` 失败绝不自动清空
- 状态渲染入口 `showLoading/showAnswer/showError` 都负责 `followUpRow` 的可见性
