package com.starveil.feature.user

import androidx.compose.runtime.Immutable

/** 反馈页状态：输入内容 + 提交中标记。 */
@Immutable
data class FeedbackUiState(
    val content: String = "",
    val isSubmitting: Boolean = false,
) {

    /** 空输入不发请求；服务端也会拦，但没必要白跑一趟。 */
    val canSubmit: Boolean get() = content.isNotBlank() && !isSubmitting
}
