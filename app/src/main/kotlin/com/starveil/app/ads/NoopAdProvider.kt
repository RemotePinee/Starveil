package com.starveil.app.ads

import com.starveil.core.common.ad.AdGateway
import com.starveil.core.common.ad.AdOutcome
import com.starveil.core.common.ad.AdSlot

/**
 * 默认广告实现：没有接任何 SDK 时使用。
 * `isReady` 恒 false，UI 因此不会出现"看广告解锁"按钮，也不会预加载任何东西。
 */
class NoopAdProvider : AdGateway {

    override fun isReady(slot: AdSlot): Boolean = false

    override fun preload(slot: AdSlot) = Unit

    override suspend fun show(slot: AdSlot): AdOutcome = AdOutcome.NoFill
}
