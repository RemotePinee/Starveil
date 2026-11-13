package com.starveil.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.TelevisionSimple
import com.starveil.core.designsystem.component.SkeletonBlock
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.CastDevice
import com.starveil.core.model.CastUiStatus

/** 设备区：标题行 + 内容，内容按四种状态切换。 */
@Composable
internal fun DevicesSection(
    status: CastUiStatus,
    onRetry: () -> Unit,
    onPick: (CastDevice) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitleRow(onRetry = onRetry)
        when (status) {
            CastUiStatus.Searching -> SearchingDevices()

            is CastUiStatus.Failed -> EmptyState(
                title = "搜索失败",
                body = status.reason,
                onRetry = onRetry,
            )

            is CastUiStatus.Devices -> if (status.devices.isEmpty()) {
                EmptyState(
                    title = "没有发现设备",
                    body = "确认电视与手机在同一 Wi-Fi，电视端投屏已打开",
                    onRetry = onRetry,
                )
            } else {
                DeviceList(devices = status.devices, onPick = onPick)
            }

            // 已经连上时面板会被关掉；这里只是兜底，不留白屏。
            else -> SearchingDevices()
        }
    }
}

/** 分区标题：左边说明，右边"重新搜索"。 */
@Composable
private fun SectionTitleRow(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "可选设备",
            style = MaterialTheme.typography.labelLarge,
            color = starveilExtraColors.textMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "重新搜索",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(AppShape.full)
                .clickable(onClick = onRetry)
                .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
        )
    }
}

/** 设备列表：最多这么高，再多就内部滚动，抽屉不会被设备数撑破。 */
@Composable
private fun DeviceList(devices: List<CastDevice>, onPick: (CastDevice) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = LIST_MAX_HEIGHT)) {
        items(devices, key = { it.id }) { device ->
            DeviceRow(device = device, onClick = { onPick(device) })
            SheetDivider()
        }
    }
}

/** 设备行：图标 + 名称 + 箭头，行间用发丝线分隔——不加整行色块。 */
@Composable
private fun DeviceRow(device: CastDevice, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(Spacing.xxl + Spacing.sm)
                .clip(AppShape.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = PhosphorIcons.Regular.TelevisionSimple,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(IconSize.item),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = Spacing.md)) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "可投屏",
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textMuted,
                modifier = Modifier.padding(top = Spacing.xxs),
            )
        }
        Icon(
            imageVector = PhosphorIcons.Regular.CaretRight,
            contentDescription = null,
            tint = starveilExtraColors.textMuted,
            modifier = Modifier.size(IconSize.sm),
        )
    }
}

/** 搜索中：三条骨架行，位置和真实设备行对齐，出结果时不跳。 */
@Composable
private fun SearchingDevices() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        repeat(SKELETON_ROWS) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Spacing.xxl + Spacing.sm),
                shape = AppShape.medium,
            )
        }
    }
}

/** 空结果与失败共用：图标 + 一句能照着做的话 + 重试。 */
@Composable
private fun EmptyState(title: String, body: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(STATE_HEIGHT)
            .padding(horizontal = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = PhosphorIcons.Regular.TelevisionSimple,
            contentDescription = null,
            tint = starveilExtraColors.textMuted,
            modifier = Modifier.size(IconSize.lg),
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        Text(
            text = "重新搜索",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = Spacing.sm)
                .clip(AppShape.full)
                .clickable(onClick = onRetry)
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        )
    }
}

/** 设备列表最高：七行左右，再多就在抽屉内部滚动。 */
private val LIST_MAX_HEIGHT = Spacing.xxl * 7

/** 空态 / 失败态的高度：占住一块稳定的地方，抽屉不会缩成一条。 */
private val STATE_HEIGHT = Spacing.xxl * 5

/** 搜索中的骨架行数：和常见设备数对齐，出结果时不跳。 */
private const val SKELETON_ROWS = 3
