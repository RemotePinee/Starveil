package com.starveil.app.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.starveil.app.R
import com.starveil.core.designsystem.component.StarveilDialog
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.AppMessage
import com.starveil.core.model.MessageKind
import com.starveil.core.model.MessageTemplate

/**
 * 弹窗内容：模板只决定**有没有装饰插画**，里面永远是同一套三段式——标题、正文、主按钮。
 *
 * 排版围绕"内容为主"的四条硬规则：
 * 1. 标题最多两行，超出省略；
 * 2. 正文占剩余空间上限，超出自己滚，不挤压按钮；
 * 3. 按钮高度固定、永远落在内容区底部；
 * 4. 装饰插画只是点缀，不参与内容节奏。
 */
@Composable
internal fun MessagePrompt(
    message: AppMessage,
    onDismiss: () -> Unit,
    onAction: (AppMessage) -> Unit,
) {
    // 卡片版式是另一套天平：整卡大图、左对齐、两级退出，不套用窄条弹窗的插画链路
    if (message.template == MessageTemplate.CARD) {
        MessageCardPrompt(message = message, onDismiss = onDismiss, onAction = onAction)
        return
    }
    val template = DialogTemplates.of(message.template)
    // 两段的"有没有"先算出来，后面的间距全按这两个开关走：
    // 空的 Text 会占行高、空的 Spacer 会把两档间距叠成一档，都会让版面松垮
    val hasTitle = message.title.isNotBlank()
    val hasBody = message.content.isNotBlank()

    StarveilDialog(
        artwork = template.artwork,
        artworkDrop = template.extraDrop,
        showClose = !message.force,
        onClose = onDismiss,
        // 强制弹出的关不掉：点外部、按返回都没反应，右上角也不给关闭按钮
        onDismissRequest = if (message.force) null else onDismiss,
    ) {
        if (hasTitle) {
            DialogTitle(text = message.title)
            // 标题与正文之间这一档只在两段都有内容时留
            if (hasBody) Spacer(Modifier.height(Spacing.sm))
        }
        if (hasBody) {
            DialogBody(text = message.content)
        }
        // 与主按钮的距离只写这一处：正文在就接在正文后，正文没发就接在标题后，
        // 一段字都没有时它也照样在——只有插画 + 按钮的弹窗同样需要这口呼吸
        Spacer(Modifier.height(Spacing.xl))
        PrimaryAction(text = ctaOf(message), onClick = { onAction(message) })
    }
}

/**
 * 标题：居中、无装饰、字距收紧。
 *
 * 22sp 是弹窗标题固定档位，与更新、隐私弹窗一致；收紧 0.4sp 读起来是一整句。
 */
@Composable
private fun DialogTitle(text: String) {
    val lineColor = starveilExtraColors.accent
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // 左侧渐隐线：透明 → accent
        Box(
            modifier = Modifier
                .weight(1f)
                .height(Spacing.hairline)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, lineColor),
                    ),
                ),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = TITLE_TRACKING),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = TITLE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(Spacing.sm))
        // 右侧渐隐线：accent → 透明
        Box(
            modifier = Modifier
                .weight(1f)
                .height(Spacing.hairline)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(lineColor, Color.Transparent),
                    ),
                ),
        )
    }
}

/**
 * 正文：居中、行高放宽，超过上限自己滚。
 *
 * 不设 maxLines：一旦限行，`verticalScroll` 只能滚到被截断处。高度上限交给 `heightIn`，
 * 按钮位置始终由按钮自己决定，正文再长也挤不动它。
 */
@Composable
private fun ColumnScope.DialogBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = BODY_LINE_HEIGHT),
        color = starveilExtraColors.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .heightIn(max = BODY_MAX_HEIGHT)
            .verticalScroll(rememberScrollState()),
    )
}

/** 主按钮：渐变底色走皮肤 accent → badge，六套皮肤自动适配。 */
@Composable
private fun PrimaryAction(text: String, onClick: () -> Unit) {
    val extra = starveilExtraColors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Spacing.controlCompact)
            .clip(AppShape.full)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(extra.accent, extra.badge),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                ),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(letterSpacing = CTA_TRACKING),
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Spacing.md),
        )
    }
}

/** 按钮文案：后台没填时按类型取默认值，公告是"我知道了"、推送是"立即查看"。 */
private fun ctaOf(message: AppMessage): String = message.cta.ifBlank {
    if (message.kind == MessageKind.PUSH) "立即查看" else "我知道了"
}

/**
 * 模板与装饰插画的对应关系。
 *
 * 插画由运营侧出（透明 PNG，已裁掉四周透明边），在卡片顶部居中显示，只是点缀。
 * [extraDrop] 是该模板插画额外下沉的偏移：爆米花素材底部飘一条丝带，
 * 默认重叠会显得丝带悬空，需要再下来一点才扎进卡片。
 */
internal enum class DialogTemplate(val artwork: Int?, val extraDrop: Dp = Spacing.none) {
    /** 纯文字：不加载任何图片，最轻，也不依赖素材。 */
    TEXT(null),

    /** 公告：粉紫喇叭插画。 */
    NOTICE(R.drawable.dialog_art_notice),

    /** 更新：暖橙场记板插画，丝带飘出素材底部，需额外下沉 8dp。 */
    UPDATE(R.drawable.dialog_art_update, extraDrop = Spacing.sm),
}

/** 后台下发的字符串 → 模板枚举。未知值退回 [DialogTemplate.TEXT]，不弹空白框。 */
internal object DialogTemplates {
    fun of(template: MessageTemplate): DialogTemplate = when (template) {
        MessageTemplate.NOTICE -> DialogTemplate.NOTICE
        MessageTemplate.UPDATE -> DialogTemplate.UPDATE
        MessageTemplate.TEXT -> DialogTemplate.TEXT
        // 卡片版式不画插画（背景图由 MessageCardPrompt 自己铺满），但枚举必须穷举
        MessageTemplate.CARD -> DialogTemplate.TEXT
    }
}

/** 标题最多两行：128 字的标题整段铺开会把按钮顶出内容区。 */
private const val TITLE_MAX_LINES = 2

/** 标题字距：22sp 字号上收 0.4sp，字与字靠拢一点，读起来才是一整句。 */
private val TITLE_TRACKING = (-0.4).sp

/** 按钮文字字距：短词落在整块纯色上，放宽 0.4sp 才不显局促。 */
private val CTA_TRACKING = 0.4.sp

/** 正文行高：13sp 正文压 21sp 行高，居中的一段话才不挤成一坨。 */
private val BODY_LINE_HEIGHT = 21.sp

/** 正文区高度上限：装饰高度 + 一档大间距（120+24=144dp），约占内容区一半。 */
private val BODY_MAX_HEIGHT = Spacing.dialogArtworkHeight + Spacing.xl
