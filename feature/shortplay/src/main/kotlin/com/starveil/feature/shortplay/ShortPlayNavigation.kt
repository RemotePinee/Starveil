package com.starveil.feature.shortplay

/** 剧场页由 app 注册；播放器与跨页面跳转均由 app 注入，禁止 feature 互依赖。 */
const val SHORT_PLAY_ROUTE = "shortplay"

/**
 * 短剧分类页：顶部分类条 + 卡片网格的独立一页。
 *
 * 它取代了原来的剧场页侧边抽屉——抽屉把分类、搜索、快捷入口都塞进一个浮层，
 * 分类一多就只能在小窗里两列滚；拆成整页后与片库筛选页是同一个形态。
 */
const val SHORT_PLAY_CATEGORY_ROUTE = "shortplay/category"

/** 分类页路由参数名。 */
const val SHORT_PLAY_CATEGORY_ARG_TYPE_ID = "typeId"

/** NavHost 注册用的带参路由模式。 */
const val SHORT_PLAY_CATEGORY_ROUTE_PATTERN =
    "$SHORT_PLAY_CATEGORY_ROUTE?$SHORT_PLAY_CATEGORY_ARG_TYPE_ID={$SHORT_PLAY_CATEGORY_ARG_TYPE_ID}"

/** 分类页带参路由：进页落在剧场页当前分类上。 */
fun shortPlayCategoryRoute(typeId: Int): String = "$SHORT_PLAY_CATEGORY_ROUTE?$SHORT_PLAY_CATEGORY_ARG_TYPE_ID=$typeId"

data class ShortPlayNavigation(
    val onSearch: () -> Unit = {},
    val onHistory: () -> Unit = {},
    val onFavorites: () -> Unit = {},
    val onFeedback: () -> Unit = {},
    /** 顶部菜单：进入短剧分类页（原先是打开侧边抽屉）。 */
    val onCategories: () -> Unit = {},
)

/**
 * 剧场页展示态：app 在 feed 与 series 之间原地切换时注入，只描述"看起来怎样"。
 * 默认值等同纯 feed，未接入的入口调用行为不变。
 */
data class ShortPlayPresentation(
    /** 全集面板已展开：菜单与分类条一律不可点，避免叠出第二套导航。 */
    val seriesExpanded: Boolean = false,
    /** 手势锁（开合动画期间）：pager 不可滚，但已落页照常播放。 */
    val interactionLocked: Boolean = false,
    /** 0..1 展开进度：顶部栏据此上移淡出，不动播放器布局。 */
    val expansion: Float = 0f,
)
