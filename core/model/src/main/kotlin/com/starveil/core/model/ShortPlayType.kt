package com.starveil.core.model

/**
 * 短剧分类：一个 macCMS 分类（`mac_type.type_id`）+ 它在后台的名字。
 *
 * 短剧不是一个独立接口，它就是 `v1.vod` 里某几个 `type_id` 的影片。后台可以配多个，
 * 剧场页顶部按这里的顺序出 chips。
 */
data class ShortPlayType(
    val id: Int,
    val name: String,
)
