package com.starveil.core.designsystem.token

/**
 * 把 `#RRGGBB` / `#AARRGGBB`（井号可省）解析成 ARGB 整数。
 *
 * 为什么下沉到令牌层：`android.graphics.Color.parseColor` 在 AGENTS.md 第 3 节被明令禁止，
 * 但弹幕颜色是后端下发的字符串，绘制层又必须拿到 `Paint.color` 认的 Int 才能画字。
 * 解析规则只该有一份，且必须和颜色字面量待在同一个包里（唯一放行的目录）。
 *
 * 非法输入返回 null，由调用方决定回落成哪个令牌色——这里不猜默认值。
 */
fun parseArgbHexOrNull(raw: String?): Int? {
    val digits = raw?.trim()?.removePrefix("#")?.lowercase().orEmpty()
    // 先按字符集挡一遍："+1" / "-1" 这类写法 toLongOrNull(16) 也认，不该当色值
    return if (isHexDigits(digits)) toArgbOrNull(digits) else null
}

/** 只认 0-9 / a-f；空串按非法处理（`all` 对空串返回 true，所以先单独挡一道）。 */
private fun isHexDigits(text: String): Boolean = text.isNotEmpty() && text.all { it in HEX_DIGITS }

/** 6 位没有 alpha 段，补成不透明；8 位原样；其余位数一律非法。 */
private fun toArgbOrNull(digits: String): Int? {
    val value = digits.toLongOrNull(16)?.toInt()
    return when (digits.length) {
        RGB_DIGITS -> value?.let { it or OPAQUE_ALPHA }
        ARGB_DIGITS -> value
        else -> null
    }
}

/** 6 位色（无 alpha）的位数。 */
private const val RGB_DIGITS = 6

/** 8 位色（带 alpha）的位数。 */
private const val ARGB_DIGITS = 8

/** 不透明 alpha（`0xFF shl 24`）：写成移位，避免负数字面量。 */
private const val OPAQUE_ALPHA = 0xFF shl 24

/** 合法字符集：小写十六进制数字（[parseArgbHexOrNull] 已先 lowercase）。 */
private val HEX_DIGITS: Set<Char> = (('0'..'9') + ('a'..'f')).toSet()
