package com.starveil.feature.home

import com.starveil.core.model.Vod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 首屏放行信号：列表落地后先解好这几张封面，再让启动层退场。
 *
 * 数据有了、图还没到就放人进去，看到的是一排等着图的卡片；先解好这几张，
 * 进首页的第一眼就是满图。封面是"锦上添花"，不是正确性的一部分——所以有超时，
 * 网络慢的时候宁可漏图也不能把启动屏卡住。
 *
 * 单独成文件是为了让 `HomeViewModel.kt` 留在 450 行上限内（AGENTS.md 第 2 节）。
 */
internal class FirstPageReady(
    private val warm: suspend (List<String>) -> Unit,
    private val onReady: () -> Unit,
) {

    private var fired = false

    fun signal(scope: CoroutineScope, vods: List<Vod>) {
        if (fired) {
            onReady()
            return
        }
        fired = true
        scope.launch {
            withTimeoutOrNull(WARM_TIMEOUT_MS) {
                warm(vods.mapNotNull { it.poster }.take(FIRST_PAGE_COVERS))
            }
            onReady()
        }
    }

    private companion object {
        /** 首屏封面最多占用启动屏这么久，超过就先放人进首页。 */
        const val WARM_TIMEOUT_MS = 1_200L
    }
}
