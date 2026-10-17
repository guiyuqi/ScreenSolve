package com.fragranstea.screensolve.recognize

/**
 * 提示词构造。
 *
 * 关键约束（见 ARCHITECTURE.md §4.7）：**输出格式骨架不允许被用户的自定义提示词覆盖**。
 * 用户改的只是"风格"（要不要解析、讲多细），一旦能改格式，JSON 解析层就会崩。
 *
 * 两个入口共用同一份骨架，区别只在"模型拿到的是什么"：
 * - [systemPrompt]：拿到屏幕截图 → 需要自己读字，所以强调"逐字抄题干与选项"
 * - [systemPromptForText]：拿到端侧 OCR 文本 → 只需回填原文，但要提醒 OCR 可能有错字
 */
object PromptBuilder {

    /**
     * `$` 在 Kotlin 原始字符串里会被当成模板起始，用它拼一下。
     * （LaTeX 必须用真正的 ASCII `$` 定界，卡片侧的数学引擎认这个。）
     */
    private const val D = "\$"

    private const val SCHEMA = """{
  "subject": "学科，如 数学/物理/英语",
  "question": "题目原文，逐字照抄图片中读到的内容",
  "answer": "最终答案，尽量简洁，如 A / x=3 / 12cm",
  "steps": ["第一步", "第二步"],
  "conclusion": "一句话总结思路",
  "knowledge_points": ["涉及的知识点"],
  "confidence": 0.9
}"""

    private val OUTPUT_RULES = """硬性要求：
1. 所有数学公式必须用标准 LaTeX 书写，并用 ${D} 定界：行内公式写作 ${D}...${D}，独立成行的公式写作 ${D}${D}...${D}${D}。
   正确示例：${D}\int_{0}^{2} x^{2}\,dx${D}、${D}e^{-1}${D}、${D}\frac{1}{2}${D}、${D}x_{1}=2${D}、${D}\sqrt{2}${D}、${D}\lim_{n\to\infty} a_{n}${D}。
   错误示例：∫_0^2（用 Unicode 字符硬拼）、1/2-e^(-1)、x1=2 —— 这类写法在卡片上会很难看，必须改用 LaTeX。
   卡片会用数学引擎把 LaTeX 真正渲染成公式，所以请直接写 LaTeX，不要为了"能看懂"而退化成纯文本。
2. 公式之外的文字可以用 markdown（**加粗**、`行内代码`、- 列表），卡片同样会渲染。
3. steps 每一项是一句完整的推导，句内不要换行，也不要自己编号；需要多行推导就拆成多条 steps。
4. 禁止使用需要额外宏包的多行环境（不要用 \begin{aligned}、\begin{cases}、\begin{array}），
   多行推导请拆成多条 steps；单行的分式、根号、积分、上下标、希腊字母、极限都可以正常使用。
5. confidence 是 0 到 1 的小数，表示你对作答结果的信心。"""

    /** 视觉直传：模型自己看截图。 */
    fun systemPrompt(style: String): String = buildString {
        appendLine("你是一个解题助手。用户会给你一张屏幕截图，截图中可能有一道题，也可能有多道题。")
        appendLine("请识别题目并作答。")
        appendLine()
        appendLine("你只能输出一个 JSON 对象，不要输出任何其它文字，不要用 markdown 代码块包裹。")
        appendLine("JSON 结构必须严格如下：")
        appendLine(SCHEMA)
        appendLine()
        appendLine("关于 question 字段：")
        appendLine("1. 必须完整抄下图片里的原文：题干 + 每一个选项的字母和内容都要逐字抄（A、B、C、D 各一行）。")
        appendLine("   不要改写、不要概括、不要省略选项 —— 用户要靠这段原文核对你是不是识别对了。")
        appendLine("2. 如果图片里选项显示不全、或根本没有选项（有些网页只渲染了题干），")
        appendLine("   就在 question 末尾注明「（图片中未显示选项）」，严禁自己补全或猜测选项。")
                appendLine("3. 【多题同屏】如果画面里有多道题，必须**逐题作答**，一道都不要漏：")
        appendLine("   - answer 按题号逐行列出，例如 \"1. D\\n2. B\\n3. 12cm\"；")
        appendLine("   - question 里把所有题的题干与选项都抄下来，用题号分隔；")
        appendLine("   - steps 也按题号分组，先写\"第1题：…\"再写\"第2题：…\"；")
        appendLine("   - 不要只回答第一道，也不要因为题多就合并成一个答案。")
        appendLine("4. 如果图片不清晰、或画面里没有题目，answer 填写\"无法识别\"，并在 conclusion 说明原因。")
        appendLine("   严禁编造题目或编造条件。")
        appendLine()
        appendLine(OUTPUT_RULES)
        appendStyle(style)
    }

    /** OCR 通道：模型拿到的是端侧识别出来的文本。 */
    fun systemPromptForText(style: String): String = buildString {
        appendLine("你是一个解题助手。用户会给你一段从屏幕截图里 OCR 出来的题目文本，可能包含一道或多道题。")
        appendLine()
        appendLine("你只能输出一个 JSON 对象，不要输出任何其它文字，不要用 markdown 代码块包裹。")
        appendLine("JSON 结构必须严格如下：")
        appendLine(SCHEMA)
        appendLine()
        appendLine("关于 question 字段：")
        appendLine("1. 把用户给你的这段题目文本原样回填（可以修正明显的 OCR 错字，但不要改写题意、不要省略选项）。")
        appendLine("2. 如果文本里选项不全，在末尾注明「（OCR 文本中选项不全）」，严禁自己补全选项内容。")
        appendLine("3. 如果文本明显残缺、无法确定题意，answer 填写\"无法识别\"，并在 conclusion 说明哪部分缺失。")
        appendLine("   严禁凭猜测补题。")
        appendLine()
        appendLine(OUTPUT_RULES)
        appendLine("6. OCR 文本可能有错字或缺字，若某处不确定，请在 conclusion 中提醒用户核对。")
        appendStyle(style)
    }

    /** 视觉直传时给用户的指令。 */
    fun userInstruction(): String = "请解答图片中的题目。"

    /** OCR 通道把识别出的文本交给模型。 */
    fun userInstructionForText(questionText: String): String = buildString {
        appendLine("下面是 OCR 识别出的题目文本，请作答：")
        appendLine("-----")
        appendLine(questionText.trim())
        appendLine("-----")
        appendLine("请按系统提示的 JSON 格式输出。")
    }

    private fun StringBuilder.appendStyle(style: String) {
        if (style.isBlank()) return
        appendLine()
        appendLine("解题风格要求（只影响表达方式，不得改变上面的 JSON 结构与硬性要求）：")
        appendLine(style.trim())
    }
}
