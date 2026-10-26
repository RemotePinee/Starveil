package com.starveil.feature.detail

import androidx.compose.runtime.Composable

/**
 * 头图插槽。
 *
 * 详情页默认自己画海报（[DetailHero]）；调用方（:app）可以塞一个内嵌播放器进来，
 * 让"看详情"和"起播"发生在同一个页面，不再跳独立播放页。插槽由 :app 提供，
 * 本模块不依赖 `:feature:player`（AGENTS.md 第 1 节）。
 *
 * 插槽被渲染在海报之上、顶栏之下，并且**不随列表滚动**：
 * 播放器一旦起播就必须保持挂载，否则滑出视口被回收后会重建播放器。
 */
typealias DetailHeroSlot = @Composable HeroSlotScope.() -> Unit

/**
 * 插槽拿得到的上下文。
 *
 * @param content 当前内容态（影片 + 当前源 / 当前集），插槽按它取海报与起播参数
 * @param onFullscreenChange 插槽自报「已铺满整屏」；详情页据此收起自己的顶栏，
 *   把整屏让给插槽。退出全屏或插槽销毁时记得回调 false
 */
class HeroSlotScope(
    val content: DetailUiState.Content,
    val onFullscreenChange: (Boolean) -> Unit,
)
