package com.starveil.core.network.mapper

import com.starveil.core.common.ad.AdPlacement
import com.starveil.core.common.DetailSections
import com.starveil.core.common.ad.AdPlacements
import com.starveil.core.common.ad.AdPlatformConfig
import com.starveil.core.common.ad.AdPlatformName
import com.starveil.core.common.ad.TradPlusConfig
import com.starveil.core.model.BottomTab
import com.starveil.core.model.HomeModule
import com.starveil.core.model.PaymentMethod
import com.starveil.core.model.SecondTab
import com.starveil.core.model.ShortPlayType
import com.starveil.core.model.Skins
import com.starveil.core.model.LoginMethod
import com.starveil.core.model.RegisterMethod
import com.starveil.core.model.StartupConfig
import com.starveil.core.network.dto.AdSlotDto
import com.starveil.core.network.dto.ShortPlayCategoryDto
import com.starveil.core.network.dto.ShortPlayTypeDto
import com.starveil.core.network.dto.StartupAdsDto
import com.starveil.core.network.dto.StartupDto
import kotlinx.serialization.json.JsonPrimitive

/**
 * 启动配置 DTO → 领域模型。
 *
 * 三处判定直接照搬后端约定：
 * - 卡片列数：`ui_home_data == "2"` → 2 列，否则 3 列
 * - 第二 Tab：只作 `bottom_tabs` 缺失时的兜底，后台已不再提供该字段
 * - 广告位开关：`status == 1 && description 非空`
 */
fun StartupDto.toDomain(localThemeOverride: String? = null): StartupConfig = StartupConfig(
    skin = Skins.resolve(serverValue = themeUi, localOverride = localThemeOverride),
    skinCode = themeUi.orEmpty(),
    bannerStyleCode = bannerStyle?.trim()?.toIntOrNull(),
    // 底栏由 `bottom_tabs` 决定；这个值只在后台没配底部菜单时兜底，
    // 后台字段已下掉，缺省取专题（与历史版本 `app_ui_tab_fun == "1"` 的默认一致）
    secondTab = if (tabFunc == "1" || tabFunc == null) SecondTab.TOPIC else SecondTab.LIVE,
    homeCardColumns = if (homeData == "2") StartupConfig.TWO_COLUMN_HOME else StartupConfig.DEFAULT_HOME_COLUMNS,
    appUiHomeSmallIcons = homeSmallIcons,
    topBarUsesBanner = homeTopBg == "1",
    playRecommendEnabled = playRecommend == "1",
    // 缺字段（null）按开启：老后端没有这个键，行为与加开关之前一致
    detailAutoplay = detailAutoplay != "0",
    ads = ads.toDomain(),
    // 详情页三块：缺字段（null）按显示处理，与加开关之前的行为一致
    detailSections = detailSectionsOf(),
    payments = payments.map { PaymentMethod(code = it.code, name = it.name, status = it.status) },
    searchHot = searchHot,
    homeModules = homeModulesOf(homeModules),
    bottomTabs = bottomTabsOf(bottomTabs.map {
        BottomTab(id = it.id, label = it.label, icon = it.icon, visible = it.visible, order = it.order, mode = it.mode)
    }),
    // 底栏图标总开关：只有显式 0 才算关，老后端缺字段时保持图标
    bottomTabIcons = bottomTabIcons?.content != "0",
    // 会员关闭只隐藏售卖入口；历史会员权益由服务端继续放行，客户端不推断
    memberEnabled = memberEnabled != "0",
    // 每日推荐：只有显式 1 才算开，老后端缺字段时首页不请求、不冒红点
    pushEnabled = pushEnabled?.content == "1",
    // 强制登录：只有显式 1 才算开，缺字段时保持游客可用的现有行为
    forceLogin = forceLogin?.content == "1",
    // 登录/注册方式由后台决定，客户端不自己猜：缺字段才回落到内置默认
    loginMethods = loginModes.mapNotNull { LoginMethod.of(it) }.toSet()
        .ifEmpty { StartupConfig.DEFAULT_LOGIN_METHODS },
    registerMethods = registerModes.mapNotNull { RegisterMethod.of(it) }.toSet()
        .ifEmpty { StartupConfig.DEFAULT_REGISTER_METHODS },
    loginDefaultMethod = LoginMethod.of(loginDefault.orEmpty()) ?: LoginMethod.DEVICE,
    // 缺字段按开启处理：老后端没有这条配置，不该顺手关掉找回
    deviceRecoveryEnabled = deviceRecovery?.content != "0",
    // 协议页地址：后台留空就保持空串，客户端据此提示"页面建设中"而不是打开空链接
    agreementUrl = agreementUrl.orEmpty(),
    privacyUrl = privacyUrl.orEmpty(),
    shortplayTypeId = shortplayTypeId?.content?.trim()?.toIntOrNull() ?: 0,
    // 搜索专用父分类：空串/非数字一律当 0（= 没配），客户端据此退回逐个查子分类
    shortplaySearchTypeId = shortplaySearchTypeId?.content?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
    // 多分类：新后端直接下数组；老后端没有这个字段时用单个 id 兜成一项，
    // 解析与空白规则集中在 [shortPlayTypesOf]
    shortplayTypes = shortPlayTypesOf(shortplayTypes, shortplayTypeId),
    // 顶部 tab：后台没配就是空列表，不拿 shortplayTypes 硬顶 —— 两者是不同的一组，
    // 顶部条的"退回全量"由页面自己决定（见 ShortPlayScreen）。
    shortplayTabs = shortplayTabs.map { ShortPlayType(id = it.typeId, name = it.typeName) }.filter { it.id > 0 },
    // 分类树：两级结构原样下发，客户端只做展示与联动；
    // 老后端 / 后台没配两级结构时用上面的单层列表兜成"一个父分类"，分类页照样能联动
    shortplayCategories = shortplayCategories.treeOrFlat(
        flat = shortPlayTypesOf(shortplayTypes, shortplayTypeId),
    ),
)

/**
 * 分类树节点的映射：id 非正的脏节点整枝丢掉，名字留空由页面给默认文案。
 *
 * 递归只到后端给的那一层——服务端按 `type_pid` 只下发直接子分类，不会有环。
 */
private fun ShortPlayCategoryDto.toDomain(): ShortPlayType? {
    if (id <= 0) return null
    return ShortPlayType(
        id = id,
        name = name,
        children = children.mapNotNull { it.toDomain() },
    )
}

/**
 * 分类树兜底：后端没下发 `shortplay_categories`（老后端），或树是空的（后台还没建两级
 * 分类）时，把单层 [ShortPlayType] 列表包成**一个隐形父分类**（id 0）。
 *
 * 这样分类页永远只有一套渲染逻辑：父分类只有一个 → 顶部那一行自动隐藏，直接显示子分类条。
 * 两者都为空时返回空列表，页面显示「后台暂无分类」并按仓库默认分类出一屏内容。
 */
private fun List<ShortPlayCategoryDto>.treeOrFlat(flat: List<ShortPlayType>): List<ShortPlayType> {
    val tree = mapNotNull { it.toDomain() }
    if (tree.isNotEmpty() || flat.isEmpty()) return tree
    return listOf(ShortPlayType(id = 0, name = "", children = flat))
}

private fun StartupDto.detailSectionsOf(): DetailSections = DetailSections(
    recommend = detailSections?.recommend != 0,
    score = detailSections?.score != 0,
    comment = detailSections?.comment != 0,
)

/**
 * 短剧分类列表的兜底规则。
 *
 * 新后端直接下发数组；老后端没有这个字段时，用单个 [legacyTypeId] 兜成一项，
 * 名字留空由剧场页显示默认文案（chip 行为与单分类时期完全一致）。
 */
private fun shortPlayTypesOf(
    types: List<ShortPlayTypeDto>,
    legacyTypeId: JsonPrimitive?,
): List<ShortPlayType> = types
    .map { ShortPlayType(id = it.typeId, name = it.typeName) }
    .filter { it.id > 0 }
    .ifEmpty {
        (legacyTypeId?.content?.trim()?.toIntOrNull() ?: 0)
            .takeIf { it > 0 }
            ?.let { listOf(ShortPlayType(id = it, name = "")) }
            .orEmpty()
    }

/**
 * 五个位逐位映射。老后端缺位、缺素材都落成"关闭 + 空"，页面因此自然不渲染。
 *
 * `description` 是位 ID（接 SDK 后才有意义）；直投资材在 `image`/`url`/`title` 里。
 * 信息流间隔与激励倒计时由模型收敛，后端填什么都不至于把 UI 顶坏。
 */
private fun StartupAdsDto?.toDomain(): AdPlacements = AdPlacements(
    splash = slotOf(this?.splash),
    banner = slotOf(this?.banner),
    feed = slotOf(this?.feed),
    reward = slotOf(this?.excitation),
    pause = slotOf(this?.playerPause),
    platform = platformOf(this),
    sdkApplicationId = this?.applicationId?.description.orEmpty(),
    startupHtml = this?.startupHtml?.description.orEmpty(),
    consentText = this?.adConsentText.orEmpty(),
    // 直投激励视频：空串表示"没配视频"，激励位退回图片倒计时（见 app 层的 RewardImageOverlay）
    rewardVideoUrl = this?.rewardVideoUrl.orEmpty(),
)

/** 平台选择。现在只有 TradPlus，字段形状照它的 initSdk(context, appId) 来。 */
private fun platformOf(dto: StartupAdsDto?): AdPlatformConfig = AdPlatformConfig(
    name = AdPlatformName.parse(dto?.platform),
    tradPlus = TradPlusConfig(
        appId = dto?.tradPlus?.appId.orEmpty(),
        splashSlot = dto?.tradPlus?.splash.orEmpty(),
        rewardSlot = dto?.tradPlus?.reward.orEmpty(),
        feedSlot = dto?.tradPlus?.feed.orEmpty(),
        pauseSlot = dto?.tradPlus?.pause.orEmpty(),
        bannerSlot = dto?.tradPlus?.banner.orEmpty(),
    ),
)

private fun slotOf(dto: AdSlotDto?): AdPlacement = AdPlacement(
    slotId = dto?.description.orEmpty(),
    enabled = dto?.isEnabled == true,
    imageUrl = dto?.image.orEmpty(),
    clickUrl = dto?.url.orEmpty(),
    title = dto?.title.orEmpty(),
    subtitle = dto?.subtitle.orEmpty(),
    interval = dto?.interval ?: 0,
    seconds = dto?.seconds ?: 0,
)
