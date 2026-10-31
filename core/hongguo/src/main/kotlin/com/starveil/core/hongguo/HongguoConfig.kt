package com.starveil.core.hongguo

/**
 * 解密模块的开关与识别规则。
 *
 * 两个字段都由后台下发，默认关；规则是逗号分隔的子串，命中才处理，
 * 所以普通 mp4/m3u8 不会受影响。
 */
data class HongguoConfig(
    val enabled: Boolean = false,
    val patterns: List<String> = emptyList(),
) {
    fun matches(url: String?): Boolean {
        // 开关、规则、地址三者缺一不可；拆成有名字的布尔量，避免一行里堆三个 or
        val ruleReady = enabled && patterns.isNotEmpty()
        if (!ruleReady || url.isNullOrBlank()) return false
        return patterns.any { url.contains(it, ignoreCase = true) }
    }

    companion object {
        fun parse(enabled: Int?, match: String?): HongguoConfig = HongguoConfig(
            enabled = enabled != null && enabled == 1,
            patterns = (match ?: "")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() },
        )
    }
}
