package com.starveil.core.designsystem

import androidx.compose.ui.graphics.toArgb
import com.starveil.core.designsystem.token.SkinPalettes

/**
 * 冷启动窗口底色：按皮肤号与明暗取该皮肤的 `background`。
 *
 * 启动窗口要在 Compose 第一帧之前绘制，那时候拿不到 `MaterialTheme`，只能靠皮肤号
 * 反查色板。传空串或未知值按默认皮肤处理，和 `SkinPalettes.of` 的口径一致。
 *
 * [darkTheme] 传"已经算好的最终值"：用户强制浅/深时以他为准，否则用系统 `uiMode`。
 */
fun starveilBackgroundArgb(skinCode: String, darkTheme: Boolean): Int =
    SkinPalettes.of(skinCode).pick(darkTheme).colorScheme.background.toArgb()
