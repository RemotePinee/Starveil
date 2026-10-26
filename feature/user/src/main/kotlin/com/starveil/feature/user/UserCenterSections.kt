package com.starveil.feature.user

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretRight
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/**
 * 区块渲染。每个 [SectionStyle] 一个组件，[UserSection] 只做分发——
 * 这样 6 套皮肤的差异永远只体现在 [UserCenterLayouts] 的配置里，不落到渲染分支。
 */
@Composable
internal fun UserSection(
    section: UserCenterSection,
    values: UserCenterValues,
    onEntryClick: (String) -> Unit,
    onLogoutClick: () -> Unit,
) {
    if (section.id == UserCenterSectionId.LOGOUT_ENTRY) {
        LogoutCard(onLogoutClick = onLogoutClick)
        return
    }
    when (section.style) {
        SectionStyle.LIST_ROW -> ListSection(section = section, onEntryClick = onEntryClick)
        SectionStyle.ICON_GRID -> IconGridSection(section = section, onEntryClick = onEntryClick)
        SectionStyle.STAT_ROW -> StatSection(section = section, values = values)
        SectionStyle.SUMMARY_ROW -> SummarySection(section = section, onEntryClick = onEntryClick)
        SectionStyle.SUBTITLED_GRID -> TiledSection(section = section, onEntryClick = onEntryClick)
        SectionStyle.BANNER -> BannerSection(section = section)
        SectionStyle.NOTICE_LIST -> NoticeSection(section = section, values = values)
    }
}

/** 统一的卡片外壳：圆角、底色、可选标题都来自令牌。 */
@Composable
private fun SectionShell(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageHorizontal),
        shape = AppShape.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(vertical = Spacing.xs)) {
            if (title.isNotEmpty()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.sm),
                )
            }
            content()
        }
    }
}

/** 整行列表（A 的全部区块）：圆点 + 文案 + 右箭头。 */
@Composable
private fun ListSection(section: UserCenterSection, onEntryClick: (String) -> Unit) {
    SectionShell(title = section.title) {
        section.entries.forEach { entry ->
            EntryRow(entry = entry, onClick = { onEntryClick(entry.label) })
        }
    }
}

@Composable
private fun EntryRow(entry: UserCenterEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        EntryDot()
        Text(
            text = entry.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = PhosphorIcons.Regular.CaretRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize.sm).mirrorIconInRtl(),
        )
    }
}

@Composable
private fun EntryDot() {
    Box(
        modifier = Modifier
            .size(IconSize.sm)
            .clip(AppShape.full)
            .background(MaterialTheme.colorScheme.primaryContainer),
    )
}

/** 图标网格（B–F）：按 [UserCenterSection.columns] 分行，最后一行用 Spacer 补位。 */
@Composable
private fun IconGridSection(section: UserCenterSection, onEntryClick: (String) -> Unit) {
    SectionShell(title = section.title) {
        section.entries.chunked(section.columns).forEach { cells ->
            GridRow(cells = cells, columns = section.columns, onEntryClick = onEntryClick)
        }
    }
}

@Composable
private fun GridRow(cells: List<UserCenterEntry>, columns: Int, onEntryClick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
    ) {
        cells.forEach { entry ->
            GridCell(
                entry = entry,
                modifier = Modifier.weight(1f),
                onClick = { onEntryClick(entry.label) },
            )
        }
        repeat(columns - cells.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun GridCell(entry: UserCenterEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        IconBubble(showsBadge = entry.showsBadge)
        Text(
            text = entry.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** 图标占位圆 + 未读红点。旧布局用位图图标，新工程不引入位图。 */
@Composable
private fun IconBubble(showsBadge: Boolean) {
    Box(
        modifier = Modifier
            .size(IconSize.lg)
            .clip(AppShape.full)
            .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        if (showsBadge) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(Spacing.sm)
                    .clip(AppShape.full)
                    .background(MaterialTheme.colorScheme.error),
            )
        }
    }
}

/** 数字栏（影豆 / 积分 / 金币 / 推广）。数值来自 `loadProfile()`，未到位时 [UserCenterValues.stat] 给占位符。 */
@Composable
private fun StatSection(section: UserCenterSection, values: UserCenterValues) {
    SectionShell(title = section.title) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.md),
        ) {
            section.entries.forEach { entry ->
                StatCell(
                    label = entry.label,
                    value = values.stat(entry.label),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 摘要卡（会员卡 / 钱包 / 分享卡）：标题上方的说明若干条 + 一个主按钮（取最后一条入口）。 */
@Composable
private fun SummarySection(section: UserCenterSection, onEntryClick: (String) -> Unit) {
    val action = section.entries.lastOrNull()
    SectionShell(title = section.title) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            section.entries.dropLast(1).forEach { detail ->
                Text(
                    text = detail.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = starveilExtraColors.textMuted,
                )
            }
            if (action != null) {
                Button(onClick = { onEntryClick(action.label) }, shape = AppShape.small) {
                    Text(text = action.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** 带副标题的瓷砖（F 的任务区）。 */
@Composable
private fun TiledSection(section: UserCenterSection, onEntryClick: (String) -> Unit) {
    SectionShell(title = section.title) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            section.entries.forEach { entry ->
                Tile(
                    entry = entry,
                    modifier = Modifier.weight(1f),
                    onClick = { onEntryClick(entry.label) },
                )
            }
        }
    }
}

@Composable
private fun Tile(entry: UserCenterEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .clip(AppShape.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Text(
            text = entry.label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = entry.subtitle.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
            maxLines = 1,
        )
    }
}

/** 广告位占位。旧布局是 ImageView / Banner 容器，新工程留空槽位等投放接入。 */
@Composable
private fun BannerSection(section: UserCenterSection) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageHorizontal),
        shape = AppShape.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Spacing.xxl * 2)
                .padding(Spacing.md),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 公告 + 历史记录（B–F）。公告字段后端没有，故只渲染观看记录条数。 */
@Composable
private fun NoticeSection(section: UserCenterSection, values: UserCenterValues) {
    SectionShell(title = section.title) {
        values.noticeLines.ifEmpty { listOf(NO_HISTORY) }.forEach { line ->
            NoticeRow(text = line)
        }
    }
}

@Composable
private fun NoticeRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(Spacing.xs)
                .clip(AppShape.full)
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun LogoutCard(onLogoutClick: () -> Unit) {
    OutlinedButton(
        onClick = onLogoutClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageHorizontal),
        shape = AppShape.small,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Text(text = "退出登录", style = MaterialTheme.typography.labelMedium)
    }
}

private const val NO_HISTORY = "暂无观看记录"
