package com.starveil.feature.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.ArrowsLeftRight
import com.adamglin.phosphoricons.regular.ListBullets
import com.starveil.core.data.LiveGroup
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.LiveChannel

/** 全屏顶部栏：返回 + 频道名 + 序号 + 选台入口。左右箭头已去掉，滑动与菜单都能换台。 */
@Composable
internal fun LivePlayerTopBar(
    name: String,
    position: String,
    onDismiss: () -> Unit,
    onOpenPicker: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = PhosphorIcons.Regular.ArrowLeft,
                contentDescription = "退出全屏",
                tint = Color.White,
                modifier = Modifier.mirrorInRtl(),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = position,
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.onImageSubtle,
                maxLines = 1,
            )
        }
        IconButton(onClick = onOpenPicker) {
            Icon(
                imageVector = PhosphorIcons.Regular.ListBullets,
                contentDescription = "选台",
                tint = Color.White,
                modifier = Modifier.mirrorInRtl(),
            )
        }
    }
}

/** 缓冲中 / 出错时的覆盖层。两者互斥，出错优先。 */
@Composable
internal fun LivePlayerStatus(loading: Boolean, error: String?) {
    if (error != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = starveilExtraColors.onImage,
                modifier = Modifier.padding(horizontal = Spacing.xxl),
            )
        }
        return
    }
    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(Spacing.xxl))
        }
    }
}

/** 进入全屏时的一次性提示：淡入停留几秒后淡出，不常驻挡画面。 */
@Composable
internal fun BoxScope.LivePlayerHint(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 3 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 3 }),
        modifier = Modifier.align(Alignment.Center),
    ) {
        Row(
            modifier = Modifier
                .clip(AppShape.full)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = PhosphorIcons.Regular.ArrowsLeftRight,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(IconSize.item),
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = "左右滑动切换频道",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
            )
        }
    }
}

/** 选台面板里的一项：原始下标 + 频道。分类过滤后下标不能重算，否则切错台。 */
internal class PickerEntry(val index: Int, val channel: LiveChannel)

/** 选台面板要用到的东西，收成对象只为压住参数个数（detekt 阈值 5）。 */
internal class LivePickerModel(
    val entries: List<PickerEntry>,
    val groups: List<LiveGroup>,
    val current: Int,
    val activeGroup: String,
)

/**
 * 选台面板：左侧分类、右侧频道，样式参考电视端直播菜单。
 *
 * 分类来自服务端下发（M3U 的 `group-title` 聚合），点分类会切回直播页并按该组重新拉列表；
 * 右侧只列当前已加载的频道，点一下直接切台。滑动换台在几百个台时并不好用，
 * 这个面板是主要的选台方式。
 */
@Composable
internal fun BoxScope.LiveChannelPicker(
    model: LivePickerModel,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    onSelectGroup: (String) -> Unit,
) {
    // 面板里的"当前分类"是本地状态：点分类不立刻关面板，先看该组有哪些台
    var localGroup by remember(model.activeGroup) { mutableStateOf(model.activeGroup) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    // 遮罩：半透明，仍然看得见后面的视频
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onDismiss),
    )
    // 从右侧滑出的半透明面板：左分类、右频道，叠在画面上而不是占掉下半屏
    AnimatedVisibility(
        visible = shown,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
        modifier = Modifier.align(Alignment.CenterEnd),
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(PICKER_WIDTH_FRACTION)
                .background(Color.Black.copy(alpha = 0.82f))
                .navigationBarsPadding()
                .statusBarsPadding()
                .clickable(enabled = false) { /* 吃掉内部点击，避免穿透到遮罩 */ },
        ) {
            LivePickerGroups(
                groups = model.groups,
                active = localGroup,
                onSelect = { name ->
                    localGroup = name
                    onSelectGroup(name)
                },
            )
            LivePickerChannels(
                entries = model.entries.filter { entry ->
                    localGroup.isEmpty() || entry.channel.group == localGroup
                },
                current = model.current,
                onPick = onPick,
            )
        }
    }
}

/** 左列：分类 + 每组数量。 */
@Composable
private fun LivePickerGroups(
    groups: List<LiveGroup>,
    active: String,
    onSelect: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .width(PICKER_GROUP_WIDTH)
            .fillMaxHeight()
            .background(Color.White.copy(alpha = 0.06f))
            .padding(vertical = Spacing.sm),
    ) {
        items(items = groups, key = { it.name }) { group ->
            val selected = group.name == active
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(group.name) }
                    .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = group.name.ifBlank { "全部" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) {
                        starveilExtraColors.accent
                    } else {
                        // 面板压在半透明黑底 + 视频上：必须用"画面上的文字"令牌，
                        // 用 textMuted 在浅色主题下是深灰，等于看不清（见 starveilExtraColors.onImage*）
                        starveilExtraColors.onImageSubtle
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = group.count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = starveilExtraColors.onImageSubtle,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.LivePickerChannels(
    entries: List<PickerEntry>,
    current: Int,
    onPick: (Int) -> Unit,
) {
    if (entries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "这个分类还没加载频道，先回列表滑到底再进来",
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.onImageSubtle,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.weight(1f).fillMaxHeight()) {
        itemsIndexed(items = entries, key = { _, entry -> entry.channel.id }) { _, entry ->
            val channel = entry.channel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(entry.index) }
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var nameOverflowed by remember(channel.id) { mutableStateOf(false) }
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (entry.index == current) {
                        starveilExtraColors.accent
                    } else {
                        starveilExtraColors.onImage
                    },
                    maxLines = 1,
                    // 窄列里省略号会切出一个硬缺口，名字长就右端渐隐
                    overflow = TextOverflow.Clip,
                    onTextLayout = { result -> nameOverflowed = result.hasVisualOverflow },
                    modifier = Modifier
                        .weight(1f)
                        .then(if (nameOverflowed) Modifier.fadeEnd() else Modifier),
                )
                if (entry.index == current) {
                    Text(
                        text = "正在播放",
                        style = MaterialTheme.typography.labelSmall,
                        color = starveilExtraColors.accent,
                    )
                }
            }
        }
    }
}

/** 侧边面板宽度占屏幕的比例：收窄到三分之一出头，画面留得多，频道名靠渐隐兜住。 */
private const val PICKER_WIDTH_FRACTION = 0.36f

/** 左侧分类栏宽度：分组名本来就短，再窄会挤掉每组数量。 */
private val PICKER_GROUP_WIDTH = 96.dp

/** 渐隐从这一比例开始，前面保持完全不透明。 */
private const val FADE_SOLID_UNTIL = 0.72f

/**
 * 文字右端渐隐：只在真溢出时用（调用方按 `hasVisualOverflow` 判断），
 * 短名字不会被啃掉结尾，长名字也不会切出硬边。
 */
private fun Modifier.fadeEnd(): Modifier = drawWithContent {
    drawContent()
    drawRect(
        brush = Brush.horizontalGradient(
            0f to Color.Black,
            FADE_SOLID_UNTIL to Color.Black,
            1f to Color.Transparent,
            endX = size.width,
        ),
        blendMode = BlendMode.DstIn,
    )
}
