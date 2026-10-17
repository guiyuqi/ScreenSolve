package com.fragranstea.screensolve.recognize

import org.json.JSONObject

/**
 * 提示词构造。
 *
 * 关键约束（见 ARCHITECTURE.md §4.7）：**输出格式骨架不允许被用户的自定义提示词覆盖**。
 * 用户改的只是"风格"（要不要解析、讲多细），一旦能改格式，JSON 解析层就会崩。
 * 所以骨架固定在前，用户风格作为最后一段附加要求，并明确声明不得改变输出格式。
 */
object PromptBuilder {

    private const val SCHEMA = """{
  "subject": "学科，如 数学/物理/英语",
  "question": "题目原文，逐字照抄图片中读到的内容",
  "answer": "最终答案，尽量简洁，如 A / x=3 / 12cm",
  "steps": ["第一步", "第二步"],
  "conclusion": "一句话总结思路",
  "knowledge_points": ["涉及的知识点"],
  "confidence": 0.9
}"""

    /**
     * @param style 用户自定义的解题风格；为空则用默认风格。
     */
    fun systemPrompt(style: String): String = buildString {
        appendLine("你是一个解题助手。用户会给你一张屏幕截图，截图中包含一道题目。")
        appendLine("请识别题目并作答。")
        appendLine()
        appendLine("你只能输出一个 JSON 对象，不要输出任何其它文字，不要用 markdown 代码块包裹。")
        appendLine("JSON 结构必须严格如下：")
        appendLine(SCHEMA)
        appendLine()
        appendLine("硬性要求：")
        appendLine("1. question 必须是图片中读到的原文，不要改写、不要概括。")
        appendLine("2. 如果图片不清晰、或画面里没有题目，answer 填写\"无法识别\"，")
        appendLine("   并在 conclusion 中说明原因。严禁编造题目或编造条件。")
        appendLine("3. confidence 是 0 到 1 的小数，表示你对识别与作答结果的信心。")
        appendLine("4. 数学公式用 LaTeX 表示，例如 x^2-3x+2=0。")
        if (style.isNotBlank()) {
            appendLine()
            appendLine("解题风格要求（只影响表达方式，不得改变上面的 JSON 结构与硬性要求）：")
            appendLine(style.trim())
        }
    }

    /** 视觉直传时给用户的指令。 */
    fun userInstruction(): String = "请解答图片中的题目。"
}
