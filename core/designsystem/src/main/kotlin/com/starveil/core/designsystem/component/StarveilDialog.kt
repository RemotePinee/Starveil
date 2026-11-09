package com.starveil.core.designsystem.component

import android.view.WindowManager
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.X
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing

/**
 * 全站统一弹窗外壳：材质、层级、入场都在这里，内容由调用方给。
 *
 * **插画溢出卡面，与卡片融为一体。** 插画底部扎进卡片 [ARTWORK_OVERLAP]，顶部自然溢出，
 * 接缝处垫一层皮肤色径向光晕，光晕把插画与卡片缝成一体，不是"上面插画、下面卡片"的割裂感。
 *
 * 卡片是一块中性 surface，不随插画或模板换色：插画本身已是全场最亮的一块，
 * 底色再跟着调色只会把对比抹平。卡片宽度封顶 [Spacing.dialogMaxWidth]，
 * 高度由内容撑开（普通 Column，没有固定高度），文案写长只是让卡片变高。
 *
 * [onDismissRequest] 传 null 表示**不可关闭**：点外部、返回键都不生效
 * （强制公告、只准接受的隐私说明用它），同时右上角也不画关闭按钮。
 */
@Composable
fun StarveilDialog(
    modifier: Modifier = Modifier,
    artwork: Int? = null,
    artworkDrop: Dp = Spacing.none,
    showClose: Boolean = true,
    onClose: () -> Unit = {},
    onDismissRequest: (() -> Unit)? = null,
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
        DialogSurface(
            modifier = modifier,
            artwork = artwork,
            artworkDrop = artworkDrop,
            showClose = showClose,
            onClose = onClose,
            content = content,
        )
    }
}

/**
 * 给弹窗窗口开系统级背景模糊（Android 12+ 生效，低版本静默无效）。
 *
 * `Modifier.blur` 只能糊自己绘制的内容，糊不到弹窗背后的页面；
 * `FLAG_BLUR_BEHIND` 才是系统合成的"毛玻璃"：背后的 App 内容被模糊，卡片用半透底透出来。
 */
@Composable
internal fun BlurBehindEffect() {
    val view = LocalView.current
    LaunchedEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        // blurBehindRadius 是 API 31 的 setter：低版本仍加 flag（无效果），但碰这个字段会崩
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.attributes = window.attributes.apply { blurBehindRadius = BLUR_BEHIND_RADIUS_PX }
        }
    }
}

/**
 * 窗口内的定位与入场：卡片 + 右上角关闭按钮，两者一起缩放淡入。
 *
 * 外层只负责两侧留白，内层负责宽度上限——顺序不能反：
 * `fillMaxWidth()` 会把宽度定成常量，之后再 `widthIn(max = ...)` 就失效了
 * （上一版正是这个顺序，令牌写了 340dp，真机上卡片却是 363dp）。
 */
@Composable
private fun DialogSurface(
    modifier: Modifier = Modifier,
    artwork: Int?,
    artworkDrop: Dp = Spacing.none,
    showClose: Boolean,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(Motion.MediumMs),
        label = "starveil-dialog-in",
    )

    Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = Spacing.dialogMaxWidth)
                .fillMaxWidth()
                .graphicsLayer {
                    // 入场：0.96 浮到位，配合淡入；收满后不再有动画
                    scaleX = DIALOG_SCALE_FROM + (1f - DIALOG_SCALE_FROM) * progress
                    scaleY = scaleX
                    alpha = progress
                },
        ) {
            DialogCard(artwork = artwork, artworkDrop = artworkDrop, content = content)
            // 关闭按钮相对卡片定位，不随内容高度浮动
            if (showClose) CloseBadge(onClose = onClose)
        }
    }
}

/**
 * 卡片本体：一块中性底 + 顶部溢出插画 + 文字区。
 *
 * 插画在上层（zIndex 1），底部 [ARTWORK_OVERLAP] 扎进卡片，顶部自然溢出。
 * 插画与卡片接缝处垫皮肤色光晕，光晕中心在接缝位置，上下各留一半，
 * 让插画看起来是从卡片里长出来的，不是两张皮贴在一起。
 *
 * [artworkDrop] 真正移动插画本身（`Modifier.offset`），标题、正文、按钮位置不动。
 */
@Composable
private fun DialogCard(artwork: Int?, artworkDrop: Dp = Spacing.none, content: @Composable ColumnScope.() -> Unit) {
    val accent = starveilExtraColors.accent
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        // 卡片底：半透明底，透出系统模糊后的页面内容，88% 不糊文字
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (artwork != null) ARTWORK_CARD_TOP else Spacing.none)
                .clip(AppShape.extraLarge)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = CARD_SURFACE_ALPHA)),
        ) {
            // 插画扎进卡片的重叠区：把卡片顶部 padding 撑开，给插画留位置
            if (artwork != null) {
                Spacer(Modifier.height(ARTWORK_OVERLAP))
            } else {
                // 没有插画时顶部留一档间距，避免标题贴到圆角上
                Spacer(Modifier.height(Spacing.xl))
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(
                    start = Spacing.xl,
                    end = Spacing.xl,
                    bottom = Spacing.xl,
                    // 有插画时标题紧跟着它，插画自己已经带了上边距
                    top = if (artwork == null) Spacing.none else Spacing.md,
                ),
                content = content,
            )
        }
        // 插画层：溢出卡片顶部，底部扎进卡片；artworkDrop 真正移动插画本身
        if (artwork != null) {
            DialogArtwork(image = artwork, glowColor = accent, drop = artworkDrop)
        }
    }
}

/**
 * 溢出插画：底部扎进卡片，顶部自然溢出，接缝处垫皮肤色光晕。
 *
 * 高度锁定 [Spacing.dialogArtworkHeight]，宽度按素材比例走 [ContentScale.Fit]。
 * 光晕中心对准插画与卡片的接缝，上下各留一半，把接缝糊成一体。
 */
@Composable
private fun DialogArtwork(image: Int, glowColor: Color, drop: Dp = Spacing.none) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Spacing.dialogArtworkHeight)
            // drop 用 offset 而不是 padding：offset 不影响布局高度，
            // 插画真正往下移，标题、正文、按钮的位置不动
            .offset(y = drop),
        contentAlignment = Alignment.Center,
    ) {
        // 光晕：径向渐变，中心在接缝（画布下方 53% 处），把插画与卡片缝成一体
        Canvas(modifier = Modifier.fillMaxWidth().height(Spacing.dialogArtworkHeight)) {
            val radius = size.minDimension * ARTWORK_GLOW_RADIUS_RATIO
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glowColor.copy(alpha = ARTWORK_GLOW_ALPHA),
                        Color.Transparent,
                    ),
                    center = Offset(size.width / 2f, size.height * ARTWORK_GLOW_CENTER_Y),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(size.width / 2f, size.height * ARTWORK_GLOW_CENTER_Y),
            )
        }
        Image(
            painter = painterResource(image),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(Spacing.dialogArtworkHeight),
        )
    }
}

/** 关闭按钮：56dp 触控盒里放一个 32dp 圆，视觉轻但点得到。 */
@Composable
internal fun BoxScope.CloseBadge(onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = Spacing.sm, end = Spacing.sm)
            .size(Spacing.control)
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(Spacing.dialogCloseBadge)
                .clip(CircleShape)
                // 深色用 scrim，浅色用中性表面：两种明暗都压得住卡片
                .background(
                    if (isSystemInDarkTheme()) {
                        colors.scrim.copy(alpha = CLOSE_SCRIM_ALPHA)
                    } else {
                        colors.surfaceVariant.copy(alpha = CLOSE_SCRIM_ALPHA)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = PhosphorIcons.Regular.X,
                contentDescription = "关闭",
                tint = colors.onSurface,
                modifier = Modifier.size(IconSize.sm),
            )
        }
    }
}

/** 入场起始缩放：0.96 是"浮上来"，0.9 以下就变成"弹出来"了。 */
private const val DIALOG_SCALE_FROM = 0.96f

/** 关闭按钮底色：压在图或渐变上都够清楚，又不至于变成一块黑饼。 */
private const val CLOSE_SCRIM_ALPHA = 0.34f

/** 插画扎进卡片的重叠深度：56dp 让插画看起来从卡片里长出来，是"一体"的关键。 */
private val ARTWORK_OVERLAP = Spacing.xxl + Spacing.lg

/** 卡片顶部 padding：插画总高 - 重叠区 = 卡片顶部到插画顶的距离。 */
private val ARTWORK_CARD_TOP = Spacing.dialogArtworkHeight - ARTWORK_OVERLAP

/** 插画光晕透明度：alpha 0.22 在深色底上能看出来，又不至于染脏中性卡片。 */
private const val ARTWORK_GLOW_ALPHA = 0.22f

/** 光晕中心 Y 比例：接缝位置 = (120 - 56) / 120 ≈ 0.53。 */
private const val ARTWORK_GLOW_CENTER_Y = 0.53f

/** 光晕半径相对画布短边的比例：1.4 倍让光晕铺满插画区，边缘自然淡出。 */
private const val ARTWORK_GLOW_RADIUS_RATIO = 1.4f

/** 模糊后卡片底色透明度：88% 白底保证文字可读，又能透出一点背后内容。 */
private const val CARD_SURFACE_ALPHA = 0.88f

/** 系统背景模糊半径（像素）：20px 是"能看出模糊但认不出内容"的档位。 */
private const val BLUR_BEHIND_RADIUS_PX = 20
