package com.starveil.feature.shortplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.starveil.core.designsystem.starveilExtraColors

/**
 * 非 owner 页的底图：当前 feed 正在看的那部剧的海报。
 *
 * 保留这个函数是因为调用方仍在取它（换页时的封面延续）；占位页本身已经不再画任何东西。
 * 找不到封面时返回 null。
 */
internal fun ShortPlayUiState.placeholderCover(): String? {
    val content = this as? ShortPlayUiState.Content ?: return null
    val play = content.plays.firstOrNull { it.id == content.currentPlayId }
        ?: content.plays.firstOrNull()
    return play?.cover
}

/**
 * 非 owner 页的底：只有底色，**不画任何内容**。
 *
 * 这里先后放过「分类名 + 骨架条」和「杂志式大字 + 细线」两版，都被否了。原因是同一个：
 * 横滑的等待通常只有一个 RTT，那行字刚出现就被真实内容替换，观感上就是「闪了一下」。
 * 现在这一页在整个滑动过程中保持安静，落页后内容自己淡入。
 *
 * 底色必须自己铺满（不能透明），否则会露出 pager 根底色；用 scrimStrong 而不是纯黑，
 * 是为了和深色主题的页面底色区分开，横滑时能看出「这是另一页」。
 *
 * 不加任何动画：页面上已有位移在动，底越安静越好。
 */
@Composable
internal fun ShortPlayCategoryPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(starveilExtraColors.scrimStrong),
    )
}
