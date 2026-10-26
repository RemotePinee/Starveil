package com.starveil.core.network

/**
 * 需要登录态的接口白名单。
 *
 * 只在命中这张表的请求上附加会话 cookie，其余请求裸发。不做"全站带 cookie"，
 * 因为公开接口（列表/详情）带上用户 cookie 会让后端与 CDN 的缓存串味。
 *
 * 新增需要登录的接口时必须同步加进来，否则该接口会被后端当游客处理——
 * 返回 403 或空数据，不会给出"未登录"这种明确错误。
 *
 * 放在 `:core:network` 而不是 `:core:model`：路径常量 [ApiPaths] 属于本模块，
 * 依赖方向不允许 model 反向引用 network。
 */
object SessionCookiePolicy {

    private val LOGIN_REQUIRED_PATHS = setOf(
        ApiPaths.VOD_PROGRESS,
        ApiPaths.USER_INFO,
        ApiPaths.VIEW_SECONDS,
        ApiPaths.TASK_LIST,
        ApiPaths.SIGN,
        ApiPaths.ORDER,
        ApiPaths.BUY_CARD,
        ApiPaths.UPGRADE_GROUP,
        ApiPaths.USER_PROFILE,
        ApiPaths.USER_AVATAR,
        ApiPaths.GOLD_WITHDRAW,
        ApiPaths.COMMENT,
        ApiPaths.FEEDBACK,
        ApiPaths.ULOG,
        ApiPaths.FAVS,
        ApiPaths.SHARE_SCORE,
        ApiPaths.BUY_POPEDOM,
        ApiPaths.CHECK_VOD_TRY_SEE,
        ApiPaths.PAY,
        ApiPaths.GOLD_TIP,
        ApiPaths.VOD_DETAIL,
        ApiPaths.SHARE_INFO,
    )

    /** `path` 允许带查询串或 `/icciu_api.php/` 前缀，这里统一归一化。 */
    fun requiresSession(path: String?): Boolean {
        val normalized = normalize(path)
        return normalized.isNotEmpty() && normalized in LOGIN_REQUIRED_PATHS
    }

    /** 暴露给测试与日志：避免有人凭印象判断某接口要不要登录。 */
    fun loginRequiredPaths(): Set<String> = LOGIN_REQUIRED_PATHS

    private fun normalize(path: String?): String {
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        return raw.substringBefore('?')
            .removePrefix("/")
            .removePrefix(ApiPaths.PREFIX)
    }
}
