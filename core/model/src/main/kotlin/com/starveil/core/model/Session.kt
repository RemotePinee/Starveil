package com.starveil.core.model

/**
 * 登录会话。
 *
 * 后端不用 token，登录态是 7 个 cookie：
 * `user_id` / `user_name` / `user_nick_name` / `group_id` / `group_name` /
 * `user_check` / `user_portrait`。
 *
 * 三个必须原样保留的约定：
 * 1. 登录判定只看 `user_id` 非空。
 * 2. 按 cookie 名 `startsWith` 匹配，后端会给同名 cookie 加后缀，
 *    所以这里也按前缀归并，取最长匹配。
 * 3. `user_check` 缺失时后端返回 403，不是客户端能补救的——按未登录处理。
 */
data class Session(
    val userId: String,
    val userName: String,
    val nickName: String,
    val groupId: String,
    val groupName: String,
    val userCheck: String,
    val portrait: String,
) {
    /**
     * 登录判定只认 `user_id` 非空。
     * `user_check` 是会话校验串，缺失时后端会 403，但不影响"本地是否已登录"这个判断。
     */
    val isLoggedIn: Boolean
        get() = userId.isNotBlank()

    /** 会话校验串是否齐全。缺它时请求会被后端当游客，需要重新登录。 */
    val hasSessionCheck: Boolean
        get() = userCheck.isNotBlank()

    companion object {

        const val COOKIE_USER_ID = "user_id"
        const val COOKIE_USER_NAME = "user_name"
        const val COOKIE_NICK_NAME = "user_nick_name"
        const val COOKIE_GROUP_ID = "group_id"
        const val COOKIE_GROUP_NAME = "group_name"
        const val COOKIE_USER_CHECK = "user_check"
        const val COOKIE_PORTRAIT = "user_portrait"

        /** 需要登录态的 cookie 名，顺序保持一致。 */
        val COOKIE_NAMES = listOf(
            COOKIE_USER_ID,
            COOKIE_USER_NAME,
            COOKIE_NICK_NAME,
            COOKIE_GROUP_ID,
            COOKIE_GROUP_NAME,
            COOKIE_USER_CHECK,
            COOKIE_PORTRAIT,
        )

        /**
         * 从 cookie 表还原会话。
         *
         * 缺失字段填空串而不是 null：历史代码到处 `getString(..., "")`，
         * 空串语义已经渗透到判断里（`isNotEmpty()`），保持同一种口径最省事。
         */
        fun fromCookies(cookies: Map<String, String>): Session = Session(
            userId = cookies.lookup(COOKIE_USER_ID),
            userName = cookies.lookup(COOKIE_USER_NAME),
            // 保持 cookie 原值（可能仍是 urlencode 串）：Session 会被 toCookieMap()
            // 写回 cookie，中文值会让 OkHttp 直接抛 "Unexpected char in Cookie value"。
            // 解码只放在展示层（见 UserCenterUiState 的 displayName / subtitle）。
            nickName = cookies.lookup(COOKIE_NICK_NAME),
            groupId = cookies.lookup(COOKIE_GROUP_ID),
            groupName = cookies.lookup(COOKIE_GROUP_NAME),
            userCheck = cookies.lookup(COOKIE_USER_CHECK),
            portrait = cookies.lookup(COOKIE_PORTRAIT),
        )

        /**
         * 按前缀取值：后端会下发 `user_id_xxx` 这类带后缀的同名 cookie，
         * 精确匹配会漏掉。多个命中时取名字最长的一个（最具体）。
         */
        /** 只有出现 `%` 才解码，避免把 `+` 误转成空格。 */
        internal fun String.decodeIfEncoded(): String =
            if (!contains('%')) {
                this
            } else {
                runCatching { java.net.URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)
            }

        private fun Map<String, String>.lookup(name: String): String =
            entries.filter { it.key.startsWith(name) }
                .maxByOrNull { it.key.length }
                ?.value
                .orEmpty()
                .trim()
    }

    /**
     * 回写 cookie 时用，保持与后端一致的名字。
     *
     * 值必须是 ASCII：昵称/组名在服务端是 urlencode 存的，但只要本地存过一次解码后的中文
     * （老版本干过），这里直接塞进 Cookie 就会让 OkHttp 抛
     * `IllegalArgumentException: Unexpected char ... in Cookie value` 并崩在 OkHttp 线程。
     * 非 ASCII 一律重新 urlencode。
     */
    fun toCookieMap(): Map<String, String> = mapOf(
        COOKIE_USER_ID to userId,
        COOKIE_USER_NAME to userName,
        COOKIE_NICK_NAME to nickName,
        COOKIE_GROUP_ID to groupId,
        COOKIE_GROUP_NAME to groupName,
        COOKIE_USER_CHECK to userCheck,
        COOKIE_PORTRAIT to portrait,
    ).mapValues { (_, value) -> value.toCookieSafe() }
}

/** 可打印 ASCII 原样保留，其余按 UTF-8 urlencode。 */
internal fun String.toCookieSafe(): String =
    if (all { it.code in COOKIE_ASCII_RANGE }) this else java.net.URLEncoder.encode(this, "UTF-8")

private val COOKIE_ASCII_RANGE = 0x20..0x7e
