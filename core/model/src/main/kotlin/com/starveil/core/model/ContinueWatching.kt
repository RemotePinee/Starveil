package com.starveil.core.model

/**
 * 「继续观看」条目，来自 `GET v1.user/videoProgressList`。
 *
 * [episodeIndex] 与 [sourceIndex] 都是 **0 起下标**，与播放器路由
 * （`playerRoute(vodId, sourceIndex, episodeIndex)`）同一口径；
 * 展示用的集号见 [episodeNumber]。
 */
data class ContinueWatching(
    val vodId: Int,
    val vodName: String,
    val vodPic: String?,
    val sourceIndex: Int,
    val episodeIndex: Int,
    /** 已看秒数。后端存的是播放位置，不是累计观看时长。 */
    val progressSec: Int,
    val updatedAt: Long,
    /** 影片类型名（`mac_type.type_name`）。空表示后端没给，按普通影片处理。 */
    val typeName: String? = null,
) {

    /**
     * 是否短剧：短剧走竖屏播放器，普通影片回详情页。
     *
     * 判定放模型层，:app 只按这个布尔选路由——以后接别的短剧来源改这一处，
     * 不用在跳转处散落字符串比较。
     */
    val isShortPlay: Boolean
        get() = typeName.orEmpty().contains(SHORT_PLAY_KEYWORD)

    companion object {
        /** 类型名里出现这个字样的按短剧处理。 */
        const val SHORT_PLAY_KEYWORD = "短剧"
    }

    /** 集号（1 起）：用户看到的"第 N 集"。 */
    val episodeNumber: Int get() = episodeIndex + 1

    /**
     * 只用于跳详情。
     *
     * 详情页按 `vod_id` 重新拉完整数据，这里除 id / 名称 / 封面外不携带任何播放信息，
     * 避免把"半张 Vod"当成真实数据用。
     */
    fun toVod(): Vod = Vod(
        id = vodId,
        name = vodName,
        poster = vodPic,
        remarks = null,
        year = null,
        area = null,
        score = null,
        blurb = null,
        playSources = emptyList(),
    )
}

/**
 * 继续观看的展示规则。
 *
 * 后端只落「已看秒数」，不下发单集总时长，所以百分比天生算不准。
 * 按名义单集时长估算，**仅用于首页卡片的进度提示**，不参与任何播放或试看判定。
 */
object ContinueWatchingRules {

    /** 名义单集时长（25 分钟）。 */
    const val NOMINAL_EPISODE_SECONDS = 25 * 60

    /** 已看秒数 → 0..100 百分比；非正数记 0，超过名义时长封顶 100。 */
    fun percent(progressSec: Int): Int {
        if (progressSec <= 0) return 0
        return (progressSec * 100 / NOMINAL_EPISODE_SECONDS).coerceIn(1, 100)
    }
}
