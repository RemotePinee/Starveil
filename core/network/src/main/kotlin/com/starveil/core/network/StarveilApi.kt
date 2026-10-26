package com.starveil.core.network

import com.starveil.core.network.dto.AdCompleteDto
import com.starveil.core.network.dto.AdProgressDto
import com.starveil.core.network.dto.AdSessionDto
import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.AvatarDto
import com.starveil.core.network.dto.BuyCardResultDto
import com.starveil.core.network.dto.DeviceLoginDto
import com.starveil.core.network.dto.FavoriteDto
import com.starveil.core.network.dto.GoldTipDto
import com.starveil.core.network.dto.GroupUpgradeDto
import com.starveil.core.network.dto.LiveDto
import com.starveil.core.network.dto.LivePageDto
import com.starveil.core.network.dto.MemberGroupCatalogDto
import com.starveil.core.network.dto.OrderDto
import com.starveil.core.network.dto.OrderStatusDto
import com.starveil.core.network.dto.PageDto
import com.starveil.core.network.dto.PayTipDto
import com.starveil.core.network.dto.RankBoardDto
import com.starveil.core.network.dto.RankCatalogDto
import com.starveil.core.network.dto.ScoreDto
import com.starveil.core.network.dto.StartupDto
import com.starveil.core.network.dto.TopicDetailDto
import com.starveil.core.network.dto.TopicDto
import com.starveil.core.network.dto.TrySeeDto
import com.starveil.core.network.dto.UserInfoDto
import com.starveil.core.network.dto.VodDto
import com.starveil.core.network.dto.WithdrawApplyResultDto
import okhttp3.MultipartBody
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Multipart
import retrofit2.http.Part
import kotlinx.serialization.json.JsonElement
import com.starveil.core.network.dto.SignStateDto
import com.starveil.core.network.dto.PointsTaskListDto

/**
 * 后端接口定义。baseUrl 已包含 /icciu_api.php/ 前缀（见 [ApiClient]）。
 * 路径陷阱（必须保留注释，否则后人会改错）：
 * - /v1.vod 同时承担 banner、榜单、分类列表、搜索四种语义，
 * 只靠 type / class / level / wd / request_type 参数区分；
 * 任何一个参数写错都会静默返回全量列表，不会报错。
 * - 后端按 URL action 反射分发 get / post / delete 前缀方法，方法名大小写不敏感。
 * - apikey / keytime 由 [ApiSignatureInterceptor] 统一注入，接口方法不再声明。
 */
interface StarveilApi : DeviceAuthApi {

    /**
     * 启动接口。这里拿到皮肤、Tab 布局、广告位、支付方式；
     * 四个关键字段缺失会被判盗版并杀进程——
     * 新版只标记契约不完整（[com.starveil.core.model.SkinResolution.contractBroken]），不退出。
     */
    @GET(ApiPaths.STARTUP)
    suspend fun startup(): ApiEnvelopeDto<StartupDto>

    /**
     * 分类/筛选列表。type 为 0 时后端按全部返回。
     *
     * 片库筛选的四维（`area` / `year` / `lang` / `class`）与排序（`by`）由
     * `DefaultVodRepository.filter` 统一拼装，页面不直接调本方法。
     *
     * 参数名与 macCMS 前台（`mac_param_url()`）逐字同名：`class` 是**扩展分类名**
     * （不是 id，后端按名字 LIKE 匹配），`by` 是排序字段、方向由后端默认 desc 决定。
     * 空串 = 该维度不限。
     */
    @GET(ApiPaths.VOD_LIST)
    suspend fun listVod(
        @Query("type") type: Int,
        @Query("page") page: Int,
        @Query("limit") limit: Int,
        @Query("class") classFilter: String = "",
        @Query("area") area: String = "",
        @Query("year") year: String = "",
        @Query("lang") lang: String = "",
        @Query("by") by: String = "",
    ): ApiEnvelopeDto<PageDto<VodDto>>

    /** 详情，含三层播放列表。 */
    @GET(ApiPaths.VOD_DETAIL)
    suspend fun vodDetail(
        @Query("vod_id") vodId: Int,
        @Query("rel_limit") relLimit: Int = 10,
    ): ApiEnvelopeDto<VodDto>

    /**
     * 登录。表单字段名固定 `user_name` / `user_pwd`。
     *
     * 返回值只是 `code`/`msg`，真正的登录态是响应 Set-Cookie 下发的 7 个 cookie。
     *
     * `data` 用 [JsonElement] 接：有的后端版本回提示串，有的回整个 profile 数组，
     * 写死成 String 会在后者上抛"数据解析失败"（用户登录时看到的正是这句）。
     */
    @FormUrlEncoded
    @POST(ApiPaths.LOGIN)
    suspend fun login(
        @Field("user_name") userName: String,
        @Field("user_pwd") password: String,
    ): ApiEnvelopeDto<JsonElement>

    /**
     * 注册。表单字段固定 `user_name` / `user_pwd` / `user_pwd2` / `uid`。
     *
     * `uid` 传渠道号，常用值是 `App.CHANNE`。
     * `data` 同样用 [JsonElement] 接，避免后端回对象时解析失败。
     * 响应 `data` 只是提示串，注册成功不等于登录成功——注册后仍要再登录一次。
     */
    @FormUrlEncoded
    @POST(ApiPaths.REGISTER)
    suspend fun register(
        @Field("user_name") userName: String,
        @Field("user_pwd") password: String,
        @Field("user_pwd2") passwordConfirm: String,
        @Field("uid") uid: String,
        /** 手机号注册时的短信验证码；账号注册传空串，后端按 `register_modes` 决定要不要。 */
        @Field("code") code: String = "",
    ): ApiEnvelopeDto<JsonElement>


    /**
     * 注册短信验证码（旧常量 `VERIFY_CODE`）。
     * `ac` 固定 `phone`：只支持手机号注册。
     */
    @GET(ApiPaths.REGISTER_SMS)
    suspend fun sendRegisterCode(
        @Query("to") phone: String,
        @Query("ac") ac: String = "phone",
    ): ApiEnvelopeDto<String>

    /**
     * 找回密码。注意是 **GET + query**，不是表单 POST；
     * 历史代码注释里给出的真实请求串见 。
     *
     * `user_pwd` 与 `user_pwd2` 必须都传且相同，`type` 固定 `2`。
     */
    @GET(ApiPaths.FIND_PASS)
    suspend fun resetPassword(
        @Query("to") phone: String,
        @Query("user_pwd") password: String,
        @Query("user_pwd2") passwordConfirm: String,
        @Query("code") code: String,
        @Query("ac") ac: String = "phone",
        @Query("type") type: String = "2",
    ): ApiEnvelopeDto<String>

    /**
     * 找回密码短信验证码。
     * 与 [sendRegisterCode] 是两个不同路径，混用会拿到"验证码类型不匹配"。
     */
    @GET(ApiPaths.FIND_PASS_SMS)
    suspend fun sendResetCode(
        @Query("to") phone: String,
        @Query("ac") ac: String = "phone",
    ): ApiEnvelopeDto<String>

    /** 用户详情。无参数，身份靠 cookie 判定，未登录返回 `code=403`。 */
    @GET(ApiPaths.USER_INFO)
    suspend fun userInfo(): ApiEnvelopeDto<UserInfoDto>

    /**
     * 收藏列表。`ulog_type` 传 2 表示影片类收藏
     */
    @GET(ApiPaths.FAVS)
    suspend fun favoriteList(
        @Query("page") page: Int,
        @Query("limit") limit: Int,
        @Query("ulog_type") ulogType: Int = FAVORITE_TYPE_VOD,
    ): ApiEnvelopeDto<PageDto<FavoriteDto>>

    /**
     * 收藏。三个字段全是字符串，`moduleId` 固定 `1`
     * （：`collect("1", vod_id, "2")`）。
     */
    @FormUrlEncoded
    @POST(ApiPaths.ULOG)
    suspend fun favoriteAdd(
        @Field("id") vodId: String,
        @Field("mid") moduleId: String = FAVORITE_MODULE_ID,
        @Field("type") type: String = FAVORITE_TYPE_VOD_TEXT,
    ): ApiEnvelopeDto<String>

    /**
     * 取消收藏。`ids` 是影片 id 的逗号串，
     * 不是 `ulog_id`。
     */
    @DELETE(ApiPaths.ULOG)
    suspend fun favoriteRemove(
        @Query("ids") ids: String,
        @Query("type") type: String = FAVORITE_TYPE_VOD_TEXT,
    ): ApiEnvelopeDto<String>

    /**
     * 上报观看时长（`v1.user/viewSeconds`）。
     *
     * 表单字段 `view_seconds` 是**本次新增的秒数**，不是累计值。
     * 旧播放页每 30 秒固定上报 60，等于两倍虚增；新实现按真实间隔上报，见 ProgressRules。
     */
    @FormUrlEncoded
    @POST(ApiPaths.VIEW_SECONDS)
    suspend fun addWatchTime(
        @Field("view_seconds") viewSeconds: Int,
    ): ApiEnvelopeDto<ScoreDto>

    /**
     * 专题列表（路径常量见 ）。
     *
     * 无参数，`data` 是分页壳：旧接口声明成 `PageResult<SpecialtTopicBean>`，
     * 真实 payload 就是壳里再套一层 `{total,page,limit,list}`。
     */
    @GET(ApiPaths.TOPIC_LIST)
    suspend fun topicList(): ApiEnvelopeDto<PageDto<TopicDto>>

    /** 专题详情（路径常量见 ）。`topic_id` 是字符串。 */
    @GET(ApiPaths.TOPIC_DETAIL)
    suspend fun topicDetail(@Query("topic_id") topicId: String): ApiEnvelopeDto<TopicDetailDto>

    /**
     * 直播列表（路径常量见 ）。
     *
     * 后端 `LiveController::getIndex` 支持 `page`/`limit`（默认 20、上限 60），
     * 早期客户端不传参所以只拿到第一页；这里把分页透出去。
     */
    @GET(ApiPaths.LIVE_LIST)
    suspend fun liveList(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 30,
        @Query("group") group: String = "",
    ): ApiEnvelopeDto<LivePageDto>

    /**
     * 榜单（路径常量见 ）。
     *
     * `order_by` 是**带空格和方向**的排序串，旧取的只有四种：
     * `vod_hits desc`、`vod_hits_month desc`、`vod_hits_week desc`、`vod_hits_day desc`。
     * `type` 是分类 id，`page` 从 1 开始，`limit` 旧页面固定 20。
     * `data` 只有 `list`，没有 total，不能按分页壳解析。
     */
    @GET(ApiPaths.VOD_RANK)
    suspend fun vodRank(
        @Query("order_by") orderBy: String,
        @Query("type") type: Int,
        @Query("page") page: Int,
        @Query("limit") limit: Int = 20,
    ): ApiEnvelopeDto<RankBoardDto>

    /**
     * 全量榜单（路径常量见 ）。
     *
     * 无参数。`data` 是 `{zhui, list}` 两组「分类 -> 影片」，与 [vodRank] 的平铺列表
     * 不是一个结构，不能合并成一个方法。
     */
    @GET(ApiPaths.VOD_RANK_ALL)
    suspend fun vodRankAll(): ApiEnvelopeDto<RankCatalogDto>

    /**
     * 试看判定（路径常量见 ）。
     *
     * 三个 query 都是字符串，映射关系来自旧调用 ：
     * `id` = 影片 id，`nid` = 单集 nid，`mid` 固定 `"1"`（模块 id，与购买接口 `:1861` 一致）。
     * 播放源下标不在契约里——无论第几源都只传这三项。
     */
    @GET(ApiPaths.CHECK_VOD_TRY_SEE)
    suspend fun checkVodTrySee(
        @Query("id") vodId: String,
        @Query("nid") episodeNumber: String,
        @Query("mid") moduleId: String = TRY_SEE_MODULE_ID,
    ): ApiEnvelopeDto<TrySeeDto>

    /**
     * 会员价目表（路径 `v1.user/groups`，常量 `SCORE_LIST`）。
     *
     * `data.list` 是「组 id -> 组」的对象而不是数组；只读键 `"3"` 的 VIP 会员组，
     * 用它的 `group_points_day/week/month/year` 当四个时长的价格（积分值，
     * 支付页除以 10 当元）。
     */
    @GET(ApiPaths.GROUP_SCORES)
    suspend fun memberGroups(): ApiEnvelopeDto<MemberGroupCatalogDto>

    /**
     * 创建充值订单（路径 `v1.user/order`，常量 `POINT_PURCHASE`）。
     *
     * 表单只有一个 `price`，单位元；返回 `order_code` 供拼支付页地址
     * 同一路径的 GET（`order_code`）是查询订单状态，
     * 响应体不同，另见 [orderStatus]。
     */
    @FormUrlEncoded
    @POST(ApiPaths.ORDER)
    suspend fun createOrder(@Field("price") price: String): ApiEnvelopeDto<OrderDto>

    /**
     * 查询订单状态（`GET v1.user/order?order_code=`）。
     *
     * 与 [createOrder] **同一路径**，只靠 HTTP 方法区分：GET 带 `order_code` 查、
     * POST 带 `price` 建。响应体也不同（多 `order_status` 等字段），所以是两个 DTO。
     *
     * H5 支付页回跳后调一次，`order_status == 0` 即未支付
     */
    @GET(ApiPaths.ORDER)
    suspend fun orderStatus(@Query("order_code") orderCode: String): ApiEnvelopeDto<OrderStatusDto>

    /**
     * 会员兑换/升级（路径 `v1.user/group`，常量 `UPGRADE_GROUP`）。
     *
     * 两个表单字段，名字保持后端原样（`long` 是关键字但不是 Kotlin 的）：
     * - `long` 是**时长档位串**（`day`/`week`/`month`/`year`），旧页把它叫 `price`
     * 变量名容易误读，实际传的是 `packaged`；
     * - `group_id` 是会员组 id，传用户当前所在组，
     * 新版传价目表里被购买的那个组（见 `PayRepository.upgradeMembership`）。
     *
     * 语义是"用刚充的积分把会员时长延长一档"，必须在订单已支付之后再调。
     */
    @FormUrlEncoded
    @POST(ApiPaths.UPGRADE_GROUP)
    suspend fun upgradeGroup(
        @Field("long") period: String,
        @Field("group_id") groupId: String,
        // 服务端以订单为准开通：按 order_code 校验已支付并写幂等标记。
        // 空串是兼容路径，服务端只会取该用户 5 分钟内已支付且未开通过的订单。
        @Field("order_code") orderCode: String,
    ): ApiEnvelopeDto<GroupUpgradeDto>

    /**
     * 在线充值提示（路径 `v1.user/payTip`）。
     *
     * `data` 只有 `url`；页面上那句提示来自响应壳的 `msg`，
     * 两个字段都要读，所以仓库层不能只取 `data`。
     */
    @GET(ApiPaths.PAY_TIP)
    suspend fun payTip(): ApiEnvelopeDto<PayTipDto>

    /** 金币提现提示：`msg` 是最低提现额，`info` 是比例说明。 */
    @GET(ApiPaths.GOLD_TIP)
    suspend fun goldTip(): ApiEnvelopeDto<GoldTipDto>

    /**
     * 金币提现申请（`POST v1.user/goldWithdrawApply`）。
     *
     * 表单字段全部来自旧调用：
     * `num` = 提现金额（元，字符串）、`type` = 收款渠道（1 支付宝 / 2 微信）、
     * `account` = 收款账号、`realname` = 收款姓名。
     *
     * 新增的 `qrcode` 是 [uploadWithdrawQrcode] 返回的站内相对路径。空值必须真的不发
     * （Retrofit 对 null 的 `@Field` 直接省字段）：传空串等于告诉后端"用户传了个空收款码"。
     *
     * 成功只表示"申请已提交"，审核结果不在这个响应里；旧页提交后重新拉一次提现记录。
     */
    @FormUrlEncoded
    @POST(ApiPaths.GOLD_WITHDRAW)
    suspend fun withdrawApply(
        @Field("num") amount: String,
        @Field("type") channel: String,
        @Field("account") account: String,
        @Field("realname") realName: String,
        @Field("qrcode") qrcode: String? = null,
    ): ApiEnvelopeDto<WithdrawApplyResultDto>

    /**
     * 提现收款码上传（`POST v1.upload/withdraw`，multipart 字段名 `file`）。
     *
     * 响应体与头像上传同构（`{"file": "/upload/withdraw/..."}`），因此复用 [AvatarDto]，
     * 不为同样的一个字段再建一个类。`data.file` 是站内相对路径，拼域名展示由调用方负责。
     *
     * 放在本接口而不是 [ProfileApi]：仓库层只拿得到 [StarveilApi]（`:app` 的 AppContainer
     * 注入），[ProfileApi] 只有资料仓库持有。
     */
    @Multipart
    @POST(ApiPaths.WITHDRAW_QRCODE)
    suspend fun uploadWithdrawQrcode(
        @Part file: MultipartBody.Part,
    ): ApiEnvelopeDto<AvatarDto>

    /**
     * 卡密兑换（路径 `v1.user/buy`，常量 `CARD_BUY`）。
     *
     * 表单只有 `card_pwd`；`data.msg` 是后端提示，但业务成功只看响应壳的 `code`
     */
    @FormUrlEncoded
    @POST(ApiPaths.BUY_CARD)
    suspend fun buyCard(@Field("card_pwd") cardPwd: String): ApiEnvelopeDto<BuyCardResultDto>

    /**
     * 单集购买（路径 `v1.user/buypopedom`，常量 `BUY_VIDEO`）。
     *
     * 六个表单字段全部来自旧调用 ：
     * `type` 恒 `"4"`、`id` 影片 id、`sid` 播放源 id、`nid` 单集 nid、
     * `mid` 恒 `"1"`（模块 id，与试看接口一致）、`pay_type` 恒 `"1"`。
     *
     * 用 [FieldMap] 而不是六个 `@Field`：detekt 参数上限是 5（AGENTS.md 第 2 节），
     * 字段名逐个写在 `DefaultPayRepository` 的表单构造里，契约测试逐个断言。
     */
    @FormUrlEncoded
    @POST(ApiPaths.BUY_POPEDOM)
    suspend fun buyEpisode(@FieldMap fields: Map<String, String>): ApiEnvelopeDto<String>

    /**
     * 直投激励视频：领观看会话。
     *
     * 表单 `vod_id` / `episode_number`，回 `{token, seconds, video_url}`。
     * token 只在服务端有意义，客户端原样回传给 [adProgress] 与 [adComplete]。
     * 服务端会顺带记下"开始时间"，所以拿到 token 之后才真正开始计时。
     */
    @FormUrlEncoded
    @POST(ApiPaths.AD_SESSION)
    suspend fun adSession(
        @Field("vod_id") vodId: String,
        @Field("episode_number") episodeNumber: String,
    ): ApiEnvelopeDto<AdSessionDto>

    /**
     * 直投激励视频：上报播放位置（秒）。
     *
     * 播放期间约每 5 秒一次，`position` 是**当前播放位置**而不是增量。
     * 这一笔不解锁，只让服务端能对着自己的时钟复核进度。
     */
    @FormUrlEncoded
    @POST(ApiPaths.AD_PROGRESS)
    suspend fun adProgress(
        @Field("token") token: String,
        @Field("position") positionSec: Int,
    ): ApiEnvelopeDto<AdProgressDto>

    /**
     * 直投激励视频：完成并请求解锁。
     *
     * `data.unlocked == 1` 才算服务端写了本集解锁记录；客户端据此决定是否把
     * 控制权交回调用方（调用方会重查 `checkVodTrySee`）。
     */
    @FormUrlEncoded
    @POST(ApiPaths.AD_COMPLETE)
    suspend fun adComplete(@Field("token") token: String): ApiEnvelopeDto<AdCompleteDto>
}

/** 收藏模块 id，固定 1。 */
private const val FAVORITE_MODULE_ID = "1"

/** `ulog_type` / `type` 的影片取值：query 用数字，表单用字符串。 */
private const val FAVORITE_TYPE_VOD = 2
private const val FAVORITE_TYPE_VOD_TEXT = "2"

/** 试看接口的模块 id，恒传 "1"（、`:1861`）。 */
private const val TRY_SEE_MODULE_ID = "1"
