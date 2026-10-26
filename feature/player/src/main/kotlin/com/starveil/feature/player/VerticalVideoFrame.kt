package com.starveil.feature.player

internal data class VerticalVideoFrame(val width: Int, val height: Int)

/** 对异常尺寸/比例也不越界；用 Double 计算以覆盖极端但有效的视频比例。 */
internal fun fitVerticalVideo(width: Int, height: Int, aspectRatio: Float): VerticalVideoFrame {
    if (width <= 0 || height <= 0) return VerticalVideoFrame(0, 0)
    val ratio = aspectRatio.takeIf { it.isFinite() && it > 0f }?.toDouble() ?: 1.0
    val fittedHeight = width / ratio
    return if (fittedHeight <= height) {
        VerticalVideoFrame(width, fittedHeight.toInt().coerceIn(1, height))
    } else {
        VerticalVideoFrame((height * ratio).toInt().coerceIn(1, width), height)
    }
}

/** STATE_READY 不代表新集首帧已显示；权限未同步也只能显示等待，不能把旧集画面称为起播。 */
internal fun verticalNeedsBuffering(
    state: VerticalPlayUiState,
    hasCurrentFrame: Boolean,
    active: Boolean,
): Boolean {
    val hasPlaybackIntent = state.isPlaying && !state.isBlocked && state.errorMessage == null
    return hasPlaybackIntent && (state.isBuffering || !hasCurrentFrame || !active)
}

/** 默认正向 pager 的真实页坐标；半页换 currentPage 时仍保持连续，不额外插值。 */
internal fun verticalVideoTranslationY(
    mediaPage: Int?,
    currentPage: Int,
    offsetFraction: Float,
    pageStridePx: Int,
    preview: Boolean = false,
): Float {
    val invalidGeometry = pageStridePx <= 0 || !offsetFraction.isFinite()
    if (preview || mediaPage == null) return 0f
    return if (invalidGeometry) 0f else {
        (mediaPage.toFloat() - currentPage.toFloat() - offsetFraction) * pageStridePx
    }
}

/** 旧源可随自己的页退场；请求页只能露出匹配本次 source/revision 的真实首帧。 */
internal fun verticalPageShowsVideo(
    page: Int,
    requestedPage: Int,
    framePage: Int?,
    hasCurrentFrame: Boolean,
): Boolean {
    val ownsFrame = page == framePage
    val matchesRequest = page != requestedPage || hasCurrentFrame
    return ownsFrame && matchesRequest
}
