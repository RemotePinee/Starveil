package com.starveil.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.PaperPlaneTilt
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import java.util.Locale

/** 弹幕字号档位。只给两档：档位多了在横屏里选不动，也没人能分辨 0.05 的差别。 */
internal const val DANMU_FONT_DEFAULT = 1f

internal const val DANMU_FONT_SMALL = 0.85f

/** 可见行数档位：默认 3 行，最多 6 行（再多就盖住画面了）。 */
internal val DANMU_LANE_STEPS = listOf(2, 3, 4, 6)

/** 不透明度档位：默认不透明，必要时降下来让画面透出来。 */
internal val DANMU_OPACITY_STEPS = listOf(1f, 0.8f, 0.6f)

/** 默认不透明度（满不透明）。 */
internal const val DANMU_OPACITY_FULL = 1f

/** 输入胶囊高度 36dp。 */
private val InputHeight: Dp = Spacing.xxl + Spacing.xs

/** 选项行左侧标签宽度：容纳"字体大小""显示区域"这类四字标签。 */
private val LabelWidth: Dp = Spacing.xxl * 2

/** 色块点击区 32dp / 选中环 28dp / 色块 24dp。 */
private val SwatchTouch: Dp = Spacing.xxl

private val SwatchRing: Dp = Spacing.xl + Spacing.xs

private val SwatchDot: Dp = Spacing.xl

/** 显示偏好：行数 / 区域 / 不透明度。 */
@Immutable
internal class DanmuLayoutPrefs(
    val laneCount: Int,
    val areaFull: Boolean,
    val opacity: Float,
)

/** 字号与颜色：发送时用，同时作用于弹幕层显示。 */
@Immutable
internal class DanmuStyle(
    val colorIndex: Int,
    val fontScale: Float,
    val layout: DanmuLayoutPrefs,
)

/** 外观类回调：颜色、字号。 */
internal class DanmuLookEvents(
    val onColorSelected: (Int) -> Unit,
    val onFontScaleSelected: (Float) -> Unit,
)

/** 显示类回调：行数、区域、不透明度。 */
internal class DanmuLayoutEvents(
    val onLaneCountSelected: (Int) -> Unit,
    val onAreaSelected: (Boolean) -> Unit,
    val onOpacitySelected: (Float) -> Unit,
)

/** 面板回调集合（拆两层是为了不超过 detekt 的参数上限）。 */
internal class DanmuEvents(
    val look: DanmuLookEvents,
    val layout: DanmuLayoutEvents,
)

/** 弹幕面板的当前选择 + 回调。 */
internal class DanmuSettings(
    val style: DanmuStyle,
    val events: DanmuEvents,
)

/**
 * 弹幕弹层的内容：输入 + 字号 + 显示行数 / 区域 / 不透明度 + 颜色。
 *
 * 外层容器是 [PlayerSheetHost] 的 `ModalBottomSheet`，与选集/倍速同一套底部弹层——
 * 面板画在播放器内部时，详情页的 16:9 小窗里会被压成一条。
 *
 * 字号、行数、区域、不透明度都是本地显示偏好，立刻作用到弹幕层；
 * 颜色只在发送时随弹幕带给后端（CMS 弹幕表里的 `danmaku_color`）。
 */
@Composable
internal fun DanmuSheetContent(settings: DanmuSettings, onSend: (String, String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        ComposerInputRow(settings = settings, onSend = onSend)
        OptionRow(label = "字体大小") {
            FontSizeChip(
                label = "默认",
                selected = settings.style.fontScale == DANMU_FONT_DEFAULT,
                onClick = { settings.events.look.onFontScaleSelected(DANMU_FONT_DEFAULT) },
            )
            FontSizeChip(
                label = "较小",
                selected = settings.style.fontScale == DANMU_FONT_SMALL,
                onClick = { settings.events.look.onFontScaleSelected(DANMU_FONT_SMALL) },
            )
        }
        OptionRow(label = "显示行数") {
            DANMU_LANE_STEPS.forEach { lanes ->
                FontSizeChip(
                    label = "$lanes 行",
                    selected = settings.style.layout.laneCount == lanes,
                    onClick = { settings.events.layout.onLaneCountSelected(lanes) },
                )
            }
        }
        OptionRow(label = "显示区域") {
            FontSizeChip(
                label = "顶部半屏",
                selected = !settings.style.layout.areaFull,
                onClick = { settings.events.layout.onAreaSelected(false) },
            )
            FontSizeChip(
                label = "全屏",
                selected = settings.style.layout.areaFull,
                onClick = { settings.events.layout.onAreaSelected(true) },
            )
        }
        OptionRow(label = "不透明度") {
            DANMU_OPACITY_STEPS.forEach { value ->
                FontSizeChip(
                    label = "${(value * 100).toInt()}%",
                    selected = settings.style.layout.opacity == value,
                    onClick = { settings.events.layout.onOpacitySelected(value) },
                )
            }
        }
        OptionRow(label = "弹幕颜色") { SwatchStrip(settings = settings) }
    }
}

/** 输入行：当前颜色小圆点 + 输入胶囊 + 发送。 */
@Composable
private fun ComposerInputRow(settings: DanmuSettings, onSend: (String, String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val colors = starveilExtraColors.danmuColors
    val color = colors.getOrElse(settings.style.colorIndex) { colors.first() }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(SwatchDot)
                .clip(AppShape.full)
                .background(color),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(InputHeight)
                .clip(AppShape.full)
                .background(starveilExtraColors.chipBackground)
                .padding(horizontal = Spacing.md),
            contentAlignment = Alignment.CenterStart,
        ) {
            Field(text = text, onTextChange = { text = it })
        }
        SendButton(
            enabled = text.isNotBlank(),
            onClick = {
                onSend(text.trim(), colorHexOf(color))
                text = ""
            },
        )
    }
}

/** 输入框本体：占位文案在没内容时顶上来，光标走主色。 */
@Composable
private fun Field(text: String, onTextChange: (String) -> Unit) {
    BasicTextField(
        value = text,
        onValueChange = onTextChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onBackground),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) {
                    Text(
                        text = "发个友善的弹幕见证当下",
                        style = MaterialTheme.typography.bodySmall,
                        color = starveilExtraColors.textFaint,
                    )
                }
                inner()
            }
        },
    )
}

/** 发送键 32dp：没内容时降到弱化色且点不动，避免发空弹幕。 */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) MaterialTheme.colorScheme.primary else starveilExtraColors.textFaint
    Box(
        modifier = Modifier
            .size(SwatchTouch)
            .clip(AppShape.full)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = PhosphorIcons.Regular.PaperPlaneTilt,
            contentDescription = "发送",
            tint = tint,
            modifier = Modifier.size(IconSize.item).mirrorIconInRtl(),
        )
    }
}

/** 选项行：左侧定宽标签，右侧控件区。 */
@Composable
private fun OptionRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = starveilExtraColors.textMuted,
            modifier = Modifier.width(LabelWidth),
        )
        content()
    }
}

/** 档位胶囊：选中用主色实底。 */
@Composable
private fun FontSizeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.primary else starveilExtraColors.chipBackground
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else starveilExtraColors.textMuted
    Box(
        modifier = Modifier
            .height(SwatchRing)
            .clip(AppShape.full)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}

/** 色块条：横向可滚，色板以后加色不用改布局。 */
@Composable
private fun RowScope.SwatchStrip(settings: DanmuSettings) {
    val colors = starveilExtraColors.danmuColors
    LazyRow(
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(items = colors) { index, color ->
            Swatch(
                color = color,
                selected = index == settings.style.colorIndex,
                onClick = { settings.events.look.onColorSelected(index) },
            )
        }
    }
}

/** 单个色块：选中时外面套一圈主色环，白/黄色块也看得见选中态。 */
@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(SwatchTouch).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(SwatchRing)
                    .border(Spacing.xxs, MaterialTheme.colorScheme.primary, AppShape.full),
            )
        }
        Box(
            modifier = Modifier
                .size(SwatchDot)
                .clip(AppShape.full)
                .background(color),
        )
    }
}

/** 色块 → 后端要的 6 位十六进制（不带 `#`）。 */
private fun colorHexOf(color: Color): String =
    String.format(Locale.ROOT, "%06X", color.toArgb() and 0xFFFFFF)
