package com.starveil.core.network

/**
 * 后端路径常量。
 *
 * 路径大小写、点号、下划线必须与后端逐字一致。
 *
 * 三条不能动的约束：
 * 1. `v1.vod` 这种点号是后端按 URL action 反射分发的一部分，改成 `/v1/vod` 直接 404。
 * 2. 一个路径承担多种语义（[VOD_LIST] 同时是 banner / 榜单 / 分类列表 / 搜索），
 * 区分全靠 query 参数，参数写错会静默返回全量而不是报错。
 * 3. H5 支付页与 App 走同一个入口，登录态靠 cookie 落在同域；换前缀会让 cookie 失效。
 */
object ApiPaths {

    /** 相对 Retrofit baseUrl 的前缀。baseUrl 必须精确到本串末尾的斜杠。 */
    const val PREFIX = "icciu_api.php/"

    // ---- 启动与配置 ---------------------------------------------------------
    const val STARTUP = "v1.main/startup"
    const val CATEGORY = "v1.main/category"
    const val BANNER = "v1.main/banner" // 首页轮播（插件表）
    const val LINKS = "v1.main/links" // 首页快捷入口
    /** 首页「编辑精选」：后台指定标题与影片。 */
    const val FEATURED = "v1.main/featured"
    /** 首页「热度榜」：与 featured 同构（`{title,subtitle,vods}`）。 */
    const val HOT = "v1.main/hot"
    /** 首页「猜你喜欢」：同上。 */
    const val GUESS = "v1.main/guess"
    /** 每日推荐条数：只回 `{count}`，用于消息入口红点。 */
    const val PUSH = "v1.main/push"

    // ---- 影片列表 / 详情 ----------------------------------------------------
    /** banner、榜单、分类列表、搜索共用；靠 query 参数区分语义。 */
    const val VOD_LIST = "v1.vod"
    const val VOD_TYPES = "v1.vod/types"
    const val VOD_DETAIL = "v1.vod/detail"
    const val VOD_RANK_ALL = "v1.vod/vodPhbAll"
    const val VOD_RANK = "v1.vod/vodphb"
    const val VOD_PROGRESS = "v1.vod/videoProgress"

    // ---- 专题 / 直播 --------------------------------------------------------
    const val TOPIC_LIST = "v1.topic/topicList"
    const val TOPIC_DETAIL = "v1.topic/topicDetail"
    const val LIVE_LIST = "v1.zhibo"

    // ---- 账号 / 登录 --------------------------------------------------------
    const val LOGIN = "v1.auth/login"
    const val REGISTER = "v1.auth/register"
    const val REGISTER_SMS = "v1.auth/registerSms"
    /**
     * 设备账号登录（RDA）。首次带随机 token 就建号，之后同一 token 即登录；
     * 重装后凭设备指纹找回。见插件 `AuthController::postDevicelogin`。
     */
    const val DEVICE_LOGIN = "v1.auth/deviceLogin"
    const val FIND_PASS_SMS = "v1.auth/findpasssms"
    const val FIND_PASS = "v1.auth/findpass"
    /** 签到：`GET` 查状态、`POST` 签到，同一路径两种动词（`UserController::getSign/postSign`）。 */
    const val SIGN = "v1.user/sign"
    const val USER_INFO = "v1.user/detail"
    /** 改资料：插件 `UserController::postIndex`，白名单只有昵称与头像两列。 */
    const val USER_PROFILE = "v1.user/index"
    const val USER_AVATAR = "v1.upload/user" // 头像上传，multipart 字段名 file
    /**
     * 提现收款码上传：multipart 字段名固定 `file`，回 `data.file`
     * （`/upload/withdraw/12_1700000000.jpg`），提交提现时原样作为 `qrcode` 回传。
     * 服务端只接受 `/upload/withdraw/` 前缀，换成别的目录会被打回。
     */
    const val WITHDRAW_QRCODE = "v1.upload/withdraw"

    // ---- 会员 / 金币 --------------------------------------------------------
    const val BUY_CARD = "v1.user/buy"
    const val UPGRADE_GROUP = "v1.user/group"
    const val GROUP_SCORES = "v1.user/groups"
    const val ORDER = "v1.user/order" // POST 下单、GET 查单
    const val PAY = "v1.user/pay" // H5 支付页，交给系统浏览器
    const val PAY_TIP = "v1.user/payTip"
    const val GOLD_TIP = "v1.user/goldTip"
    const val GOLD_WITHDRAW = "v1.user/goldWithdrawApply"
    const val TASK_LIST = "v1.user/task"
    const val SHARE_SCORE = "v1.user/shareScore"
    const val SHARE_INFO = "v1.user/shareInfo"

    // ---- 观影记录 / 收藏 / 试看 / 购买 -------------------------------------
    const val VIEW_SECONDS = "v1.user/viewSeconds"
    /** 首页「继续观看」：最近 10 条进度，join mac_vod 取片名/封面。 */
    const val VIDEO_PROGRESS_LIST = "v1.user/videoProgressList"
    const val FAVS = "v1.user/favs"
    const val ULOG = "v1.user/ulog"
    const val CHECK_VOD_TRY_SEE = "v1.user/checkVodTrySee"
    const val BUY_POPEDOM = "v1.user/buypopedom"

    // ---- 社区 --------------------------------------------------------------
    const val COMMENT = "v1.comment"
    const val FEEDBACK = "v1.gbook"

    // ---- 直投激励视频 -------------------------------------------------------
    /** 领会话：表单 `vod_id` / `episode_number`，回 `{token,seconds,video_url}`。 */
    const val AD_SESSION = "v1.ad/session"
    /** 播放中上报播放位置（秒）：表单 `token` / `position`，只记进度不解锁。 */
    const val AD_PROGRESS = "v1.ad/progress"
    /**
     * 播完才调：表单只有 `token`。
     * 服务端按"进度够 + 真实经过时间够"校验后自己写解锁记录，客户端随后重查 `checkVodTrySee`。
     */
    const val AD_COMPLETE = "v1.ad/complete"

    /** Retrofit 注解不接受运行时拼接，仅用于日志、WebView 与 cookie 匹配。 */
    fun absolute(path: String): String = "/$PREFIX$path"
}
