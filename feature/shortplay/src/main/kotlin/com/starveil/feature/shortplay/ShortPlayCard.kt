package com.starveil.feature.shortplay

import androidx.compose.runtime.Composable
import com.starveil.core.designsystem.component.BlurredCoverBackdrop
import com.starveil.core.model.ShortPlay

/**
 * 正被滑进屏幕的那一页自己铺的整屏底。
 *
 * 画的是**模糊**封面，不是清晰封面 Crop：视频层只喂锚点页，滑入页在落页前没有画面，
 * 先铺原图再等模糊算完就会闪一下——拖动过程中看到的第一帧必须是最终那张模糊图。
 * 与锚点页视频层里的底是同一份实现、同一个 URL、同一个缓存键（128px + 同一变换），
 * 落页那一刻两边逐像素一致，接缝看不出来。
 *
 * 底色不透明：模糊位图没就绪时垫令牌里的深底，不会漏出页面底色。
 */
@Composable
internal fun ShortPlayBackdrop(play: ShortPlay) {
    BlurredCoverBackdrop(play.cover)
}
