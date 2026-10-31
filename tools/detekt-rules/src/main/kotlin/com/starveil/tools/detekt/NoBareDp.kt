package com.starveil.tools.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression

/**
 * 数值字面量直接接 `.dp` 视为裸尺寸（AGENTS.md 第 3 节）。
 *
 * 只盯 `24.dp` / `1.5.dp` 这种"字面量 + .dp"：`Spacing.xl`、`IconSize.md` 是名字引用，
 * 不命中；`Spacing.xl + 4.dp` 里的 `4.dp` 是另一个表达式，照样命中。
 */
class NoBareDp(config: Config = Config.empty) : Rule(config) {

    override val issue: Issue = Issue(
        javaClass.simpleName,
        Severity.CodeSmell,
        "尺寸必须走令牌：Spacing.* / IconSize.*（core:designsystem 的 token 包）。",
        Debt.FIVE_MINS,
    )

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        if (!expression.isCheckedSource() || !expression.isBareDp()) return
        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "${expression.text} 是裸 dp：换成 Spacing / IconSize 令牌；" +
                    "确实没有对应令牌，就在 Spacing 里加一个语义化常量。",
            ),
        )
    }

    /** 形如 `24.dp`：接收者是数值常量，选择者是 `dp`。 */
    private fun KtDotQualifiedExpression.isBareDp(): Boolean {
        val receiver = receiverExpression as? KtConstantExpression ?: return false
        return selectorExpression?.text == DP_SUFFIX && NUMERIC_LITERAL.matches(receiver.text)
    }

    private companion object {
        const val DP_SUFFIX = "dp"

        /** 整数或小数常量；`1f`/`1L` 这类后缀写法不是 dp 的正常写法，不认。 */
        val NUMERIC_LITERAL = Regex("""\d+(?:\.\d+)?""")
    }
}
