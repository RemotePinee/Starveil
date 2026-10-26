package com.starveil.feature.player

internal data class VerticalPagerSnapshot(
    val requestedPage: Int,
    val settledPage: Int,
    val isScrolling: Boolean,
) {
    val isPlaybackReady: Boolean get() = !isScrolling && requestedPage == settledPage
}

internal sealed interface VerticalPagerAction {
    data object None : VerticalPagerAction
    data class SelectEpisode(val page: Int) : VerticalPagerAction
    data class ScrollToEpisode(val page: Int) : VerticalPagerAction
}

/** 仅 idle 时协调：真实落页改变优先；回弹原页则继续尚未完成的程序切集。 */
internal class VerticalPagerCoordinator(initialSettledPage: Int) {
    private var lastSettledPage = initialSettledPage

    fun next(snapshot: VerticalPagerSnapshot): VerticalPagerAction {
        if (snapshot.isScrolling) return VerticalPagerAction.None
        val pageChanged = snapshot.settledPage != lastSettledPage
        lastSettledPage = snapshot.settledPage
        return when {
            snapshot.requestedPage == snapshot.settledPage -> VerticalPagerAction.None
            pageChanged -> VerticalPagerAction.SelectEpisode(snapshot.settledPage)
            else -> VerticalPagerAction.ScrollToEpisode(snapshot.requestedPage)
        }
    }

    /** 程序动画的落页只作确认；被手势打断时保留旧基点，让真实落页优先。 */
    fun onProgramScrollFinished(target: Int, snapshot: VerticalPagerSnapshot) {
        if (!snapshot.isScrolling && snapshot.settledPage == target) lastSettledPage = target
    }
}
