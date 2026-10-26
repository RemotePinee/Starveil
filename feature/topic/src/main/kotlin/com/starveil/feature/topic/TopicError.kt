package com.starveil.feature.topic

import com.starveil.core.common.AppError

/**
 * 错误文案收敛在一处，与首页保持同一口径，避免两个页面各说一套。
 * 分类依据见 :core:common 的 AppError。
 */
internal fun AppError.toUserMessage(): String = when (this) {
    is AppError.Network -> "网络连接失败，请检查网络后重试"
    is AppError.Http -> "服务异常（$code）"
    is AppError.Business -> message.ifBlank { "请求被拒绝" }
    is AppError.Serialization -> "数据解析失败"
    is AppError.Unknown -> "未知错误"
}
