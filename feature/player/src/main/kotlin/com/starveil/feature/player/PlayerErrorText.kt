package com.starveil.feature.player

import com.starveil.core.common.playerErrorMessage as corePlayerErrorMessage

/**
 * 点播的错误文案。映射实现搬到了 `:core:common`（直播也要用同一套说法），
 * 保留同名入口，调用点与既有测试保持不变。
 */
internal fun playerErrorMessage(code: String, triedOtherSources: Boolean = true): String =
    corePlayerErrorMessage(code, triedOtherSources)
