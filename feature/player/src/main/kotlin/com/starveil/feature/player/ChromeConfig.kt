package com.starveil.feature.player

import androidx.compose.runtime.Immutable

/**
 * 控制层的外观与回调。
 *
 * 顶栏与底栏都要读它，收成一个对象后组件签名不随交互数量变长
 * （AGENTS.md 第 2 节的参数上限）。
 */
@Immutable
internal data class ChromeConfig(
    val visible: Boolean,
    val danmuEnabled: Boolean,
    /**
     * 真正的横屏全屏（`isFullscreen && 已横屏`）。
     *
     * 不用 `isFullscreen` 本身：转屏要 200~400ms，那一瞬按全屏算会让顶栏先吃
     * 状态栏 inset 往下掉一截，再随系统栏隐藏弹回去——就是"按钮先落下来再转屏"。
     */
    val immersive: Boolean,
    /**
     * 已生效的全屏态：进全屏等系统真的横过来、退全屏等真的转回竖屏之后才翻转。
     *
     * 底栏的全屏图标与倍速胶囊读它，不读 `isFullscreen`：否则点下那一帧图标先变成
     * "退出全屏"、画面还要几百毫秒才铺满，同时多出来的倍速胶囊会把进度条挤窄。
     */
    val fullscreen: Boolean,
    /** 用户碰了控制层：重置自动隐藏倒计时。 */
    val onInteract: () -> Unit,
    val onToggleDanmu: () -> Unit,
    val onOpenSheet: (PlayerSheetKind) -> Unit,
    /** 点倍速胶囊：切下一档并显示在按钮上，不再弹选择层。 */
    val onCycleSpeed: () -> Unit = {},
)
