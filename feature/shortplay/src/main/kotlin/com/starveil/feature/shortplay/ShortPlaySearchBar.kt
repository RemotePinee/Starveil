package com.starveil.feature.shortplay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.bold.MagnifyingGlass
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.X
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.designsystem.component.ImageBlurTransformation
import com.starveil.core.designsystem.starveilExtraColors

/** 收起与展开共用的高度：和列表行同档，展开时页面不抖。 */
internal val SearchBarHeight = Spacing.rowHeight - Spacing.sm

/**
 * 展开后的宽度。
 *
 * 刻意不铺满：它是从图标里长出来的一段，不是页面的第二根标题栏。208dp 在 411dp
 * 的机身上占一半，右侧图标位置固定，视觉上整个胶囊是从图标左缘延伸出去的。
 */
internal val SearchBarExpandedWidth = 208.dp

/**
 * 剧场页的内联搜索。
 *
 * 收起态**没有容器**：只有一根 20dp 的放大镜压在画面上，不套圆、不垫底色。
 * 点开后同一条胶囊从左往右长出来，右端的图标留在原地淡出，左端图标与输入框
 * 滑入——中间每一帧都是合法药丸，不会先出现一个方块再变圆。
 */
@Composable
internal fun ShortPlaySearchBar(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    // 首页同款的半透明胶囊，不再用药丸自己的模糊底。
    val container by animateColorAsState(
        targetValue = if (expanded) starveilExtraColors.imageGlass else Color.Transparent,
        animationSpec = tween(durationMillis = Motion.ShortMs, easing = Motion.standard),
        label = "shortplay-search-container",
    )

    val outline by animateColorAsState(
        targetValue = if (expanded) colors.outlineVariant else Color.Transparent,
        animationSpec = tween(durationMillis = Motion.MediumMs, easing = Motion.standard),
        label = "shortplay-search-outline",
    )

    Box(
        modifier = modifier
            .height(SearchBarHeight)
            .clip(AppShape.full)
            .background(container)
            .border(Spacing.hairline, outline, AppShape.full)
            .clickable(
                enabled = !expanded,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "展开搜索",
                onClick = { onExpandedChange(true) },
            ),
        contentAlignment = Alignment.CenterEnd,
    ) {
        // 首页同款胶囊：底色是半透明玻璃，里面不铺任何图。
        AnimatedVisibility(
            visible = !expanded,
            enter = fadeIn(tween(durationMillis = Motion.ShortMs)),
            exit = fadeOut(tween(durationMillis = Motion.ShortMs)) +
                scaleOut(targetScale = 0.82f, animationSpec = tween(durationMillis = Motion.ShortMs)),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = Spacing.md),
        ) {
            Icon(
                // 顶部入口图标放大一档并加粗：压在视频上，细线在亮背景里会糊掉。
                PhosphorIcons.Bold.MagnifyingGlass,
                contentDescription = "搜索短剧",
                tint = starveilExtraColors.onImage,
                modifier = Modifier.size(IconSize.md),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(durationMillis = Motion.MediumMs, delayMillis = Motion.ShortMs)) +
                slideInHorizontally(
                    animationSpec = tween(durationMillis = Motion.MediumMs, easing = Motion.emphasized),
                    initialOffsetX = { it / 3 },
                ),
            exit = fadeOut(tween(durationMillis = Motion.ShortMs)),
            modifier = Modifier.fillMaxSize(),
        ) {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                onSubmit = { onSubmit(query) },
                onCollapse = { onExpandedChange(false) },
            )
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCollapse: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface)
    val focusRequester = remember { FocusRequester() }
    // 这一段只在展开时组合，首次进入就把焦点交给输入框，用户不用再点一次。
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            PhosphorIcons.Regular.MagnifyingGlass,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(IconSize.sm),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("搜索短剧", style = textStyle, color = colors.onSurfaceVariant)
                    }
                    inner()
                }
            },
        )
        Icon(
            PhosphorIcons.Regular.X,
            contentDescription = "收起搜索",
            tint = colors.onSurfaceVariant,
            modifier = Modifier
                .size(Spacing.lg)
                .clickable(onClick = onCollapse),
        )
    }
}

/** 顶部搜索框的外部状态：展开与否、以及提交回调。 */
internal data class ShortPlaySearchState(
    val expanded: Boolean = false,
    val onExpandedChange: (Boolean) -> Unit = {},
    val onSubmit: (String) -> Unit = {},
)
