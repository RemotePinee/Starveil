package com.starveil.core.model

/**
 * 用户资料，来自 `GET v1.user/detail`（
 *
 * 只保留首期用得到的字段。后端 bean 把后端列名直接暴露给 UI（40+ 个 `user_xxx`），
 * 统一改 camelCase，映射在 :core:network 完成。
 *
 * 三个不能合并、也不能直接当数值用的字段：
 * 1. [points] 与 [userPoints] 是后端两个独立余额，历史代码两处都读，语义未文档化，不要合并。
 * 2. [gold] 是字符串（形如 `29.00`），后端 bean 也是 String；空串直接 toDouble 会抛异常。
 * 3. [memberEndTime] 是秒级时间戳，0 表示未开通或永久，不能当 Unix epoch 渲染成 1970。
 */
data class UserProfile(
    val userId: Int,
    val userName: String,
    val nickName: String,
    val portrait: String?,
    val groupId: Int,
    val groupName: String,
    val level: String?,
    val points: Int,
    val userPoints: Int,
    val gold: String,
    val status: Int,
    val memberEndTime: Long,
    val viewCount: String?,
    /** 累计观看秒数（插件 `cuican_user_ext.view_seconds`）。 */
    val viewSeconds: Long,
    /** 是否有效会员：服务端 `is_vip` 判定（会员组 + 未过期），客户端不要自己再算一遍。 */
    val isVip: Boolean,
    val isAgents: String?,
)
