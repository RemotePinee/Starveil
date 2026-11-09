package com.starveil.app.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Crown
import com.adamglin.phosphoricons.regular.Diamond
import com.adamglin.phosphoricons.regular.Fire
import com.adamglin.phosphoricons.regular.Gift
import com.adamglin.phosphoricons.regular.Lamp
import com.adamglin.phosphoricons.regular.Megaphone
import com.adamglin.phosphoricons.regular.Popcorn
import com.adamglin.phosphoricons.regular.Star
import com.adamglin.phosphoricons.regular.Timer
import com.adamglin.phosphoricons.regular.Wrench
import com.starveil.core.designsystem.component.StarveilBannerDialog
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.AppMessage

/**
 * 卡片模板的弹窗内容：完整的主图 + 文字压在底部。
 *
 * ## 图怎么摆
 * 主图**完整显示**（`Fit`，不裁不拉伸）。正方形卡里它只占上部 56%，下面留白——
 * 这块留白不铺纯色，而是铺**同一张图放大模糊后的副本**：视觉上仍是这张图在往下延续，
 * 文字压在上面不会像"图 + 色块"两张皮，也就不用为了铺满去裁图。
 *
 * 卡片比例、底色、渐隐都由 `StarveilBannerDialog` 提供，这里只管图上叠什么。
 */
@Composable
internal fun MessageCardPrompt(
    message: AppMessage,
    onDismiss: () -> Unit,
    onAction: (AppMessage) -> Unit,
) {
    StarveilBannerDialog(
        showClose = !message.force,
        onClose = onDismiss,
        // 强制弹出的关不掉：点外部、按返回都没反应，右上角也不给关闭按钮
        onDismissRequest = if (message.force) null else onDismiss,
        background = {
            CardBackdrop(url = message.cover)
            if (message.badge.isNotBlank() || message.icon.isNotBlank()) {
                CardBadge(icon = message.icon, text = message.badge)
            }
        },
    ) {
        if (message.title.isNotBlank()) {
            CardHeadline(text = message.title)
        }
        if (message.content.isNotBlank()) {
            Spacer(Modifier.height(Spacing.xs))
            CardBody(text = message.content)
        }
        // 与主按钮的距离只写这一处：正文在就接在正文后，正文没发就接在标题后
        Spacer(Modifier.height(Spacing.lg))
        CardPrimaryAction(
            text = message.cta.ifBlank { CARD_CTA_FALLBACK },
            onClick = { onAction(message) },
        )
        // 强制弹窗必须走主按钮，不给第二条退路
        if (!message.force) {
            Spacer(Modifier.height(Spacing.xxs))
            CardDismiss(onClick = onDismiss)
        }
    }
}

/**
 * 图：完整显示，不裁切、不拉伸，顶部对齐。
 *
 * 只有这一层：不加副本、不加遮罩压在图面上。图占卡片上部 56%，
 * 下方留给文字，两者接缝由卡片层的渐隐处理。
 */
@Composable
private fun BoxScope.CardBackdrop(url: String) {
    if (url.isBlank()) {
        return
    }
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        alignment = Alignment.TopCenter,
        modifier = Modifier.matchParentSize(),
    )
}

/**
 * 左上角药丸：图标 + 文字，只说"这是什么"，本身不可点。
 *
 * 高度锁 32dp，与右上角关闭按钮的圆同高同中线。图标用本地矢量图（Phosphor）而不是 Emoji：
 * Emoji 在厂商 ROM 上的字形、基线、尺寸都不一致，做不到"和文字垂直居中、按主题着色"。
 * 底色是中性深灰半透明、不是纯黑。
 */
@Composable
private fun BoxScope.CardBadge(icon: String, text: String) {
    val colors = starveilExtraColors
    val glyph = badgeIconOf(icon)
    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(Spacing.md)
            .height(Spacing.dialogCloseBadge)
            .clip(AppShape.full)
            .background(colors.onImageSurface.copy(alpha = BADGE_SCRIM_ALPHA))
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            Icon(
                imageVector = glyph,
                contentDescription = null,
                tint = colors.onImage,
                modifier = Modifier.size(IconSize.sm),
            )
            if (text.isNotBlank()) Spacer(Modifier.width(Spacing.xs))
        }
        if (text.isNotBlank()) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = BADGE_TRACKING),
                color = colors.onImage,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 角标 key → 图标。key 由后台 `PushPage::ICONS` 定义，两边必须同名。
 *
 * 返回 null 表示"不画图标"（空 key 或未知值）：宁可只显示文字，也不弹一个空白占位。
 */
private fun badgeIconOf(key: String): ImageVector? = when (key) {
    "fire" -> PhosphorIcons.Regular.Fire
    "gift" -> PhosphorIcons.Regular.Gift
    "crown" -> PhosphorIcons.Regular.Crown
    "timer" -> PhosphorIcons.Regular.Timer
    "tool" -> PhosphorIcons.Regular.Wrench
    "lantern" -> PhosphorIcons.Regular.Lamp
    "star" -> PhosphorIcons.Regular.Star
    "film" -> PhosphorIcons.Regular.Popcorn
    "notice" -> PhosphorIcons.Regular.Megaphone
    "gem" -> PhosphorIcons.Regular.Diamond
    else -> null
}

/** 标题：白色、左对齐、两行封顶。 */
@Composable
private fun CardHeadline(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = CARD_TITLE_TRACKING),
        color = starveilExtraColors.onImage,
        maxLines = CARD_TITLE_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 正文：次级白、四行封顶。 */
@Composable
private fun CardBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = CARD_BODY_LINE_HEIGHT),
        color = starveilExtraColors.onImageSubtle,
        maxLines = CARD_BODY_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 主按钮：半透明白玻璃 + 全圆角 + 深色字。 */
@Composable
private fun CardPrimaryAction(text: String, onClick: () -> Unit) {
    val colors = starveilExtraColors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Spacing.controlCompact)
            .clip(AppShape.full)
            .background(colors.onImage.copy(alpha = CTA_SURFACE_ALPHA))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(letterSpacing = CARD_CTA_TRACKING),
            color = colors.scrimStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Spacing.md),
        )
    }
}

/** 次一级退出：纯文字，不抢主按钮；强制弹窗不画它，见调用处。 */
@Composable
private fun CardDismiss(onClick: () -> Unit) {
    Text(
        text = DISMISS_LABEL,
        style = MaterialTheme.typography.bodySmall,
        color = starveilExtraColors.onImageFaint,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
    )
}


/** 角标底色透明度：中性深灰 62%，压亮画面也读得出白字。 */
private const val BADGE_SCRIM_ALPHA = 0.62f

/** 主按钮底色透明度：92% 白，透出一点底色但不影响深色字。 */
private const val CTA_SURFACE_ALPHA = 0.92f

/** 标题最多两行。 */
private const val CARD_TITLE_MAX_LINES = 2

/** 正文最多四行。 */
private const val CARD_BODY_MAX_LINES = 4

/** 标题字距：大字收 0.3sp，读起来是一整句。 */
private val CARD_TITLE_TRACKING = (-0.3).sp

/** 正文行高：14sp 正文配 21sp 行高，不挤成一坨。 */
private val CARD_BODY_LINE_HEIGHT = 21.sp

/** 药丸字距：小字放宽 0.4sp 才不显局促。 */
private val BADGE_TRACKING = 0.4.sp

/** 按钮字距：短词落在整块浅色上，放宽 0.4sp 才不显局促。 */
private val CARD_CTA_TRACKING = 0.4.sp

/** 主按钮默认文案：与窄条弹窗的推送默认值一致。 */
private const val CARD_CTA_FALLBACK = "立即查看"

/** 次按钮文案：与参考稿一致。 */
private const val DISMISS_LABEL = "稍后再说"
