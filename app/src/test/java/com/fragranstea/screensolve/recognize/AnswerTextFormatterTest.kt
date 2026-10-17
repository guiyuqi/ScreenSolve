package com.fragranstea.screensolve.recognize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住 LaTeX / markdown → 纯文本的转换行为。
 *
 * 为什么值得写测试：提示词虽然要求模型用纯文本，但模型一定会飘。
 * 这套清洗是"卡片能不能读懂"的最后一道闸，规则多了以后很容易互相踩（比如 `\le` 会吃掉 `\leq` 的开头），
 * 靠人肉回归不可靠。
 */
class AnswerTextFormatterTest {

    @Test
    fun `去掉美元定界符但保留公式内容`() {
        assertEquals(
            "设 x^2-5x+6=0，则 x_1=2",
            AnswerTextFormatter.sanitize("设 \$x^2-5x+6=0\$，则 \$x_{1}=2\$"),
        )
    }

    @Test
    fun `分式转成除号写法`() {
        assertEquals("(1)/(2)", AnswerTextFormatter.sanitize("\\frac{1}{2}"))
        assertEquals("(a+b)/(c-d)", AnswerTextFormatter.sanitize("\\dfrac{a+b}{c-d}"))
    }

    @Test
    fun `根号与常用符号`() {
        assertEquals("√(2)", AnswerTextFormatter.sanitize("\\sqrt{2}"))
        assertEquals("x ≤ 1 且 x ≠ 0", AnswerTextFormatter.sanitize("x \\le 1 且 x \\neq 0"))
        assertEquals("2 × 3 = 6", AnswerTextFormatter.sanitize("2 \\times 3 = 6"))
        assertEquals("x ≥ 0", AnswerTextFormatter.sanitize("x \\geq 0"))
    }

    @Test
    fun `aligned 环境拆成多行且去掉对齐符`() {
        val out = AnswerTextFormatter.sanitize("\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}")
        assertEquals("a = b\nc = d", out)
    }

    @Test
    fun `markdown 强调与列表符号被清掉`() {
        assertEquals("答案很明确", AnswerTextFormatter.sanitize("**答案**很明确"))
        assertEquals("第一步", AnswerTextFormatter.sanitize("- 第一步"))
        assertEquals("标题", AnswerTextFormatter.sanitize("## 标题"))
    }

    @Test
    fun `text 包裹只保留内容`() {
        assertEquals("数学成绩及格", AnswerTextFormatter.sanitize("\\text{数学成绩及格}"))
    }

    @Test
    fun `纯文本原样保留`() {
        val plain = "事件 A 表示数学及格且英语不及格，由德摩根律得非 A = 数学不及格或英语及格。"
        assertEquals(plain, AnswerTextFormatter.sanitize(plain))
    }

    @Test
    fun `不会残留反斜杠与美元符`() {
        val out = AnswerTextFormatter.sanitize("\\unknowncmd{x} \$y\$ \\left( z \\right)")
        assertFalse(out.contains("\\"))
        assertFalse(out.contains("$"))
        assertTrue(out.contains("y"))
    }

    @Test
    fun `空输入不会抛异常`() {
        assertEquals("", AnswerTextFormatter.sanitize(""))
        assertEquals("", AnswerTextFormatter.sanitize("   \n\n  "))
    }
}
