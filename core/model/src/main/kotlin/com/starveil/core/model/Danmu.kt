package com.starveil.core.model

/**
 * 弹幕，来自 `GET v1.danmu/index`（
 * 字段见旧 `bean/DanMuBean.ListBean`）。
 *
 * [atTimeMs] 是「播放位置（毫秒）」而不是发送时间：旧渲染逻辑用
 * `at_time - currentPosition` 算剩余偏移，
 * 发送时也传当时的播放位置。
 * 真正的发送时间是 [createdAt]（秒级时间戳）。
 *
 * [color] 可空。出现过带井号和不带井号两种写法
 * （ 不带、 带），
 * 原样保留，归一化交给渲染层。
 */
data class Danmu(
    val id: Int,
    val content: String,
    val userId: Int,
    val atTimeMs: Long,
    val createdAt: Long,
    val status: Int,
    val likeCount: Int,
    val color: String?,
)

/**
 * 弹幕归属坐标，与苹果CMS 弹幕表一致：影片 / 播放源下标 / 集下标。
 *
 * 单独成类型是为了让接口参数不超过 5 个（detekt LongParameterList），
 * 也避免三个 Int 在手写调用处张冠李戴。
 */
data class DanmuTarget(
    val vodId: Int,
    val sid: Int,
    val nid: Int,
)
