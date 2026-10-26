package com.starveil.core.data

import com.starveil.core.model.Session

/**
 * 会话持久化。
 *
 * 把 7 个 cookie 拆成 14 个 SP 键（值 + domain）存 `user_cookie` 表
 * 新实现只存值：域名由 [com.starveil.core.network.ApiClient]
 * 统一持有，不该跟着每个 cookie 存一份。
 */
interface SessionStore {

    /** 没有会话时返回 null，不是抛异常——冷启动阶段调用方不该处理失败分支。 */
    suspend fun load(): Session?

    suspend fun save(session: Session)

    suspend fun clear()
}
