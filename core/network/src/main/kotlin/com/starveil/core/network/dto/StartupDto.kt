package com.starveil.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * 启动接口 `v1.main/startup` 的响应体，提取自
 * （顶层字段）与 `:315-376`（Ads 广告位）。
 *
 * 搬运原则：
 * - 后端 bean 全部字段无注解、靠 Gson 按字段名映射；这里逐个显式 [SerialName]，
 * 后端改字段名时会变成默认值而不是静默错位。
 * - 全部字段有默认值：旧后台经常缺字段，缺了必须能反序列化，由业务层降级。
 * - 只搬首期要用的字段。后端 bean 有 28 个顶层字段 + 44 个广告位，
 * 其余字段等对应功能开工时再补，避免一次搬进一堆没人读的字段。
 */
@Serializable
data class StartupDto(
    @SerialName("app_theme_ui") val themeUi: String? = null,
    @SerialName("app_banner_style") val bannerStyle: String? = null,
    @SerialName("app_ui_tab_fun") val tabFunc: String? = null,
    @SerialName("app_cc_fd_jc") val copyrightFlag: String? = null,
    @SerialName("ui_home_data") val homeData: String? = null,
    @SerialName("ui_home_topbg") val homeTopBg: String? = null,
    @SerialName("app_ui_home_small_icons") val homeSmallIcons: String? = null,
    @SerialName("app_home_ads") val homeAds: String? = null,
    @SerialName("vip_close_ad") val vipCloseAd: String? = null,
    @SerialName("app_play_recommend") val playRecommend: String? = null,
    @SerialName("app_detail_autoplay") val detailAutoplay: String? = null,
    @SerialName("app_user_adurl") val userAdUrl: String? = null,
    @SerialName("vip_purchase") val vipPurchase: String? = null,
    @SerialName("online_recharge") val onlineRecharge: String? = null,
    /** 会员体系总开关；'0' 关闭。缺字段按开启处理。 */
    @SerialName("member_enabled") val memberEnabled: String? = null,
    /**
     * 每日推荐开关；"1" 开启。缺字段按关闭处理（老后端没有这条配置）。
     *
     * 用 [JsonPrimitive] 而不是 String：后台可能下发 `1` 也可能下发 `"1"`，
     * 声明成 String 时数字会让整个启动配置解析失败（与 `ParseResultDto.code` 同样处理）。
     */
    @SerialName("push_enabled") val pushEnabled: JsonPrimitive? = null,
    /** 强制登录开关；"1" 开启。与 `push_enabled` 同样用 [JsonPrimitive] 容忍数字或字符串。 */
    @SerialName("force_login") val forceLogin: JsonPrimitive? = null,
    /** 允许的登录方式：device / password。缺字段按默认两种处理。 */
    @SerialName("login_modes") val loginModes: List<String> = emptyList(),
    /** 允许的注册方式：phone / account。 */
    @SerialName("register_modes") val registerModes: List<String> = emptyList(),
    @SerialName("login_default") val loginDefault: String? = null,
    /** 设备账号是否允许凭设备指纹找回；"0" 关闭。 */
    @SerialName("device_recovery") val deviceRecovery: JsonPrimitive? = null,
    /** 协议页地址：客户端点《用户协议》/《隐私政策》直接打开，空串表示后台没配。 */
    @SerialName("agreement_url") val agreementUrl: String? = null,
    @SerialName("privacy_url") val privacyUrl: String? = null,
    @SerialName("search_hot") val searchHot: List<String> = emptyList(),
    @SerialName("home_modules") val homeModules: List<String> = emptyList(),
    /**
     * 短剧分类 id（`mac_type.type_id`），后台「基础 → 短剧分类 id」维护。
     *
     * 用 [JsonPrimitive] 而不是 Int：后台可能下发数字也可能下发字符串，
     * 声明成 Int 时字符串会让整个启动配置解析失败（与 `push_enabled` 同样处理）。
     * 缺失或 0 表示未配置，由客户端回落内置常量。
     */
    @SerialName("shortplay_type_id") val shortplayTypeId: JsonPrimitive? = null,
    /**
     * 搜索专用的**父分类 id**（后台「短剧搜索分类 id」）。
     *
     * 服务端按父分类查会自动带上全部子分类，搜索一次请求就够；0 = 没配，
     * 客户端退回按下面的 [shortplayTypes] 逐个查。同样是 [JsonPrimitive]：数字或字符串都要能读。
     */
    @SerialName("shortplay_search_type_id") val shortplaySearchTypeId: JsonPrimitive? = null,
    /**
     * 短剧分类列表（可多选）：后台「基础 → 短剧分类 id」填 `21,22,23` 时下发数组。
     *
     * 老后端没有这个字段，客户端回落到上面的单个 [shortplayTypeId]。
     */
    @SerialName("shortplay_types") val shortplayTypes: List<ShortPlayTypeDto> = emptyList(),
    /**
     * 短剧页**顶部横滑分类条**（后台「短剧顶部 tab 分类 id」，逗号串）。
     *
     * 与 [shortplayTypes] 不是同一组：顶部只放 3-6 个，抽屉里才是全量。
     * 空数组 = 后台没配，由页面退回用 [shortplayTypes]；这里不拿它硬顶。
     */
    @SerialName("shortplay_tabs") val shortplayTabs: List<ShortPlayTypeDto> = emptyList(),
    @SerialName("bottom_tabs") val bottomTabs: List<BottomTabDto> = emptyList(),
    /** 底栏是否显示图标；老后端不返回时按「显示」处理。 */
    @SerialName("bottom_tab_icons") val bottomTabIcons: JsonPrimitive? = null,
    @SerialName("payments") val payments: List<PaymentDto> = emptyList(),
    @SerialName("ads") val ads: StartupAdsDto? = null,
    /** 详情页三个区块开关；老后端不返回时按全显示处理。 */
    @SerialName("detail_sections") val detailSections: DetailSectionsDto? = null,
)

/** 详情页开关（`detail_recommend` / `detail_score` / `detail_comment`，1 显示 / 0 隐藏）。 */
@Serializable
data class DetailSectionsDto(
    @SerialName("recommend") val recommend: Int = 1,
    @SerialName("score") val score: Int = 1,
    @SerialName("comment") val comment: Int = 1,
)

/** 支付方式：`payment` 是渠道码，回传给下单接口。 */
@Serializable
data class PaymentDto(
    @SerialName("payment") val code: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("status") val status: Int = 0,
)

/**
 * 广告位开关。的通用判定是 `status == 1 && description 非空`
 * 缺一即视为关闭。
 */
@Serializable
data class StartupAdsDto(
    @SerialName("application_id") val applicationId: AdSlotDto? = null,
    @SerialName("sdk_startup") val splash: AdSlotDto? = null,
    @SerialName("startup_adv") val startupHtml: AdSlotDto? = null,
    @SerialName("sdk_banner") val banner: AdSlotDto? = null,
    @SerialName("sdk_informationflow") val feed: AdSlotDto? = null,
    @SerialName("sdk_excitation") val excitation: AdSlotDto? = null,
    @SerialName("sdk_nreward") val newReward: AdSlotDto? = null,
    @SerialName("player_pause") val playerPause: AdSlotDto? = null,
    @SerialName("define_account") val defineAccount: AdSlotDto? = null,
    @SerialName("xunfei_appid") val xunfeiAppId: AdSlotDto? = null,
    @SerialName("home_logo") val homeLogo: AdSlotDto? = null,
    @SerialName("home_backg") val homeBackGround: AdSlotDto? = null,
    /** 选中的聚合平台：`tradplus` / 其它按直投处理（TopOn 已摘除，见 AdPlatformName）。 */
    @SerialName("ad_platform") val platform: String? = null,
    @SerialName("tradplus") val tradPlus: TradPlusDto? = null,
    /** 广告隐私弹窗正文；后台为空时客户端用本地默认文案（`R.string.ad_consent_body`）。 */
    @SerialName("ad_consent_text") val adConsentText: String? = null,
    /**
     * 直投激励视频地址。为空即"没有视频素材"，激励位退回直投图片。
     * 视频链路的地址以 `v1.ad/session` 返回的 `video_url` 为准，这里只是"配没配"的开关显示。
     */
    @SerialName("ad_reward_video") val rewardVideoUrl: String? = null,
)

/**
 * TradPlus 凭证与广告位。没有 appKey：`TradPlusSdk.initSdk(context, appId)` 只吃一个参数。
 * adUnitId 的类型在建广告位时就绑定了，客户端按 Slot 选类去请求。
 */
@Serializable
data class TradPlusDto(
    @SerialName("app_id") val appId: String? = null,
    @SerialName("splash") val splash: String? = null,
    @SerialName("reward") val reward: String? = null,
    @SerialName("feed") val feed: String? = null,
    @SerialName("pause") val pause: String? = null,
    @SerialName("banner") val banner: String? = null,
)

/**
 * 广告位。`description` 是位 ID（接 SDK 后由平台分配）。
 *
 * 直投资材的 `image` / `url` / `title` 是本版新增字段：老后端不返回时全为空串，
 * 该位就只当"已配置但画不出来"处理，页面隐藏而不占位。
 */
@Serializable
data class AdSlotDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("typename") val typeName: String = "",
    @SerialName("status") val status: Int = 0,
    @SerialName("sort") val sort: Int = 0,
    @SerialName("tag") val tag: String = "",
    @SerialName("description") val description: String? = null,
    @SerialName("image") val image: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("title") val title: String? = null,
    /** 信息流广告第二行文案；其它位为空。 */
    @SerialName("subtitle") val subtitle: String? = null,
    /** 信息流插播间隔（每 N 张剧卡一条），其它位恒 0。 */
    @SerialName("interval") val interval: Int = 0,
    /** 激励视频看满多少秒算完成，其它位恒 0。 */
    @SerialName("seconds") val seconds: Int = 0,
) {
    /**
     * 这个广告位开着吗。
     *
     * 与服务端 `Startup::slotConfigured()` 保持同一口径：**位 ID 或直投图片**
     * 任一非空都算配置。只认位 ID 的话，后台只上传开屏图、位 ID 留空的直投
     * 会被判成未启用——服务端明明回了 `status:1` 和图片地址，客户端却不展示。
     */
    val isEnabled: Boolean
        get() = status == 1 &&
            (!description.isNullOrBlank() || !image.isNullOrBlank())
}

/** 底部菜单项（`bottom_tabs`）。缺字段时用默认值，避免后端直接崩。 */
@Serializable
data class BottomTabDto(
    @SerialName("id") val id: String = "",
    @SerialName("label") val label: String = "",
    @SerialName("icon") val icon: String = "",
    @SerialName("visible") val visible: Boolean = true,
    @SerialName("order") val order: Int = 0,
    @SerialName("mode") val mode: String = "topic",
)

/** 短剧分类（`shortplay_types`）：一个 macCMS 分类 + 后台填的名字。 */
@Serializable
data class ShortPlayTypeDto(
    @SerialName("type_id") val typeId: Int = 0,
    @SerialName("type_name") val typeName: String = "",
)
