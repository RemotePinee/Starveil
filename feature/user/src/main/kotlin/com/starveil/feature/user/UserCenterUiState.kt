package com.starveil.feature.user

import com.starveil.core.model.MembershipStatus
import com.starveil.core.model.Session
import com.starveil.core.model.UserCenterVariant
import com.starveil.core.model.UserProfile
import com.starveil.core.model.toMembershipStatus

/**
 * 用户中心状态。不可变，通过 StateFlow 暴露（AGENTS.md 第 5 节）。
 *
 * 区块列表不单独存字段：它由 [variant] 决定，从 [UserCenterLayouts] 派生，避免两份数据各自漂移。
 * 需要注意：`UserCenterScreen` 现在是固定的业务卡组（用户卡 / 会员卡 / 数据行 / 功能列表），
 * 不再按 [sections] 渲染；区块配置保留为皮肤契约（也是 `UserCenterViewModelTest` 的断言对象）。
 *
 * 资料值来自 `UserRepository.loadProfile()`，取法与 [ProfileUiState] 保持一致：
 * 影豆 = `points`、积分 = `userPoints`、金币 = `gold`（字符串，**不要 toDouble**，
 * 旧 ）。拉取失败时 [profile] 置 null，数值降级为占位符。
 *
 * @param variant 当前皮肤变体，来自 `Skins.userCenter(skin)`
 * @param session 会话；null 表示未登录（旧 只认 `user_id` 非空）
 * @param profile `v1.user/detail` 的结果；null 表示未登录、尚未返回或拉取失败
 * @param isLoading 首次 restore() 尚未返回
 * @param errorMessage 资料同步失败的文案，页面挂一条可重试提示；成功刷新后清空
 */
data class UserCenterUiState(
    val variant: UserCenterVariant = UserCenterVariant.A,
    val session: Session? = null,
    val profile: UserProfile? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    /**
     * 会员体系是否开放。
     *
     * 关闭只影响"售卖"：会员卡仍然显示（历史会员要看到到期时间），
     * 但换成弱化样式、不渲染开通/续费按钮；权益本身由服务端继续放行。
     */
    val memberEnabled: Boolean = true,
    /** 接口域名：头像存的是站内相对路径，拼上它才能请求。 */
    val apiHost: String = "",
) {

    /** 当前变体的结构配置。 */
    val layout: UserCenterLayout
        get() = UserCenterLayouts.of(variant)

    /** 当前变体要渲染的区块，按旧布局的上下顺序排列。 */
    val sections: List<UserCenterSection>
        get() = layout.sections

    val isLoggedIn: Boolean
        get() = session?.isLoggedIn == true

    /**
     * 昵称：接口值优先，其次会话 cookie，最后账号名；"未登录"是旧 `tv_user_phone` 的占位。
     *
     * 接口和 cookie 里的昵称/组名都是 urlencode 原值（苹果CMS 就是这么存的），
     * 两处都要解码，否则页面上会直接显示 `%E6%B8%B8%E5%AE%A2` 这种串。
     */
    val displayName: String
        get() = profile?.nickName?.takeIf { it.isNotBlank() }?.decodeCookieText()
            ?: session?.nickName?.takeIf { it.isNotBlank() }?.decodeCookieText()
            ?: session?.userName?.takeIf { it.isNotBlank() }
            ?: "未登录"

    /**
     * 头像地址。后端存的是站内相对路径（`/upload/avatar/x.jpg`），拼上接口域名才能请求；
     * 域名来自 `strings.xml`，代码里不写死。拼不出来时返回 null，UI 降级成首字母圆底。
     */
    val avatarUrl: String?
        get() = profile?.portrait.toRemoteUrl(apiHost) ?: session?.portrait.toRemoteUrl(apiHost)

    val rankLabel: String?
        get() = profile?.groupName?.takeIf { it.isNotBlank() }?.decodeCookieText()
            ?: session?.groupName?.takeIf { it.isNotBlank() }?.decodeCookieText()
    /** 等级号，来自 `UserProfile.level`（后端 bean 是字符串，可能是空串）。 */
    val levelLabel: String?
        get() = profile?.level?.takeIf { it.isNotBlank() }

    /** 昵称下面那一行：会员组 · 等级；都没有时回落到"普通用户"或登录引导。 */
    val subtitle: String
        get() = listOfNotNull(rankLabel, levelLabel?.let { "Lv.$it" }, MOTTO)
            .joinToString(separator = " · ")
            .ifEmpty { if (isLoggedIn) DEFAULT_RANK else GUEST_HINT }

    /**
     * 公告文案。
     *
     * 后端 `v1.user/detail` 没有公告字段（`core/model/UserProfile.kt:15-30` 只有 15 个字段），
     * 旧布局 `noticeList` 的公告行也是写死的，所以这里恒为 null、UI 不渲染公告行；
     * 契约补齐后把这里换成 `profile?.notice` 即可，渲染端已经支持非空文案。
     */
    val noticeText: String?
        get() = null

    /** 区块渲染需要的数据值，与结构配置（[sections]）分开，配置里不再放数值。 */
    val values: UserCenterValues
        get() = UserCenterValues(
            stats = profile?.statValues().orEmpty(),
            noticeLines = listOfNotNull(noticeText, profile?.watchRecordLine()),
        )

    /**
     * 会员状态。判定与文案都来自 [MembershipStatus]（`core/model/MembershipStatus.kt`），
     * 与会员页 Hero 共用同一份逻辑：同一个账号在两张会员卡上不会出现两种说法。
     *
     * 首次 restore() 还没返回（[isLoading] 且 [session] 为空）时登录态未知，
     * 给 [MembershipStatus.Pending]，卡片先显示占位而不是"登录后查看会员状态"。
     */
    val membershipStatus: MembershipStatus
        get() = profile.toMembershipStatus(
            if (isLoading && session == null) null else isLoggedIn,
        )

    /** 是否会员：服务端 `is_vip` 结论，两个页面共用。 */
    val isMember: Boolean
        get() = membershipStatus.isMember

    /** 会员卡副标题（未登录 / 未开通 / 到期日），文案来自共享的 [MembershipStatus.label]。 */
    val memberExpiryLabel: String
        get() = membershipStatus.label

    /**
     * 数据行（收藏 / 历史 / 积分 / 金币 / 卡密）的展示值。
     *
     * 只映射接口真有的字段：收藏没有总数接口、卡密没有余额字段，保持默认占位符，
     * 不用本地缓存条数冒充。
     */
    val metrics: UserCenterMetrics
        get() {
            // 登录态但资料还没到：留空，等数值直接出现；访客才显示占位横线
            val pending = profile == null && isLoggedIn
            fun value(get: (UserProfile) -> String?): String =
                if (pending) "" else profile?.let(get)?.takeIf { it.isNotBlank() } ?: PROFILE_PLACEHOLDER
            return UserCenterMetrics(
                points = value { it.points.toString() },
                gold = value { it.gold },
                watch = value { it.viewSeconds?.toWatchLabel() },
            )
        }
}

/**
 * 区块渲染用的资料值。
 *
 * @param stats 标签 → 数值；键与 `UserCenterLayoutVariants.kt` 里的统计标签一致
 * @param noticeLines 历史/推荐区的行文案（公告 + 观看记录），空表示接口没给数据
 */
data class UserCenterValues(
    val stats: Map<String, String> = emptyMap(),
    val noticeLines: List<String> = emptyList(),
) {

    /** 取某个统计项的值；接口没覆盖到的标签（如 F 的"推广"）给占位符。 */
    fun stat(label: String): String = stats[label] ?: PROFILE_PLACEHOLDER
}

/**
 * 数据行的展示值。默认全是占位符：未登录、资料未到、或后端没有该字段时都会落到默认值，
 * 页面因此不需要再判空。
 *
 * @param favoriteCount 收藏总数；`FavoriteRepository` 只有分页列表，没有计数接口
 * @param historyCount 观看记录条数，来自 `UserProfile.viewCount`
 * @param points 积分，来自 `UserProfile.userPoints`
 * @param gold 金币，`UserProfile.gold` 是字符串，原样展示（**不要 toDouble**）
 * @param cardKey 卡密余额；后端没有该字段
 */
data class UserCenterMetrics(
    /** 积分：后端 `points`（= `mac_user.user_points`，苹果CMS 原生积分列）。 */
    val points: String = PROFILE_PLACEHOLDER,
    /** 金币：插件表 `cuican_user_ext.gold`，字符串形态（如 `29.00`）。 */
    val gold: String = PROFILE_PLACEHOLDER,
    /** 累计观看时长：插件表 `cuican_user_ext.view_seconds`，格式化成"x.x 小时"。 */
    val watch: String = PROFILE_PLACEHOLDER,
)

/** 秒 → "x.x 小时" / "x 分钟"；0 或负值给占位符，不硬凑"0 分钟"。 */
private fun Long.toWatchLabel(): String {
    if (this <= 0L) return PROFILE_PLACEHOLDER
    val minutes = this / 60
    return if (minutes >= 60) String.format(java.util.Locale.ROOT, "%.1f 小时", this / 3600f)
    else "$minutes 分钟"
}

/** 三栏数值，不要合并：`points` 与 `userPoints` 是后端两个独立余额。 */
private fun UserProfile.statValues(): Map<String, String> = buildMap {
    put(LABEL_BEAN, points.toString())
    put(LABEL_POINTS, userPoints.toString())
    if (gold.isNotBlank()) put(LABEL_GOLD, gold)
}

/** 旧布局历史记录区是 RecyclerView，新工程只显示条数（`UserProfile.viewCount`）。 */
private fun UserProfile.watchRecordLine(): String? =
    viewCount?.takeIf { it.isNotBlank() }?.let { "$WATCH_RECORD $it" }

/** 绝对地址直接用；站内相对路径拼接口域名；其它（半截路径、空串）一律 null。 */
private fun String?.toRemoteUrl(host: String): String? {
    val raw = this?.takeIf { it.isNotBlank() } ?: return null
    return when {
        raw.startsWith(ABSOLUTE_URL_PREFIX) -> raw
        raw.startsWith("/") && host.isNotBlank() -> host.trimEnd('/') + raw
        else -> null
    }
}

private const val PROFILE_PLACEHOLDER = "——"
private const val ABSOLUTE_URL_PREFIX = "https://"
private const val LABEL_BEAN = "影豆"
private const val LABEL_POINTS = "积分"
private const val LABEL_GOLD = "金币"
private const val WATCH_RECORD = "观看记录"
private const val MOTTO = "电影是每秒二十四格的真理"
private const val DEFAULT_RANK = "普通用户"
private const val GUEST_HINT = "登录后可同步收藏与观看记录"

/**
 * cookie 里的昵称/组名是 urlencode 原值（苹果CMS 就是这么存的），展示前解码。
 * 只在含 `%` 时动手，避免把 `+` 误转成空格。
 */
internal fun String.decodeCookieText(): String {
    var text = this
    repeat(DECODE_PASSES) {
        if (!text.contains('%')) return text
        text = runCatching { java.net.URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
    }
    return text
}

/** 后端有的字段是双重编码（`%25E6...`），一次解码只会解掉外层。 */
private const val DECODE_PASSES = 2
