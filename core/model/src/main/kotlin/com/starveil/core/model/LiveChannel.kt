package com.starveil.core.model

/**
 * 直播频道，来自 `v1.zhibo`。
 *
 * [id] 用 Long：后端 bean 是 `long`，频道号可能超过 Int。
 * [url] 是后端直接给的可播放地址，不经过线路解析——旧直播页点开就播。
 */
data class LiveChannel(
    val id: Long,
    val name: String,
    val cover: String?,
    val url: String?,
    /** 分组名。后台按 M3U 的 group-title 导入，空串表示未分组。 */
    val group: String = "",
)
