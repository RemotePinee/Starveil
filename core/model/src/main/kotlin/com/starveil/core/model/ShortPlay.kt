package com.starveil.core.model

/**
 * 短剧领域模型（剧场页卡片所需的最小字段集）。
 *
 * 数据来自 macCMS 的 `v1.vod`，短剧与普通影片共用一张表和一套契约，
 * 只在客户端按 `type_id == 30` 区分（联调分类，见计划 §2.1）。
 *
 * @property id 等于后端 `vod_id`，即 `Vod.id`；详情与播放都靠它回查。
 * @property title 对应 `Vod.name`。
 * @property cover 对应 `Vod.poster`；后端可能不下发。
 * @property episodeCount 集数，取自第一个播放源（[Vod.playSources]`[0]`）的集数。
 *   短剧约定"单源多集"，多源是异常数据，此时不求和也不取最大，避免把重复源算两遍。
 * @property freeEpisodes 前 N 集免费。服务端目前没有这个字段，恒为 0，
 *   判定是否可看仍走 [TrySeeRules]，不要把这个 0 当成"全部要付费"。
 * @property category 分类名，取自 `Vod.typeName`（后端 `mac_type.type_name`）。
 *   后端不下发 `type_name`、或下发了空白时仍为 null；不拿 `remarks`
 *   （"更新至 80 集"之类的文案）顶替——那是猜，不是映射。
 * @property blurb 简介，对应 `Vod.blurb`。
 */
data class ShortPlay(
    val id: Int,
    val title: String,
    val cover: String?,
    val episodeCount: Int,
    val freeEpisodes: Int,
    val category: String?,
    val blurb: String?,
)
