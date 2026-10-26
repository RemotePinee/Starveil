package com.starveil.core.network.dto

import kotlinx.serialization.Serializable

/**
 * 后端统一响应壳：{"code":200,"msg":"success","data":...}
 *
 * 契约要点（来自后端）：
 * - 成功判定是 code == 200，不是 HTTP 状态
 * - 业务失败时 HTTP 400，body 仍是本壳
 * - 方法不存在时返回 HTML 脚本，不是 JSON
 */
@Serializable
data class ApiEnvelopeDto<T>(
    val code: Int = 0,
    val msg: String? = null,
    val data: T? = null,
)

/**
 * 只带 code / msg 的壳，用于错误体二次解析。
 *
 * 后端业务失败时 HTTP 状态是 400 而不是 200，body 仍是完整壳；
 * 仓库层需要先读出业务码，才能把 400 归为 [com.starveil.core.common.AppError.Business]。
 */
@Serializable
data class ErrorEnvelopeDto(
    val code: Int = 0,
    val msg: String? = null,
)

/**
 * 分页壳，字段名与后端完全一致。
 *
 * 部分接口不返回 total（消息、弹幕、直播、游戏），这些场景 total 为 0，
 * UI 不能依赖 total 判断是否还有下一页。
 */
@Serializable
data class PageDto<T>(
    val total: Int = 0,
    val page: Int = 1,
    val limit: Int = 20,
    val list: List<T> = emptyList(),
)
