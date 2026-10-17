package com.fragranstea.screensolve.recognize

/** 识题失败的原因。UI 层据此给出可操作的提示，而不是一句"出错了"。 */
enum class SolveError {
    /** 还没在设置页填 API Key */
    NO_API_KEY,

    /** 401：Key 无效或已被删除 */
    INVALID_KEY,

    /** 402：余额不足 */
    NO_BALANCE,

    /** 429：请求过快 */
    RATE_LIMIT,

    /** 连接失败、DNS、断网等 */
    NETWORK,

    /** 读超时（开深度思考时可能较久） */
    TIMEOUT,

    /** 服务端返回成功但没有内容 */
    EMPTY_RESPONSE,

    /** 返回内容不是预期结构 */
    BAD_RESPONSE,

    /** 413 等请求体问题 */
    REQUEST_TOO_LARGE,

    UNKNOWN,
}

/** 一次识题的最终结果。 */
sealed interface SolveOutcome {
    data class Success(val answer: Answer) : SolveOutcome
    data class Failure(val error: SolveError, val detail: String? = null) : SolveOutcome
}

/**
 * 结构化的答案。
 *
 * `question` 字段是搜题产品最关键的一道 UX 防线：把模型读到的题干原文回显给用户，
 * 一眼就能看出"是不是识别错了题"。识别错时答案必然错，让用户早点发现。
 */
data class Answer(
    val subject: String,
    val question: String,
    val answer: String,
    val steps: List<String>,
    val conclusion: String,
    val knowledgePoints: List<String>,
    val confidence: Double,
    /** 模型原始输出，解析失败时用来兜底展示 */
    val raw: String,
    /**
     * true = 模型没有按 JSON 结构化输出，整段原文直接当回答展示。
     *
     * 追问（多轮对话）里模型经常直接写一段 markdown，此时不该显示"置信度 0%"，
     * 而要换个中性标题、并放宽正文字号。
     */
    val plainText: Boolean = false,
) {
    /** 置信度偏低时提示用户核对题干。 */
    val lowConfidence: Boolean get() = !plainText && confidence in 0.0..0.55

    /**
     * 多轮追问里"一条回复"的文本（存历史、渲染气泡都用它）。
     *
     * 和 [toPlainText] 的区别：追问回复不需要再回显题目 ——
     * 提问已经单独成一条气泡了，回答里再写一遍"题目：xxx 答案：xxx"是重复的。
     */
    fun toReplyText(): String = buildString {
        if (plainText) {
            append(answer)
            return@buildString
        }
        append(answer)
        if (steps.isNotEmpty()) {
            appendLine()
            steps.forEachIndexed { index, step -> appendLine("${index + 1}. $step") }
        }
        if (conclusion.isNotBlank()) {
            appendLine()
            append(conclusion)
        }
    }.trim()

    /** 复制到剪贴板/分享用的纯文本。 */
    fun toPlainText(): String = buildString {
        // 追问回复本身就是一段完整的话，不需要再加"答案："这种前缀
        if (plainText) {
            append(answer)
            return@buildString
        }
        if (question.isNotBlank()) appendLine("题目：$question")
        appendLine("答案：$answer")
        if (steps.isNotEmpty()) {
            appendLine()
            steps.forEachIndexed { index, step -> appendLine("${index + 1}. $step") }
        }
        if (conclusion.isNotBlank()) {
            appendLine()
            appendLine(conclusion)
        }
    }.trim()
}
