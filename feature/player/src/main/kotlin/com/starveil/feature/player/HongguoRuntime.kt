package com.starveil.feature.player

import com.starveil.core.hongguo.HongguoConfig

/**
 * 播放期共享的红果配置。
 *
 * 配置来自片源的 player_info，由 PlayerViewModel 在开播前写入；
 * DataSource 层读它来决定这条地址要不要解密。默认是「关 + 空规则」，
 * 所以在后台没启用之前，任何片源都不会被碰。
 */
internal object HongguoRuntime {
    @Volatile
    var config: HongguoConfig = HongguoConfig()
}
