package com.starveil.tools.detekt

import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile

/**
 * 只有设计令牌包可以写颜色字面量与裸 dp：规则本身的定义域就在那里。
 *
 * 用路径后缀而不是包名判定：包名要靠 import 解析，而路径在 PSI 上直接就有，
 * 且 detekt 的 basePath 设成工程根后两种形态（绝对 / 相对）都能被 contains 命中。
 */
private const val TOKEN_PACKAGE = "core/designsystem/src/main/kotlin/com/starveil/core/designsystem/token/"

/**
 * 测试源码不参与。
 *
 * 与 `verifyFileLength` 同一个理由：断言里的 `116.dp`、`0.dp` 是被测数值的字面写法，
 * 不是页面上的设计常量；逼测试去引令牌，只会逼设计系统为测试专用值加一堆无人使用的令牌。
 */
private val EXEMPT_MARKERS = listOf(TOKEN_PACKAGE, "/src/test/", "/src/androidTest/")

/** 当前元素所在文件是否属于本次检查范围。 */
internal fun KtElement.isCheckedSource(): Boolean {
    val path = containingFile?.virtualFile?.path?.replace('\\', '/') ?: return true
    return EXEMPT_MARKERS.none(path::contains)
}
