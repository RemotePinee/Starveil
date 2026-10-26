package com.starveil.core.network.mapper

import com.starveil.core.model.BottomTab
import com.starveil.core.model.HomeBanner
import com.starveil.core.model.HomeBlock
import com.starveil.core.model.HomeLink
import com.starveil.core.model.HomeModule
import com.starveil.core.model.HomeSpotlight
import com.starveil.core.network.dto.HomeBannerDto
import com.starveil.core.network.dto.HomeBlockDto
import com.starveil.core.network.dto.HomeLinkDto
import com.starveil.core.network.dto.HomeSpotlightDto

fun HomeBannerDto.toDomain(): HomeBanner = HomeBanner(
    id = id,
    title = title,
    subtitle = subtitle,
    pic = pic,
    vodId = vodId,
    url = url,
    style = style,
)

fun HomeBlockDto.toDomain(): HomeBlock = HomeBlock(
    id = id,
    title = title,
    subtitle = subtitle,
    layout = layout,
    vods = vods.map { it.toDomain() },
)

fun HomeLinkDto.toDomain(): HomeLink = HomeLink(
    title = title,
    subtitle = subtitle,
    bg = bg,
    targetType = targetType,
    targetId = targetId,
    url = url,
)

/**
 * 标题原样透传：三个卡片位的回退文案不同（编辑精选 / 热度榜 / 猜你喜欢），
 * 由 UI 按位置决定；这里替后台补默认值只会让热度榜顶着「编辑精选」的标题。
 */
fun HomeSpotlightDto.toDomain(): HomeSpotlight = HomeSpotlight(
    title = title,
    subtitle = subtitle,
    items = vods.map { it.toDomain() },
)

/** 字符串 → 模块枚举；未知值丢弃，避免后台写错导致整页空白。 */
fun homeModulesOf(values: List<String>): List<HomeModule> =
    values.mapNotNull { HomeModule.fromWire(it) }.ifEmpty { HomeModule.entries.toList() }

/**
 * 底部菜单：按 order 排序、去重，至少保留首页与我的。
 *
 * 不再按固定槽位白名单过滤：后台可以新增任意数量的按钮（"显示"上限 5 个由服务端卡），
 * 再白名单一遍会把新增的按钮静默丢掉。
 */
fun bottomTabsOf(values: List<BottomTab>): List<BottomTab> {
    val cleaned = values.filter { it.id.isNotBlank() }.distinctBy { it.id }.sortedBy { it.order }
    val missing = listOf("home", "mine") - cleaned.map { it.id }.toSet()
    return cleaned + missing.map { id ->
        BottomTab(id = id, label = if (id == "home") "首页" else "我的", icon = id, visible = true, order = 99)
    }
}
