package com.starveil.feature.user

/**
 * 用户中心 6 套皮肤的结构差异，压成纯数据（无 UI、无 Android 依赖）。
 *
 * ： 按 `app_theme_ui` 分支加载 6 个整文件复制的
 * Fragment，每个 Fragment 配一份自己的布局，全部在旧工程 `app/src/main/res/layout/` 下：
 *
 * | 变体 | 皮肤 | 旧布局 | 行数 |
 * |---|---|---|---|
 * | A | default | `fragment_user.xml` | 1003 |
 * | B | "1" YOUKU | `fragment_userb.xml` | 961 |
 * | C | "2" KATONG | `fragment_userc.xml` | 607 |
 * | D | "3" BILIBILI | `fragment_userd.xml` | 934 |
 * | E | "4" BILIBILI2 | `fragment_usere.xml` | 936 |
 * | F | "5" AQC | `fragment_userf.xml` | 1082 |
 *
 * 新客户端只有一套渲染实现（`UserCenterScreen`），6 套的差别只体现为：
 * 头部样式（[HeaderSpec]）、是否顶部大图、区块集合与区块栅格列数。
 * 变体常量见 [UserCenterLayouts]。
 *
 * 三处刻意的压缩：
 * 1. 旧头部是位图大图，新工程不放位图，统一用品牌渐变占位。
 * 2. 旧布局里 `visibility="gone"` 的区块不进配置（用户在界面上看不到它们）。
 * 3. 统计区只存标签，数值来自 `UserRepository.loadProfile()`，由 UI 按标签取（见 `UserCenterValues`）。
 */
enum class HeaderStyle {
    /** 顶部大图 + 头像压在图上：A / B / D / E / F。 */
    PHOTO_HERO,

    /** 无大图，头部自带渐变底：C（`fragment_userc.xml` 全文件无 `iv_user_top_bg`）。 */
    GRADIENT_BAND,
}

/**
 * 头部除"签到 / 消息 / 客服"三个固定图标之外的差别件。
 * 那三个图标在各变体里的取舍由 [HeaderSpec] 的三个布尔位表达。
 */
enum class HeaderExtra {
    /** 会员到期时间行（旧 `linTabs` / `tv_user_time`）。 */
    EXPIRE_TIME,

    /** 头部右侧"主页"胶囊（旧 `user_home` / `user_home_d`）。 */
    HOME_PILL,

    /** 头部齿轮，设置入口在头部而非列表里（F 的 `iv_user_title`）。 */
    SETTINGS_GEAR,
}

/** 区块标识。渲染器按 [SectionStyle] 选组件，按 id 区分行为特殊的区块。 */
enum class UserCenterSectionId {
    MEMBER_CARD,
    WALLET_CARD,
    STAT_ROW,
    PROMOTE_CARD,
    AD_BANNER,
    SHORTCUT_GRID,
    SHARE_CARD,
    NOTICE_HISTORY,
    TASK_GRID,
    TOOL_GRID,
    LOGOUT_ENTRY,
}

/** 区块排布方式。A 全部是整行列表，B–F 用图标网格与带副标题的瓷砖。 */
enum class SectionStyle {
    /** 整行列表项 + 右箭头（A 的"我的服务""常用功能"）。 */
    LIST_ROW,

    /** 图标 + 文案的网格，列数取 [UserCenterSection.columns]。 */
    ICON_GRID,

    /** 数字栏（影豆 / 积分 / 金币）。 */
    STAT_ROW,

    /** 卡片摘要：标题 + 一个主按钮 + 若干次要说明（会员卡、钱包、分享卡）。 */
    SUMMARY_ROW,

    /** 带副标题的瓷砖（F 的"任务中心 / 轻松赚积分"）。 */
    SUBTITLED_GRID,

    /** 广告位占位。 */
    BANNER,

    /** 公告 + 历史记录列表（B / D / E / F）。 */
    NOTICE_LIST,
}

/**
 * 区块内的一条入口。
 *
 * @param label 文案，取自旧布局 `android:text`
 * @param subtitle 副标题，仅 [SectionStyle.SUBTITLED_GRID] 使用
 * @param showsBadge 是否挂未读红点（C 的"系统消息"、B 的"未登录"）
 */
data class UserCenterEntry(
    val label: String,
    val subtitle: String? = null,
    val showsBadge: Boolean = false,
)

/** 一个区块：标识 + 标题 + 入口列表 + 栅格列数 + 排布方式。 */
data class UserCenterSection(
    val id: UserCenterSectionId,
    val title: String,
    val entries: List<UserCenterEntry>,
    val columns: Int = 1,
    val style: SectionStyle = SectionStyle.LIST_ROW,
)

/**
 * 头部配置。
 *
 * 三个布尔位对应旧布局头部的 `tv_user_slgn`（签到）、`llNotice`（消息）、
 * `tv_user_servlce`（客服）：B 的客服图标是 `gone`，C 三者都不在头部（在网格里）。
 */
data class HeaderSpec(
    val style: HeaderStyle,
    val showsSignIn: Boolean,
    val showsNotice: Boolean,
    val showsService: Boolean,
    val extras: Set<HeaderExtra> = emptySet(),
)

/** 一套用户中心的完整结构。 */
data class UserCenterLayout(
    val header: HeaderSpec,
    val showsTopBanner: Boolean,
    val gridColumns: Int,
    val sections: List<UserCenterSection>,
)
