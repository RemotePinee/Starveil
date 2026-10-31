package com.starveil.tools.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * 颜色字面量只能出现在 `core:designsystem` 的 token 包（AGENTS.md 第 3 节）。
 *
 * 覆盖四种写法：
 * - `Color(0xFF123456)` / `Color(0xFF123456.toInt())`
 * - `Color.Black` 这一组命名颜色（`Color.Transparent` 放行：语义是"无背景"）
 * - `android.graphics.Color.parseColor("#RRGGBB")`
 * - `"#RRGGBB"` 字符串字面量
 *
 * 判 PSI 不判文本：注释里的 `#RRGGBB`、`Palette` 里的令牌定义（已按路径豁免）都不会误伤。
 */
class NoHardcodedColor(config: Config = Config.empty) : Rule(config) {

    override val issue: Issue = Issue(
        javaClass.simpleName,
        Severity.CodeSmell,
        "颜色只能来自 MaterialTheme.colorScheme.* 或 starveilExtraColors.*；" +
            "确需新色值时加进 core:designsystem 的 token 包。",
        Debt.FIVE_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (!expression.isCheckedSource()) return
        val callee = expression.calleeExpression?.text ?: return
        when {
            callee == PARSE_COLOR -> report(
                expression,
                "android.graphics.Color.parseColor 已禁用：改用 token 包的 parseArgbHexOrNull + 令牌色。",
            )
            callee == COLOR_FACTORY && expression.hasHexColorArgument() -> report(
                expression,
                "禁止在页面里写 ${expression.text.take(40)}：换成 colorScheme.* 或 starveilExtraColors.*。",
            )
        }
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        if (!expression.isCheckedSource() || !expression.isNamedColor()) return
        report(
            expression,
            "${expression.text} 是写死的命名颜色：换成 colorScheme.*；" +
                "压在海报 / 视频画面上时用 starveilExtraColors.onImage。",
        )
    }

    /** `Color.Black` 这类属性访问；`Color.Transparent` 不在 [NAMED_COLORS] 里。 */
    private fun KtDotQualifiedExpression.isNamedColor(): Boolean {
        val receiver = (receiverExpression as? KtNameReferenceExpression)?.getReferencedName()
        val selector = (selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
        return receiver == COLOR_FACTORY && selector in NAMED_COLORS
    }

    override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
        super.visitStringTemplateExpression(expression)
        if (!expression.isCheckedSource() || !expression.isHexColorLiteral()) return
        report(
            expression,
            "禁止写 ${expression.text} 这样的十六进制色串：换成颜色令牌，" +
                "后端下发色的场景用 parseArgbHexOrNull 解析。",
        )
    }

    /** 只有纯字面量才算：含 `$` 插值的串是拼出来的，不是色值。 */
    private fun KtStringTemplateExpression.isHexColorLiteral(): Boolean =
        !hasInterpolation() && HEX_STRING_LITERAL.matches(text)

    /** 参数里是否有 `0xRRGGBB` / `0xRRGGBB.toInt()` 形态的字面量。 */
    private fun KtCallExpression.hasHexColorArgument(): Boolean =
        valueArguments.any { argument -> argument.getArgumentExpression()?.isHexConstant() == true }

    private fun KtExpression.isHexConstant(): Boolean {
        val unwrapped = KtPsiUtil.deparenthesize(this) ?: return false
        return when (unwrapped) {
            is KtConstantExpression -> HEX_NUMBER.matches(unwrapped.text)
            // 0xFF123456 超过 Int.MAX_VALUE，写法上套一层 toInt()
            is KtDotQualifiedExpression -> {
                val receiver = unwrapped.receiverExpression
                val selector = unwrapped.selectorExpression?.text
                receiver is KtConstantExpression &&
                    selector in TO_INT_CALLS &&
                    HEX_NUMBER.matches(receiver.text)
            }
            else -> false
        }
    }

    private fun report(expression: KtExpression, message: String) {
        report(CodeSmell(issue, Entity.from(expression), message))
    }

    private companion object {
        const val COLOR_FACTORY = "Color"
        const val PARSE_COLOR = "parseColor"

        /** 0x 后 6 位（无 alpha）或 8 位（带 alpha）。 */
        val HEX_NUMBER = Regex("""0[xX][0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?[lLuU]*""")

        val HEX_STRING_LITERAL = Regex(""""#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?"""")

        /** 命名颜色里 **不包括** `Transparent`：它是"无背景"，不是颜色。 */
        val NAMED_COLORS = setOf(
            "Black",
            "White",
            "Gray",
            "LightGray",
            "DarkGray",
            "Red",
            "Green",
            "Blue",
            "Yellow",
            "Cyan",
            "Magenta",
        )

        val TO_INT_CALLS = setOf("toInt()", "toLong()")
    }
}
