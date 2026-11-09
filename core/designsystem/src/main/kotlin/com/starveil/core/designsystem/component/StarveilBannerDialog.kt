package com.starveil.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.X
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing

/**
 * 卡片式弹窗外壳：正方形卡，图铺满整卡、文字压在底部渐隐上。
 *
 * ## 为什么图要铺满
 * 卡片是 1:1，素材大多是 16:9。图若按比例完整放进卡片，只会占 56% 高，
 * 剩下 44% 是一条纯色带——文字全落在那条带子里，看着像"上半张图 + 下半块色"。
 * 铺满之后图延伸到卡片底，文字压在图上，渐隐负责让文字读得清，
 * 整张卡才是一体的。
 *
 * ## 为什么不会有"分割线"
 * 渐变终点是**不透明的卡片底色**，并且从 [SCRIM_START] 一路铺到卡片底：
 * 收尾处与底色完全同值，叠加后深浅一致，所以不存在渐变与底色交界。
 * 反过来（两处都用半透明）会叠出更深的覆盖区，那才是怎么调都有线的原因。
 *
 * ## 可关闭性
 * [onDismissRequest] 传 null 表示**不可关闭**：点外部、返回键都不生效，右上角也不画关闭按钮。
 */
@Composable
fun StarveilBannerDialog(
    modifier: Modifier = Modifier,
    showClose: Boolean = true,
    onClose: () -> Unit = {},
    onDismissRequest: (() -> Unit)? = null,
    background: @Composable BoxScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { onDismissRequest?.invoke() },
        properties = DialogProperties(
            dismissOnBackPress = onDismissRequest != null,
            dismissOnClickOutside = onDismissRequest != null,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // 窗口级背景模糊：糊的是弹窗背后的页面内容，弹窗自己的文字不受影响
        BlurBehindEffect()
        BannerSurface(
            modifier = modifier,
            showClose = showClose,
            onClose = onClose,
            background = background,
            content = content,
        )
    }
}

/**
 * 定位与入场：卡片 + 关闭按钮一起缩放淡入。
 *
 * 外层给两侧留白、内层封顶宽度——顺序不能反：先 `fillMaxWidth()` 会把宽度定成常量，
 * 之后的 `widthIn(max = …)` 直接失效（[StarveilDialog] 踩过这个坑）。
 */
@Composable
private fun BannerSurface(
    modifier: Modifier = Modifier,
    showClose: Boolean,
    onClose: () -> Unit,
    background: @Composable BoxScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(Motion.MediumMs),
        label = "starveil-banner-in",
    )

    Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = Spacing.dialogBannerMaxWidth)
                .fillMaxWidth()
                .graphicsLayer {
                    // 0.96 浮到位 + 淡入；与 StarveilDialog 保持同一手感
                    scaleX = SCALE_FROM + (1f - SCALE_FROM) * progress
                    scaleY = scaleX
                    alpha = progress
                },
        ) {
            BannerCard(background = background, content = content)
            if (showClose) BannerCloseBadge(onClose = onClose)
        }
    }
}

/** 卡面：正方形，图铺满，渐隐 + 文字沉底。 */
@Composable
private fun BannerCard(
    background: @Composable BoxScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glow = starveilExtraColors.onImage
    val surface = starveilExtraColors.onImageSurface
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(CARD_ASPECT)
            .clip(AppShape.extraLarge)
            .background(surface)
            .border(Spacing.hairline, glow.copy(alpha = CARD_BORDER_ALPHA), AppShape.extraLarge),
    ) {
        // 图 + 角标（角标由调用方叠在图左上角）
        background()
        // 渐隐：图从 [SCRIM_START] 开始变暗，到 [SCRIM_END]（图的下沿）已经是完全不透明的卡片底色
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        // 四档而不是三档：三档从"全透"到"全不透"跨 16% 太缓，
                        // 图下沿（56%）处只覆盖了八成多，仍能看出那条线。
                        // 这里 50% 先压到六成、56% 已经是完整底色，边界被彻底盖住。
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            SCRIM_START to Color.Transparent,
                            SCRIM_MID to surface.copy(alpha = SCRIM_MID_ALPHA),
                            SCRIM_END to surface,
                            1f to surface,
                        ),
                    ),
                ),
        )
        // 文字：沉底，标题落在渐隐区、按钮在更实的一段
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
            content = content,
        )
    }
}

/**
 * 关闭按钮：与左上角角标同高、同中线、同边距。
 *
 * 32dp 盒 + 32dp 圆：圆心落在 16+16=32dp，与 32dp 高的药丸中线重合；
 * 圆右边缘离卡边也是 [Spacing.md]，与药丸左侧留白对称。
 * 不复用 `CloseBadge`：那个是 56dp 盒 + 32dp 圆，盒顶不等于圆顶，两边永远差 6-8dp。
 */
@Composable
private fun BoxScope.BannerCloseBadge(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = Spacing.md, end = Spacing.md)
            .size(Spacing.dialogCloseBadge)
            .clip(CircleShape)
            // 中性深灰半透明：压亮图也读得出白 X；纯黑会像一块补丁
            .background(starveilExtraColors.onImageSurface.copy(alpha = CLOSE_SCRIM_ALPHA))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = PhosphorIcons.Regular.X,
            contentDescription = "关闭",
            tint = starveilExtraColors.onImage,
            modifier = Modifier.size(IconSize.sm),
        )
    }
}

/** 整卡宽高比：1:1。 */
private const val CARD_ASPECT = 1f

/**
 * 渐隐起点：38% 之前图面完整。
 *
 * 过渡带要够长才不像"切一刀"：从 38% 一路走到图的下沿（56%），
 * 中间再插一档把曲线压实，图边界处正好是完全不透明的卡片底色。
 */
private const val SCRIM_START = 0.38f

/** 中段位置：48% 处先压到八成，后面这段就只剩很小的落差。 */
private const val SCRIM_MID = 0.48f

/** 中段透明度。 */
private const val SCRIM_MID_ALPHA = 0.80f

/**
 * 渐隐终点：56%。16:9 的图 `Fit` 进方形卡，下沿就在这里，
 * 到这里已经是不透明的卡片底色——图片自身的边界被完全盖住，不留线。
 */
private const val SCRIM_END = 0.56f

/** 入场起始缩放：0.96 是"浮上来"，0.9 以下就变成"弹出来"了。 */
private const val SCALE_FROM = 0.96f

/** 卡片描边透明度：只为把卡片从模糊背景里"起边"，看得见就过头了。 */
private const val CARD_BORDER_ALPHA = 0.12f

/** 关闭按钮圆的底色透明度。 */
private const val CLOSE_SCRIM_ALPHA = 0.55f
