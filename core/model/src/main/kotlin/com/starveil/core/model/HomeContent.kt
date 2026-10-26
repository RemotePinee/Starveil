package com.starveil.core.model

/**
 * 首页内容位。数据全部来自插件后台（`cuican_banner` / `cuican_block` / `cuican_link`），
 * 不再用"列表切片"拼页面。
 */
data class HomeBanner(
    val id: Int,
    val title: String,
    val subtitle: String,
    val pic: String,
    val vodId: Int,
    val url: String,
    val style: String = "",
)

data class HomeBlock(
    val id: Int,
    val title: String,
    val subtitle: String,
    val layout: String,
    val vods: List<Vod>,
)

/**
 * 首页快捷入口（`cuican_link`）。
 *
 * 卡片由「背景图 + 标题 + 副标题」组成：`bg` 是后台填的完整地址或上传后的地址，
 * 空串表示没配图，UI 退回纯色底只显示文字。不再有内置图标。
 */
data class HomeLink(
    val title: String,
    val subtitle: String,
    val bg: String,
    val targetType: String,
    val targetId: Int,
    val url: String,
)

/** 底部菜单项（`bottom_tabs` 配置）。`mode` 只对 second 生效：topic / live / shortplay。 */
data class BottomTab(
    val id: String,
    val label: String,
    val icon: String,
    val visible: Boolean,
    val order: Int,
    val mode: String = "topic",
)

/** 首页模块顺序（`home_modules`）。未知值在映射层丢弃。 */
enum class HomeModule(val wire: String) {
    BANNER("banner"),
    FEATURED("featured"),
    LINKS("links"),
    BLOCKS("blocks"),
    HOT("hot"),
    CONTINUE("continue"),
    TOPIC("topic"),
    LIVE("live"),
    GUESS("guess"),
    ;

    companion object {
        fun fromWire(value: String): HomeModule? = entries.firstOrNull { it.wire == value }
    }
}
