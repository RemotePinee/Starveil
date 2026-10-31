package com.starveil.core.model

/**
 * 影片领域模型。
 *
 * 只保留首期需要的字段。后端返回 60+ 字段且直接用列名，
 * 导致 UI 层到处出现 vod_xxx 命名；这里改成 camelCase，映射在 :core:network 完成。
 */
data class Vod(
    val id: Int,
    val name: String,
    val poster: String?,
    val remarks: String?,
    val year: String?,
    val area: String?,
    val score: String?,
    val blurb: String?,
    val playSources: List<PlaySource>,
    /**
     * 后端 `type_id`。详情页拿它拉同类型推荐。
     * 默认 0 表示后端没给类型，此时不请求推荐。
     */
    val typeId: Int = 0,
    /**
     * 后端 `type_name`，分类中文名（剧场页 chips 用它去重）。
     * 后端不下发该字段；空串与 null 同义，都当作"没有分类名"。
     */
    val typeName: String? = null,
) {

    /**
     * 是否短剧：分类名里带"短剧"就走竖屏播放器。
     *
     * 判定只此一处，跳转方（观看记录、搜索、榜单）都问它，不各自写字符串比较。
     */
    val isShortPlay: Boolean
        get() = typeName.orEmpty().contains(SHORT_PLAY_KEYWORD)

    private companion object {
        const val SHORT_PLAY_KEYWORD = "短剧"
    }
}

/**
 * 播放源。后端的 vod_play_list 是「源 -> 集」两层结构。
 *
 * [index] 是数组下标（0 起），用来选集、换源、存观看记录，不能当后端主键用；
 * [sid] 是后端真正的主键（1 起），单集购买必须原样上报——后端 `array_values`
 * 重排过，两者不保证一致，按下标 + 1 猜 sid 会把钱付到别的源上。
 * 老数据/后端没下发时 [sid] 为 0，由仓库退回「下标 + 1」。
 */
data class PlaySource(
    val index: Int,
    val name: String,
    val episodes: List<PlayUrl>,
    val playerInfo: PlayerInfo,
    val sid: Int = 0,
)

/**
 * 单集。episodeNumber 对应后端 nid（1 起），接口调用需要它；
 * 而数组下标才是 urlIndex（0 起）。两者不可混用。
 */
data class PlayUrl(
    val episodeNumber: Int,
    val title: String,
    val url: String,
)
