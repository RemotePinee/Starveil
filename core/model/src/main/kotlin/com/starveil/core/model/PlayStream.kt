package com.starveil.core.model

/**
 * 一条解析出来的流。
 *
 * 大多数平台只给一个地址（m3u8 / mp4），B 站给的是**音视频分离**的两条直链
 * （DASH 的 URL 形态，但没有 `.mpd` 清单），所以这里多一个 [audioUrl]：
 * - [audioUrl] 为 null → 单流，播放器只挂一个 MediaSource；
 * - [audioUrl] 非空 → 双流，播放器用 MergingMediaSource 合成（见 Media3Player）。
 *
 * [type] 是服务端给的类型（`m3u8` / `mp4` / `dash`），用来判定"这算不算播放地址"。
 * 不能只看后缀：B 站的 relay 地址是 `/stream?...`，没有 `.mp4` 也没有 `.m3u8`。
 *
 * 命名上不复用 [PlaySource]（那是"播放源/线路"这一层，一个源里有多集），
 * 两者不是一个概念。
 */
data class PlayStream(
    val url: String,
    val audioUrl: String? = null,
    val type: String = "",
    /**
     * 官方弹幕地址（解析站 `/json` 里的 `danmu` 字段，指向 qilin-danmu 的 `/danmu?url=...`）。
     * 与用户弹幕是两套数据：这份是平台官方弹幕（B站/腾讯/优酷…的 xml 转成 json）。
     */
    val danmuUrl: String? = null,
    /**
     * 解析站下发的播放请求头（目前只有 Referer），与 `PlaybackPhase.Playing.headers` 合并，
     * 后者优先生效。
     */
    val referer: String? = null,
) {

    /** 是否音视频分离的双流。 */
    val isDualStream: Boolean get() = !audioUrl.isNullOrBlank()
}
