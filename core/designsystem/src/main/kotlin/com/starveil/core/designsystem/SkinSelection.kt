package com.starveil.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import com.starveil.core.common.AppearanceMode
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.SkinPalettes
import com.starveil.core.designsystem.token.Spacing

/** 一个可选的皮肤：code 与后端 `app_theme_ui` 同一套取值，空串表示"跟随服务端"。 */
data class SkinOption(
    val code: String,
    val label: String,
    val accent: Color,
    val badge: Color,
)

/**
 * 皮肤选项。色块取**深色版**强调色：它是色相预览，深色版在白底/黑底上都看得清，
 * 不用随当前明暗换一套（浅色版是 700 档，圆点会显得发闷）。
 */
val StarveilSkinOptions: List<SkinOption> = buildList {
    add(SkinOption("", "跟随服务端", SkinPalettes.default.dark.extra.accent, SkinPalettes.default.dark.extra.badge))
    add(SkinOption("1", "石墨灰", SkinPalettes.graphite.dark.extra.accent, SkinPalettes.graphite.dark.extra.badge))
    add(SkinOption("2", "暖金", SkinPalettes.katong.dark.extra.accent, SkinPalettes.katong.dark.extra.badge))
    add(SkinOption("3", "B 站粉", SkinPalettes.bilibili.dark.extra.accent, SkinPalettes.bilibili.dark.extra.badge))
    add(SkinOption("4", "B 站紫", SkinPalettes.bilibili2.dark.extra.accent, SkinPalettes.bilibili2.dark.extra.badge))
    add(SkinOption("5", "AQC 天蓝", SkinPalettes.aqc.dark.extra.accent, SkinPalettes.aqc.dark.extra.badge))
}

/**
 * 当前皮肤与切换入口。
 *
 * `current` 是用户选的那一个（空串 = 跟随服务端）；`effective` 是真正生效的皮肤码，
 * 页面要用颜色时只认主题令牌，不需要读它，只有选择器要把当前项打勾。
 */
class SkinSelection(
    val current: String,
    val effective: String,
    val select: (String) -> Unit,
)

val LocalSkinSelection = staticCompositionLocalOf {
    SkinSelection(current = "", effective = "", select = {})
}

/**
 * 皮肤选择器：底部弹层。
 *
 * 视觉取舍：**只有选中项有底色**。之前每行都是一张大圆角卡片，六行叠在一起像六个按钮，
 * 既重又看不出当前选的是哪个；现在未选中行透明，选中行才铺 surfaceVariant + 加粗 + 勾。
 *
 * 左侧色块从"两个方角块叠一起"改成同心圆：外圈是这套皮肤的强调色、中心圆点是角标色，
 * 一眼能看清这套皮肤的两个落点，也不会因为两个方块错位显得脏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkinPickerSheet(
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
        ) {
            Text(
                text = "主题",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "换的是配色；浅色 / 深色在「设置 → 外观」里切",
                style = MaterialTheme.typography.labelMedium,
                color = starveilExtraColors.textMuted,
                modifier = Modifier.padding(top = Spacing.xxs, bottom = Spacing.md),
            )
            StarveilSkinOptions.forEach { option ->
                SkinRow(
                    option = option,
                    selected = option.code == selected,
                    onClick = { onSelect(option.code) },
                )
            }
        }
    }
}

@Composable
private fun SkinRow(option: SkinOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShape.large)
            // 顺序：clip → background → clickable，水波纹不会溢出圆角，未选中行不留色块
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // 同心圆预览：外圈强调色 + 中心角标色
        Box(
            modifier = Modifier
                .size(SKIN_PREVIEW_SIZE)
                .clip(AppShape.full)
                .background(option.accent),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(SKIN_PREVIEW_DOT)
                    .clip(AppShape.full)
                    .background(option.badge),
            )
        }
        Text(
            text = option.label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = PhosphorIcons.Regular.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(IconSize.md),
            )
        }
    }
}

/**
 * 明暗外观：当前值与切换入口。
 *
 * 与 [SkinSelection] 平行——皮肤管色相，它管亮/暗。两者都由 `:app` 在 Compose 顶层提供，
 * 页面只读不写。
 */
class AppearanceSelection(
    val current: AppearanceMode,
    val select: (AppearanceMode) -> Unit,
)

val LocalAppearanceSelection = staticCompositionLocalOf {
    AppearanceSelection(AppearanceMode.SYSTEM) {}
}

/** 明暗选择器：底部弹层，三行（跟随系统 / 浅色 / 深色）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearancePickerSheet(
    current: AppearanceMode,
    onSelect: (AppearanceMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = "外观",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = Spacing.md),
            )
            AppearanceMode.entries.forEach { mode ->
                AppearanceRow(
                    label = mode.displayName,
                    selected = mode == current,
                    onClick = { onSelect(mode) },
                )
            }
        }
    }
}

@Composable
private fun AppearanceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShape.large)
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = PhosphorIcons.Regular.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(IconSize.md),
            )
        }
    }
}

/** 皮肤预览圆直径：够看清同心圆，又不至于把行撑高。 */
private val SKIN_PREVIEW_SIZE = Spacing.xxl + Spacing.sm

/** 预览圆中心的概念点：角标色的落点。 */
private val SKIN_PREVIEW_DOT = Spacing.md
