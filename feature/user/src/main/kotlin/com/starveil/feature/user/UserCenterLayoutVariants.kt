package com.starveil.feature.user

/**
 * 6 套用户中心的结构配置本体。
 *
 * ： 按 `app_theme_ui` 分支加载 6 个整文件复制的
 * Fragment，每个 Fragment 配一份自己的布局（旧工程 `app/src/main/res/layout/`）。
 * 把每份布局"有哪些区块、几列、头部有什么"压成数据，渲染只有一套实现。
 *
 * 三处刻意的压缩：
 * 1. 旧头部是位图大图，新工程不放位图，统一用品牌渐变占位。
 * 2. 旧布局里 `visibility="gone"` 的区块不进配置（界面上看不到）。
 * 3. 统计区只存标签，数值来自 `UserRepository.loadProfile()`，由 UI 按标签取（见 `UserCenterValues`）。
 */

private fun entry(label: String) = UserCenterEntry(label = label)

private fun listRows(
    id: UserCenterSectionId,
    title: String,
    vararg labels: String,
) = UserCenterSection(
    id = id,
    title = title,
    entries = labels.map { entry(it) },
    columns = 1,
    style = SectionStyle.LIST_ROW,
)

private fun iconGrid(
    id: UserCenterSectionId,
    title: String,
    columns: Int,
    vararg labels: String,
) = UserCenterSection(
    id = id,
    title = title,
    entries = labels.map { entry(it) },
    columns = columns,
    style = SectionStyle.ICON_GRID,
)

private fun statRow(vararg labels: String) = UserCenterSection(
    id = UserCenterSectionId.STAT_ROW,
    title = "",
    entries = labels.map { entry(it) },
    columns = labels.size,
    style = SectionStyle.STAT_ROW,
)

/**
 * 摘要卡。入口列表的顺序有语义：**最后一条是主按钮**，前面的都是灰色说明。
 */
private fun summary(
    id: UserCenterSectionId,
    title: String,
    vararg labels: String,
) = UserCenterSection(
    id = id,
    title = title,
    entries = labels.map { entry(it) },
    columns = 1,
    style = SectionStyle.SUMMARY_ROW,
)

private fun tiles(title: String, vararg entries: UserCenterEntry) = UserCenterSection(
    id = UserCenterSectionId.TASK_GRID,
    title = title,
    entries = entries.toList(),
    columns = 2,
    style = SectionStyle.SUBTITLED_GRID,
)

private fun banner(title: String) = UserCenterSection(
    id = UserCenterSectionId.AD_BANNER,
    title = title,
    entries = emptyList(),
    columns = 1,
    style = SectionStyle.BANNER,
)

private fun noticeList(title: String) = UserCenterSection(
    id = UserCenterSectionId.NOTICE_HISTORY,
    title = title,
    entries = emptyList(),
    columns = 1,
    style = SectionStyle.NOTICE_LIST,
)

/**
 * 退出登录入口。旧布局里没有这一行（历史版本把退出放在设置页），新客户端放在用户中心底部，
 * 调 `AuthRepository.logout()`。
 */
private fun logout() = UserCenterSection(
    id = UserCenterSectionId.LOGOUT_ENTRY,
    title = "",
    entries = emptyList(),
    columns = 1,
    style = SectionStyle.LIST_ROW,
)

/**
 * A：`fragment_user.xml`（default 分支，唯一一套"整行列表"）。
 *
 * 会员卡在 `fragment_user.xml:298-395`（mcv_user：立即续费 + 黄金会员 + 到期时间）；
 * 之后三张纯列表卡：`:420` 起历史/收藏，购买积分/缓存/分享APP，
 * 再是 一键加群/任务中心/清理缓存/设置。末尾"更多服务"四个入口旧布局里全 `gone`，忽略。
 */
internal val userCenterLayoutA = UserCenterLayout(
    header = HeaderSpec(
        style = HeaderStyle.PHOTO_HERO,
        showsSignIn = true,
        showsNotice = true,
        showsService = true,
        extras = setOf(HeaderExtra.EXPIRE_TIME),
    ),
    showsTopBanner = true,
    gridColumns = 1,
    sections = listOf(
        summary(UserCenterSectionId.MEMBER_CARD, "黄金会员", "到期时间", "立即续费"),
        statRow("影豆", "积分", "金币"),
        banner("会员广告位"),
        listRows(
            UserCenterSectionId.SHORTCUT_GRID,
            "我的服务",
            "历史记录",
            "我的收藏",
            "购买积分",
            "缓存",
            "分享APP",
        ),
        listRows(UserCenterSectionId.TOOL_GRID, "常用功能", "一键加群", "任务中心", "清理缓存", "设置"),
        logout(),
    ),
)

/**
 * B：`fragment_userb.xml`。头部多一个"主页"胶囊，客服图标 `gone`；
 * 没有会员续费卡，换成"我的推广"卡（`tv_user_tuiguang` 默认"未登录"）与广告卡；
 * 四列网格出现两次（`fragment_userb.xml:661-785`）。
 */
internal val userCenterLayoutB = UserCenterLayout(
    header = HeaderSpec(
        style = HeaderStyle.PHOTO_HERO,
        showsSignIn = true,
        showsNotice = true,
        showsService = false,
        extras = setOf(HeaderExtra.HOME_PILL),
    ),
    showsTopBanner = true,
    gridColumns = 4,
    sections = listOf(
        UserCenterSection(
            id = UserCenterSectionId.PROMOTE_CARD,
            title = "我的推广",
            entries = listOf(
                UserCenterEntry(label = "积分可兑换会员"),
                UserCenterEntry(label = "推广赚积分", showsBadge = true),
            ),
            style = SectionStyle.SUMMARY_ROW,
        ),
        banner("免费VIP · 饿了么送"),
        iconGrid(UserCenterSectionId.SHORTCUT_GRID, "我的内容", 4, "我的下载", "历史记录", "已追收藏", "我的购买"),
        noticeList("历史记录"),
        iconGrid(UserCenterSectionId.TASK_GRID, "常用功能", 4, "任务中心", "分享有礼", "缓存清理", "一键加群"),
        iconGrid(UserCenterSectionId.TOOL_GRID, "更多服务", 4, "积分商城", "金币提现", "求片留言", "设置"),
        logout(),
    ),
)

/**
 * C：`fragment_userc.xml`。唯一没有顶部大图的变体，头部是橙色渐变带；
 * 没有影豆/金币数字栏，改成"会员中心"+"我的钱包"两张摘要卡；
 * 签到与系统消息从头部下沉到四列网格（`fragment_userc.xml:459-604`）。
 * 旧文件里另有一个 5 列 `ll_task`（每日分享/在线客服/金币提现/积分商城），整块 `gone`。
 */
internal val userCenterLayoutC = UserCenterLayout(
    header = HeaderSpec(
        style = HeaderStyle.GRADIENT_BAND,
        showsSignIn = false,
        showsNotice = false,
        showsService = false,
        extras = setOf(HeaderExtra.EXPIRE_TIME),
    ),
    showsTopBanner = false,
    gridColumns = 4,
    sections = listOf(
        summary(UserCenterSectionId.MEMBER_CARD, "会员中心", "立即续费"),
        summary(UserCenterSectionId.WALLET_CARD, "我的钱包", "当前积分", "立即充值"),
        iconGrid(UserCenterSectionId.TASK_GRID, "常用功能", 4, "福利中心", "每日签到", "我的收藏", "我的下载"),
        iconGrid(UserCenterSectionId.TOOL_GRID, "更多服务", 4, "系统消息", "留言求片", "分享有礼"),
        logout(),
    ),
)

/**
 * D：`fragment_userd.xml`。头部有大图、到期时间与"主页"胶囊；影豆/积分/金币三栏；
 * 四列"我的内容" + 分享卡（"分享给你的小伙伴吧~~"）+ "推荐服务"公告与历史列表，
 * 最后是两张四列网格。
 */
internal val userCenterLayoutD = UserCenterLayout(
    header = HeaderSpec(
        style = HeaderStyle.PHOTO_HERO,
        showsSignIn = true,
        showsNotice = true,
        showsService = true,
        extras = setOf(HeaderExtra.EXPIRE_TIME, HeaderExtra.HOME_PILL),
    ),
    showsTopBanner = true,
    gridColumns = 4,
    sections = listOf(
        statRow("影豆", "积分", "金币"),
        iconGrid(UserCenterSectionId.SHORTCUT_GRID, "我的内容", 4, "我的下载", "历史记录", "已追收藏", "购买积分"),
        UserCenterSection(
            id = UserCenterSectionId.SHARE_CARD,
            title = "有奖分享",
            entries = listOf(
                entry("分享给你的小伙伴吧~~"),
                entry("分享可获得积分！可兑换会员噢~~"),
                entry("有奖分享"),
            ),
            style = SectionStyle.SUMMARY_ROW,
        ),
        noticeList("推荐服务"),
        iconGrid(UserCenterSectionId.TASK_GRID, "常用功能", 4, "任务中心", "推广中心", "金币提现", "积分商城"),
        iconGrid(UserCenterSectionId.TOOL_GRID, "更多服务", 4, "求片留言", "一键加群", "清理缓存", "设置"),
        logout(),
    ),
)

/**
 * E：`fragment_usere.xml`。与 D 逐块对齐，唯一结构差异是"主页"胶囊整块被注释掉
 * （`fragment_usere.xml:168-193` 以 `-->` 收尾），所以头部只剩到期时间。
 * 因此这里直接复用 D 的区块列表，只换头部。
 */
internal val userCenterLayoutE = UserCenterLayout(
    header = HeaderSpec(
        style = HeaderStyle.PHOTO_HERO,
        showsSignIn = true,
        showsNotice = true,
        showsService = true,
        extras = setOf(HeaderExtra.EXPIRE_TIME),
    ),
    showsTopBanner = true,
    gridColumns = 4,
    sections = userCenterLayoutD.sections,
)

/**
 * F：`fragment_userf.xml`。头部是"签到 / 消息 / 设置齿轮 / 头像 / 会员等级"，
 * 客服下沉到"常用工具"；数字栏四栏（影豆/积分/金币/推广）；会员卡位被广告图
 * `awvUsers` 占用；任务区是带副标题的两列瓷砖（`fragment_userf.xml:503-589`，
 * 金币提现/分享有礼那两格旧布局里 `gone`）；"常用工具"是 7 个入口的四列网格
 * （`fragment_userf.xml:949-1074`）。
 */
internal val userCenterLayoutF = UserCenterLayout(
    header = HeaderSpec(
        style = HeaderStyle.PHOTO_HERO,
        showsSignIn = true,
        showsNotice = true,
        showsService = false,
        extras = setOf(HeaderExtra.SETTINGS_GEAR),
    ),
    showsTopBanner = true,
    gridColumns = 4,
    sections = listOf(
        statRow("影豆", "积分", "金币", "推广"),
        banner("会员广告位"),
        iconGrid(UserCenterSectionId.SHORTCUT_GRID, "我的内容", 4, "我的下载", "历史记录", "已追收藏", "购买积分"),
        tiles(
            "任务中心",
            UserCenterEntry(label = "任务中心", subtitle = "轻松赚积分"),
            UserCenterEntry(label = "积分商城", subtitle = "免费兑会员"),
        ),
        noticeList("推荐服务"),
        iconGrid(
            UserCenterSectionId.TOOL_GRID,
            "常用工具",
            4,
            "金币提现",
            "分享APP",
            "缓存清理",
            "在线客服",
            "一键加群",
            "留言求片",
            "推广中心",
        ),
        logout(),
    ),
)
