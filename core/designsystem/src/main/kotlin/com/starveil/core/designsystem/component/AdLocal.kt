package com.starveil.core.designsystem.component

import androidx.compose.runtime.staticCompositionLocalOf
import com.starveil.core.common.ad.AdPlacements

/**
 * 当前生效的广告位配置。
 *
 * 走 CompositionLocal 而不是逐层传参：广告是横切关注点，商品页/列表页都不该为了
 * 一条横幅多出两个参数（Composable 参数上限 5，见 AGENTS.md 第 2 节）。
 * [com.starveil.core.common.ad.AdPlacements.NONE] 是默认值，未提供时页面自然不渲染广告。
 */
val LocalAdPlacements = staticCompositionLocalOf { AdPlacements.NONE }

/**
 * 打开广告落地页。由 `:app` 提供实现（Intent ACTION_VIEW），
 * feature 模块只拿到一个回调，不依赖 app 也不自己拼 Intent。
 */
val LocalAdClick = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * 播放激励广告。参数是"看完之后做什么"：`:app` 弹全屏浮层并把回调带过去，
 * feature 模块只负责上抛点击，看不到素材怎么画，也不拼 Intent。
 */
val LocalRewardAd = staticCompositionLocalOf<(onFinished: () -> Unit) -> Unit> { { } }
