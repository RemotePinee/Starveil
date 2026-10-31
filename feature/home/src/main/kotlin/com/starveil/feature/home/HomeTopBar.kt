package com.starveil.feature.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.VodType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 顶部栏：一行操作区（logo 占位 / 搜索 / 筛选），一行分类。
 *
 * 搜索和分类拆成两行是参考小米视频那套：分类是浏览的主路径，搜索是调用动作，
 * 挤在一行时总有一边要将就。操作区里三块横向按 1:弹性:固定 排，
 * 分类独占一行、超出就横向滚动（两端渐隐由 [CategoryChips] 负责）。
 */
@Composable
fun HomeTopBar(
    hotWord: String,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    /** 每日推荐条数；> 0 时在搜索胶囊右上角冒红点，0 不占位。 */
    pushCount: Int = 0,
    types: List<VodType> = emptyList(),
    selectedTypeId: Int = 0,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 分类行到轮播的距离：只收底部，顶部那 4dp 留着，搜索行不至于贴到状态栏。
            .padding(
                start = Spacing.md,
                end = Spacing.md,
                top = Spacing.xs,
                bottom = Spacing.xxs,
            ),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandLogo()
            Spacer(Modifier.width(Spacing.sm))
            SearchPill(
                hotWord = hotWord,
                onSearch = actions.onSearch,
                pushCount = pushCount,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.sm))
            FilterButton(onClick = actions.onFilter)
        }
        if (types.isNotEmpty()) {
            CategoryChips(
                types = types,
                selectedTypeId = selectedTypeId,
                onSelect = actions.onSelectType,
                modifier = Modifier.fillMaxWidth(),
                leadingPadding = Spacing.xxs,
            )
        }
    }
}

/**
 * 左上角品牌图标。
 *
 * 图是带透明通道的 PNG（`drawable-nodpi/feature_home_logo.png`，192×192）：
 * 放 `nodpi` 是为了不被屏幕密度再缩一次把边缘糊掉；透明区直接透出页面背景，
 * 所以这里不加底板、也不裁圆角——形状由图片自己决定。
 */
@Composable
private fun BrandLogo() {
    Image(
        painter = painterResource(R.drawable.feature_home_logo),
        contentDescription = null,
        modifier = Modifier.size(LogoSize),
    )
}

/** 搜索胶囊：中间放热词（没有就写「搜索」），右端放大镜；按下缩一下再进搜索页。 */
@Composable
private fun SearchPill(
    hotWord: String,
    onSearch: () -> Unit,
    pushCount: Int,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(SearchPillHeight)
                .clickable(onClick = onSearch),
            shape = AppShape.full,
            color = starveilExtraColors.imageGlass,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = hotWord.ifBlank { "搜索影片、演员" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = starveilExtraColors.onImage,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.xs))
                Icon(
                    imageVector = PhosphorIcons.Regular.MagnifyingGlass,
                    contentDescription = "搜索",
                    tint = starveilExtraColors.onImage,
                    modifier = Modifier.size(IconSize.sm),
                )
            }
        }
        if (pushCount > 0) {
            UnreadDot(modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

/** 右端「筛选」：进片库筛选页，图标与文字都复用主题色，不新造一套按钮样式。 */
@Composable
private fun FilterButton(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .height(SearchPillHeight)
            // 先裁圆再挂点击：clickable 的 ripple 画在 Surface 裁剪之外，
            // 不裁就是点一下冒一个半透明矩形。
            .clip(AppShape.full)
            .clickable(onClick = onClick),
        shape = AppShape.full,
        color = starveilExtraColors.imageGlass,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = Spacing.md),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "筛选",
                style = MaterialTheme.typography.bodyMedium,
                color = starveilExtraColors.onImage,
                maxLines = 1,
            )
        }
    }
}

/** 未读红点：复用主题的 error 色，不新造一套角标样式。 */
@Composable
private fun UnreadDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(top = Spacing.xxs, end = Spacing.xxs)
            .size(Spacing.xs)
            .clip(AppShape.full)
            .background(MaterialTheme.colorScheme.error),
    )
}

/** 搜索胶囊与筛选按钮共用行高：操作区三个元素对齐到同一条基线。 */
internal val SearchPillHeight = Spacing.xxl + Spacing.xs

/** logo 占位方块边长，与搜索胶囊同高。 */
internal val LogoSize = SearchPillHeight

