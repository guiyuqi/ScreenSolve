package com.fragranstea.screensolve.recognize

/**
 * 把模型输出里的 LaTeX / markdown 转成**纯文本**，让答案卡片能直接读懂。
 *
 * 为什么需要这一层：提示词里已经要求模型用普通字符写公式，但模型（尤其是数学题）
 * 仍可能飘回 LaTeX —— 而卡片是 TextView，没有 LaTeX 渲染器，`$x_{1}=2$`、`\frac{1}{2}`、
 * `\begin{aligned}` 会原样显示成乱码。与其在 UI 里塞一个 LaTeX 引擎（体积大、还要求字体），
 * 不如在出口处清洗成可读的普通数学记号。两层的取舍见 ARCHITECTURE.md §4.7。
 */
object AnswerTextFormatter {

    /** 常见 LaTeX 命令 → Unicode 记号。注意顺序：长命令要排在它的前缀之前。 */
    private val SYMBOLS = listOf(
        "\\times" to "×", "\\cdot" to "·", "\\div" to "÷", "\\pm" to "±",
        "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥", "\\ge" to "≥",
        "\\neq" to "≠", "\\ne" to "≠", "\\approx" to "≈", "\\equiv" to "≡",
        "\\infty" to "∞", "\\degree" to "°",
        "\\rightarrow" to "→", "\\to" to "→", "\\Rightarrow" to "⇒",
        "\\leftarrow" to "←", "\\Leftarrow" to "⇐",
        "\\cup" to "∪", "\\cap" to "∩", "\\in" to "∈", "\\notin" to "∉",
        "\\subset" to "⊂", "\\emptyset" to "∅",
        "\\sum" to "Σ", "\\prod" to "Π", "\\int" to "∫",
        "\\pi" to "π", "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ",
        "\\delta" to "δ", "\\theta" to "θ", "\\lambda" to "λ", "\\mu" to "μ",
        "\\sigma" to "σ", "\\phi" to "φ", "\\omega" to "ω",
        "\\angle" to "∠", "\\triangle" to "△", "\\perp" to "⊥", "\\parallel" to "∥",
        "\\because" to "∵", "\\therefore" to "∴",
    )

    fun sanitize(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw

        // 1) 代码围栏与行内反引号（保留内容）
        s = s.replace(Regex("```[A-Za-z0-9_-]*"), "").replace("`", "")

        // 2) markdown 强调与标题标记
        s = s.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        s = s.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        s = s.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")   // 列表符号，步骤我们自己编号

        // 3) LaTeX 环境（aligned / cases / array …）
        s = s.replace(Regex("\\\\begin\\{[^}]*\\}(\\{[^}]*\\})?"), "")
        s = s.replace(Regex("\\\\end\\{[^}]*\\}"), "")

        // 4) 换行：LaTeX 里的 \\ 先变成真正的换行（必须早于其它反斜杠处理）
        s = s.replace("\\\\", "\n")

        // 5) 分式、根号、文本包裹
        s = s.replace(Regex("\\\\[dt]?frac\\s*\\{([^{}]*)\\}\\s*\\{([^{}]*)\\}"), "($1)/($2)")
        s = s.replace(Regex("\\\\sqrt\\s*\\{([^{}]*)\\}"), "√($1)")
        s = s.replace(Regex("\\\\(?:text|mathrm|mathbf|mathit|boldsymbol|operatorname)\\s*\\{([^{}]*)\\}"), "$1")

        // 6) 只调间距/尺寸的命令直接删掉
        s = s.replace(Regex("\\\\(?:left|right|big|Big|bigg|Bigg|quad|qquad|,|;|:|!| )"), "")

        // 7) 符号表替换
        for ((from, to) in SYMBOLS) {
            s = s.replace(from, to)
        }

        // 8) 上下标花括号：x_{1} → x_1
        s = s.replace(Regex("([_^])\\s*\\{([^{}]*)\\}"), "$1$2")

        // 9) 剩余的未知反斜杠命令（连同命令名一起去掉）
        s = s.replace(Regex("\\\\[A-Za-z]+"), "")

        // 10) 公式定界符与对齐符
        s = s.replace("$", "")
        s = s.replace("&", " ")

        // 11) 空白整理：行内多余空格、行首行尾空白、连续空行
        s = s.replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex(" *\\n *"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")

        return s.trim()
    }
}
