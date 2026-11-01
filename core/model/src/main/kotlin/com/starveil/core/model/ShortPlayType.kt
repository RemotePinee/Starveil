package com.starveil.core.model

/**
 * 短剧分类：一个 macCMS 分类（`mac_type.type_id`）+ 它在后台的名字。
 *
 * 短剧不是一个独立接口，它就是 `v1.vod` 里某几个 `type_id` 的影片。后台可以配多个，
 * 剧场页顶部按这里的顺序出 chips。
 *
 * [children] 只有分类树的一级节点才有值：macCMS 的 `mac_type.type_pid` 把子分类挂在
 * 父分类下面，分类页据此做两级联动。叶子分类（含从 `shortplay_types` 兜底出来的单层
 * 列表）的 [children] 是空列表。
 *
 * @property id 对应 `mac_type.type_id`；客户端虚拟的「全部」用 0，不占后端 id。
 * @property name 后台填的分类名；老后端没下发名字时为空串，由页面给默认文案。
 * @property children 直接子分类，按 `type_id` 升序；空表示这是叶子分类。
 */
data class ShortPlayType(
    val id: Int,
    val name: String,
    val children: List<ShortPlayType> = emptyList(),
)
