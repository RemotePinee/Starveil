package com.starveil.feature.shortplay

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.ShortPlay

/**
 * 剧场页搜索浮层：边输边出候选，点一条才播。
 *
 * 输入只驱动候选（[onQueryChange] → ViewModel 去抖拉 `searchAllTypes`），
 * 播放由 [onPick] 触发；回车不再是"直接播第一部"。
 *
 * 胶囊底用 `imageGlass`（白 24%），视频从底下透出来才是玻璃；填不透明容器色只是白条。
 */
@Composable
internal fun ShortPlaySearchOverlay(
    visible: Boolean,
    candidates: List<ShortPlay>,
    onQueryChange: (String) -> Unit,
    onPick: (ShortPlay) -> Unit,
    onDismiss: () -> Unit = {},
) {
    if (!visible) return
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val width by animateDpAsState(
        targetValue = if (shown) SearchBarExpandedWidth else SearchBarHeight,
        animationSpec = spring(dampingRatio = Motion.SpringDampingRatio, stiffness = Motion.SpringStiffness),
        label = "shortplay-search-width",
    )
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = Motion.medium,
        label = "shortplay-search-alpha",
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                    .graphicsLayer { this.alpha = alpha },
                horizontalAlignment = Alignment.End,
            ) {
                SearchField(
                    onQueryChange = onQueryChange,
                    modifier = Modifier.width(width).height(SearchBarHeight),
                )
                if (candidates.isNotEmpty()) {
                    CandidateList(
                        candidates = candidates,
                        onPick = onPick,
                        modifier = Modifier
                            .padding(top = Spacing.sm)
                            .width(SearchBarExpandedWidth),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    val colors = starveilExtraColors
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    Row(
        modifier = modifier
            .clip(AppShape.full)
            .background(colors.imageGlass)
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            PhosphorIcons.Regular.MagnifyingGlass,
            contentDescription = null,
            tint = colors.onImage,
            modifier = Modifier.size(IconSize.sm),
        )
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "搜索短剧",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onImage,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = {
                    query = it
                    onQueryChange(it)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onImage),
                cursorBrush = SolidColor(colors.onImage),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
    }
}

@Composable
private fun CandidateList(
    candidates: List<ShortPlay>,
    onPick: (ShortPlay) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        modifier = modifier
            .heightIn(max = Spacing.xxl * 10)
            .clip(AppShape.medium)
            .background(colors.surfaceContainerHigh),
    ) {
        items(items = candidates, key = { it.id }) { play ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(play) }
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                AsyncImage(
                    model = play.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(Spacing.xxl + Spacing.md)
                        .height(Spacing.xxl + Spacing.xl)
                        .clip(AppShape.small),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = play.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // 搜索结果不带播放源，`episodeCount` 恒为 0；不要再显示"共0集"。
                        text = listOfNotNull(
                            play.category?.takeIf { it.isNotBlank() },
                            "共${play.episodeCount}集".takeIf { play.episodeCount > 0 },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
