package com.starveil.core.model

import com.starveil.core.common.ad.AdPlacements
import com.starveil.core.common.DetailSections
/**
 * 启动配置的领域形态。
 *
 * 来自启动接口 `v1.main/startup`，但只保留"决定 UI 结构"的部分：
 * 皮肤、第二 Tab、首页卡片列数、广告位开关、支付方式。
 * 纯展示文案、分享图、友盟 key 之类不进领域模型。
 */
data class StartupConfig(
    val skin: SkinResolution,
    /** 服务端下发的原始皮肤号（`app_theme_ui`）。主题层按它选色板，不认 SkinId。 */
    val skinCode: String = "",
    val bannerStyleCode: Int?,
    val secondTab: SecondTab,
    val homeCardColumns: Int,
    val appUiHomeSmallIcons: String?,
    val topBarUsesBanner: Boolean,
    val playRecommendEnabled: Boolean,
    /** 进详情页是否直接起播。缺字段（老后端）按开启处理，与加开关之前的行为一致。 */
    val detailAutoplay: Boolean = true,
    val ads: AdPlacements,
    val payments: List<PaymentMethod>,
    val searchHot: List<String>,
    /** 首页模块顺序；空表示用默认顺序。 */
    val homeModules: List<HomeModule> = HomeModule.entries.toList(),
    /** 底部菜单；空表示用默认五栏。 */
    val bottomTabs: List<BottomTab> = emptyList(),
    /** 底栏是否显示图标；关闭后只画文字，字号按 tab 数量自动放大。 */
    val bottomTabIcons: Boolean = true,
    /** 详情页三个区块的开关；缺字段按全显示处理。 */
    val detailSections: DetailSections = DetailSections.ALL,
    /**
     * 会员体系总开关（`member_enabled`）。
     *
     * 关闭只代表"停止售卖"：会员页、底栏入口、开通按钮都要隐藏；
     * 历史会员的权益由服务端按到期时间继续放行，客户端不在这里判断。
     * 缺字段按开启处理，避免老后端把会员功能关掉。
     */
    val memberEnabled: Boolean = true,
    /**
     * 每日推荐开关（`push_enabled`，"1" 开启）。
     *
     * 关闭时首页连 `v1.main/push` 都不请求；缺字段按关闭处理 ——
     * 老后端没有这条配置，也就不该冒出推荐角标。
     */
    val pushEnabled: Boolean = false,
    /**
     * 强制登录开关（`force_login`，"1" 开启）。
     *
     * 开启时客户端冷启动直接锁全屏登录，未登录进不了 App；关闭时登录是弹层，可关。
     * 缺字段按关闭处理：老后端没有这条配置，不能凭空把用户挡在门外。
     */
    val forceLogin: Boolean = false,
    /**
     * 允许的登录方式（`login_modes`）。客户端只渲染勾选的方式，不勾即关闭。
     *
     * 缺字段时按"设备一键 + 账号密码"处理：老后端没有这套配置，
     * 而这两种方式在新客户端上都不依赖服务端额外配合。
     */
    val loginMethods: Set<LoginMethod> = DEFAULT_LOGIN_METHODS,
    /** 允许的注册方式（`register_modes`）。缺字段只留账号注册，不凭空开短信。 */
    val registerMethods: Set<RegisterMethod> = DEFAULT_REGISTER_METHODS,
    /** 登录页默认展示哪种方式（`login_default`）：device / password。 */
    val loginDefaultMethod: LoginMethod = LoginMethod.DEVICE,
    /**
     * 设备账号是否允许凭设备指纹找回（`device_recovery`）。
     *
     * 关闭后重装/换机只能靠绑定手机号找回；开启时服务端还有 24 小时恢复冷却。
     */
    val deviceRecoveryEnabled: Boolean = true,
    /** 用户协议地址（后台 `agreement_url`）；空串表示没配，客户端只提示"页面建设中"。 */
    val agreementUrl: String = "",
    /** 隐私政策地址（后台 `privacy_url`）。 */
    val privacyUrl: String = "",
    /**
     * 短剧分类 id（`shortplay_type_id`）。
     *
     * 短剧就是 macCMS `mac_vod` 里某个 `type_id` 的影片，而这个 id 随站点自增，
     * 各站不同（本项目联调站当年是 30）。所以它必须由后台下发，客户端只留兜底，
     * 0 表示后台没配：剧场页回落到仓库里的内置兜底常量（见 DefaultShortPlayRepository）。
     */
    val shortplayTypeId: Int = 0,
    /**
     * 短剧分类列表：后台「短剧分类 id」可以填多个（`21,22,23`），剧场页按顺序出 chips。
     *
     * 老后端没有 `shortplay_types`，此时为空，由上面的单个 id 兜成一项。
     */
    val shortplayTypes: List<ShortPlayType> = emptyList(),
    /**
     * 搜索专用的父分类 id（`shortplay_search_type_id`），**也用作进短剧页的初始流**。
     *
     * 搜索只请求它一次：服务端查父分类会带上全部子分类，比逐个查 [shortplayTypes] 快。
     * 进页同理，先按父分类拉一屏全部子分类的内容，比只拉第一个子分类更接近"全部短剧"。
     * 0 表示后台没配，搜索退回按 [shortplayTypes] 逐个查，进页用第一个子分类。
     */
    val shortplaySearchTypeId: Int = 0,
    /**
     * 短剧页**顶部横滑分类条**（`shortplay_tabs`）。
     *
     * 与 [shortplayTypes] 是两组：顶部只放后台单独配的 3-6 个，抽屉里才是全量。
     * 空表示后台没配，顶部条退回用 [shortplayTypes]——所以这里不继承它的兜底。
     */
    val shortplayTabs: List<ShortPlayType> = emptyList(),
) {

    /**
     * 后台配置的短剧分类 id 集合：单个 `shortplay_type_id` 与多选 `shortplay_types` 合并。
     *
     * 判断"这部片是不是短剧"只能靠它，不能看 `typeName` 里有没有"短剧"两个字：
     * 短剧内容大多挂在子分类下（古装仙侠、现代都市…），名字里压根没有"短剧"，
     * 按名字判会把它们全送进详情页的 16:9 播放器。
     */
    val shortplayTypeIds: Set<Int>
        get() = buildSet {
            if (shortplayTypeId > 0) add(shortplayTypeId)
            shortplayTypes.forEach { add(it.id) }
        }
    companion object {
        /** 旧默认：`ui_home_data` 非 "2" 时强制 3 列。 */
        const val DEFAULT_HOME_COLUMNS = 3

        /** `ui_home_data == "2"` 时 2 列。 */
        const val TWO_COLUMN_HOME = 2

        /** 缺配置时的登录方式：两种都不依赖服务端额外开关。 */
        val DEFAULT_LOGIN_METHODS: Set<LoginMethod> = setOf(LoginMethod.DEVICE, LoginMethod.PASSWORD)

        /** 缺配置时的注册方式：账号注册，不凭空开短信。 */
        val DEFAULT_REGISTER_METHODS: Set<RegisterMethod> = setOf(RegisterMethod.ACCOUNT)
    }
}

/**
 * 登录方式（服务端 `login_modes` 的取值）。
 *
 * 客户端只渲染集合里有的方式；集合为空是不允许出现的状态（后台保存时会拦住全不选）。
 */
enum class LoginMethod(val code: String) {
    /** 设备一键登录（RDA）：随机凭据 + 可恢复锚，见 [com.starveil.core.data.DeviceCredentialStore]。 */
    DEVICE("device"),

    /** 账号 / 手机号 + 密码。 */
    PASSWORD("password"),
    ;

    companion object {
        fun of(code: String): LoginMethod? = entries.firstOrNull { it.code == code }
    }
}

/** 注册方式（服务端 `register_modes` 的取值）。 */
enum class RegisterMethod(val code: String) {
    /** 手机号 + 短信验证码。需要后台配好短信网关。 */
    PHONE("phone"),

    /** 账号 + 密码，不走验证码。 */
    ACCOUNT("account"),
    ;

    companion object {
        fun of(code: String): RegisterMethod? = entries.firstOrNull { it.code == code }
    }
}

/** 底部第二个功能位（`app_ui_tab_fun`）："1" 是专题，其余是直播。 */
enum class SecondTab { LIVE, TOPIC }

/** 五个广告位的配置，定义见 [AdPlacements]。缺字段时整体为 [AdPlacements.NONE]。 */

/** 支付方式。`code` 原样回传给下单接口，不做本地枚举，避免后端加渠道就要发版。 */
data class PaymentMethod(
    val code: String,
    val name: String,
    val status: Int,
)
