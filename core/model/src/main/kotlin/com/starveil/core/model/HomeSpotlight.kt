package com.starveil.core.model

/**
 * 首页单个「卡片位」。
 *
 * 同时承载三个位置，三者在后台是同一张结构（`{title,subtitle,vods}`）：
 * - 编辑精选 `GET v1.main/featured`
 * - 热度榜 `GET v1.main/hot`
 * - 猜你喜欢 `GET v1.main/guess`
 *
 * [items] 为空表示后台未配置（或接口失败降级），仓库与状态都如实保持空列表，
 * 由 UI 按位置自行决定回退文案与切片 —— 因此默认值必须是空列表，
 * 也不能在这里塞默认影片或默认标题。
 */
data class HomeSpotlight(
    val title: String = "",
    val subtitle: String = "",
    val items: List<Vod> = emptyList(),
)
