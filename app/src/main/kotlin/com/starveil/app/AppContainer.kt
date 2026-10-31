package com.starveil.app

import android.app.Activity
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.starveil.app.ads.LocalAdGateway
import com.starveil.app.ads.AdProviders
import com.starveil.app.ads.RewardRequestContext
import com.starveil.app.probe.RestrictedWebProbe
import com.starveil.core.common.AppResult
import com.starveil.core.common.AppError
import com.starveil.core.common.ad.AdGateway
import com.starveil.core.common.ad.AdPlacements
import com.starveil.core.data.AccessRepository
import com.starveil.core.data.AdUnlockRepository
import com.starveil.core.data.AuthRepository
import com.starveil.core.data.CommentRepository
import com.starveil.core.data.ContinueWatchingRepository
import com.starveil.core.data.DanmuRepository
import com.starveil.core.data.DefaultAccessRepository
import com.starveil.core.data.DefaultAdUnlockRepository
import com.starveil.core.data.DefaultAuthRepository
import com.starveil.core.data.DefaultCommentRepository
import com.starveil.core.data.DeviceCredentialStore
import com.starveil.core.data.DefaultContinueWatchingRepository
import com.starveil.core.data.DefaultDanmuRepository
import com.starveil.core.data.DefaultFavoriteRepository
import com.starveil.core.data.DefaultLiveRepository
import com.starveil.core.data.DefaultPayRepository
import com.starveil.core.data.DefaultPlaybackRepository
import com.starveil.core.data.DefaultRankRepository
import com.starveil.core.data.DefaultShortPlayRepository
import com.starveil.core.data.DefaultStartupRepository
import com.starveil.core.data.DefaultTopicRepository
import com.starveil.core.data.DefaultUserRepository
import com.starveil.core.data.DefaultVodRepository
import com.starveil.core.data.DefaultWithdrawRepository
import com.starveil.core.data.FavoriteRepository
import com.starveil.core.data.HomeSnapshotStore
import com.starveil.core.data.DetailSnapshotStore
import com.starveil.core.data.FilterSnapshotStore
import com.starveil.core.data.SnapshotStore
import com.starveil.core.data.PageSnapshotStore
import java.io.File
import com.starveil.core.data.LiveRepository
import com.starveil.core.data.PlaybackRepository
import com.starveil.core.data.PayRepository
import com.starveil.core.data.PreferenceSessionStore
import com.starveil.core.data.DefaultSearchHistoryStore
import com.starveil.core.data.SearchHistoryStore
import com.starveil.core.data.RankRepository
import com.starveil.core.data.SessionStore
import com.starveil.core.data.ShortPlayRepository
import com.starveil.core.data.DataStoreThemePreference
import com.starveil.core.data.StartupRepository
import com.starveil.core.data.ThemePreference
import com.starveil.core.data.TopicRepository
import com.starveil.core.data.UserRepository
import com.starveil.core.data.VodRepository
import com.starveil.core.data.WithdrawRepository
import com.starveil.core.model.Danmu
import com.starveil.core.model.Session
import com.starveil.core.model.TrySeeGate
import com.starveil.core.network.ApiClient
import com.starveil.core.network.ApiSignature
import com.starveil.feature.player.DanmuSource
import com.starveil.feature.player.PlayerDeps
import com.starveil.feature.player.ProgressSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.starveil.core.data.PointsRepository
import com.starveil.core.data.DefaultPointsRepository
import com.starveil.core.data.DefaultFeedbackRepository
import com.starveil.core.data.FeedbackRepository
import com.starveil.core.model.DanmuTarget

/** 会话落盘位置。DataStore 单例挂在 Application context 上，与 Activity 生命周期无关。 */
private val Context.sessionDataStore by preferencesDataStore(name = "starveil_session")

/** 皮肤本地选择单独一个文件：清缓存/换号不该把用户选的主题一起清掉。 */
private val Context.themeDataStore by preferencesDataStore(name = "starveil_theme")

/**
 * 依赖容器。手写而非 Hilt：模块数少、构造函数注入足够。
 *
 * 新增依赖时在这里加字段，不要在业务代码里 new 单例。
 */
class AppContainer(
    context: Context,
    apiHost: String,
    signatureSecret: String,
) {
    /**
     * 广告出口：直投实现。`isReady` 读后台下发的广告位配置——没有素材时恒 false，
     * UI 因此不会出现广告入口，也不会预加载任何东西。
     *
     * 字段用 `@Volatile`：启动配置在导航层拉回来后写入，播放页可能在别的线程读。
     */
    @Volatile
    private var adPlacements: AdPlacements = AdPlacements.NONE

    /**
     * 广告出口。启动配置就位后由 [updateAdPlacements] 换成平台实现
     * （TopOn / TradPlus），平台没配齐或未初始化时仍是直投实现。
     *
     * 对外只读：播放页用它判 `isReady(REWARD)`，拿到什么实现由这里决定。
     */
    @Volatile
    var adGateway: AdGateway = LocalAdGateway { adPlacements }
        private set

    /** 当前前台 Activity：全屏广告（激励/开屏/插屏）必须有宿主才能展示。 */
    @Volatile
    private var hostActivity: Activity? = null

    /** 由前台 Activity 在 `onResume` 里挂上。摘除走 [detachActivity]，不传 null。 */
    fun attachActivity(activity: Activity) {
        hostActivity = activity
    }

    /**
     * 摘掉 [activity]，但它已经不是当前宿主时什么都不做。
     *
     * 必须按实例判断，不能无条件置空：冷启动时 `SplashAdActivity` 先起，
     * `startActivity` 之后 `MainActivity` 先 `onResume`（挂上自己），开屏页随后才
     * `onPause`/`onDestroy`。若后者无条件 `hostActivity = null`，首页就会一直以为
     * 没有前台 Activity，激励/插屏/开屏全部拿不到宿主，只能等下一次切页才恢复。
     */
    fun detachActivity(activity: Activity) {
        if (hostActivity !== activity) return
        hostActivity = null
    }

    /**
     * 启动配置就位后刷新广告位与网关。
     *
     * 只在配置真变了时重建：`SideEffect` 每次组合都会调到这里，无条件重建会
     * 把已经 preload 好的缓存对象换掉，等于每一帧都丢一次广告缓存。
     */
    fun updateAdPlacements(value: AdPlacements) {
        if (value == adPlacements) return
        adPlacements = value
        // 第 4 个参数是激励请求的身份：写入由播放页决定（点"看广告解锁"时），网关在 load 前读一次。
        adGateway = AdProviders.create(appContext, value, { hostActivity }, RewardRequestContext::snapshot)
    }

    /** 用户刚同意广告隐私：重建网关让 SDK 立即生效（未同意时 create 内部降级为直投）。 */
    fun refreshAdGateway() {
        adGateway = AdProviders.create(appContext, adPlacements, { hostActivity }, RewardRequestContext::snapshot)
    }

    // 签名参数由拦截器统一注入，接口方法与仓库都不再感知 apikey/keytime
    private val apiClient = ApiClient(
        host = apiHost,
        signature = ApiSignature(secret = signatureSecret),
        // 只有 debug 包装配请求日志（BASIC 级别，Cookie 已 redact）：
        // release 里 enableLogging 为 false，不会把播放地址与会话写进日志。
        enableLogging = BuildConfig.DEBUG,
    )

    private val appContext = context.applicationContext

    private val sessionStore: SessionStore =
        PreferenceSessionStore(appContext.sessionDataStore)
    val searchHistoryStore: SearchHistoryStore = DefaultSearchHistoryStore(appContext.sessionDataStore)

    /** 皮肤本地选择：优先级高于服务端 `app_theme_ui`，见 [ThemePreference]。 */
    val themePreference: ThemePreference =
        DataStoreThemePreference(appContext.themeDataStore)

    /** cookie jar 里现有的会话（同步）；进"我的"页首帧用，避免先渲染成未登录。 */
    fun sessionSnapshot(): Session? = Session.fromCookies(apiClient.sessionCookies())

    /**
     * 登录态流：强制登录锁屏与各页面的"已登录"分支都读它。
     *
     * cookie jar 本身不可观察，所以登录成功、登出、冷启动恢复之后都要显式
     * [refreshSession] 推一次；不推的话界面会停在旧判断上（弹层里登录完，页面还是游客）。
     */
    private val loginState = MutableStateFlow(sessionSnapshot())
    val session: StateFlow<Session?> = loginState.asStateFlow()

    fun refreshSession() {
        loginState.value = sessionSnapshot()
    }

    /** 设备账号凭据（RDA）：随机 token 加密落盘 + 设备指纹作恢复锚。 */
    val deviceCredentials = DeviceCredentialStore(appContext)

    /**
     * 静默设备登录：没有会话、且用户没有主动登出过时调用。
     *
     * 只在后台配置了 `device` 登录方式时才该被调用（见 `StarveilNavHost`）。
     * 失败不抛异常：设备登录是"顺手把游客变成账号"，失败就当游客继续用。
     *
     * @return 是否拿到了会话
     */
    suspend fun silentDeviceSignIn(): Boolean = when {
        sessionSnapshot()?.isLoggedIn == true -> true
        // 用户主动退出过：不替他再建号，否则"退出登录"等于没退
        deviceCredentials.isSuppressed -> false
        else -> createDeviceSession()
    }

    /** 最近一次设备登录失败的原因（后端 msg），只用于给用户一句人话提示。 */
    private var deviceLoginFailure: String? = null
    /** 真正走一次设备登录。失败不抛：设备登录是"顺手把游客变成账号"，失败就当游客继续用。 */
    private suspend fun createDeviceSession(): Boolean {
        val token = deviceCredentials.loadOrCreate()
        val result = authRepository.deviceLogin(
            token = token,
            deviceName = deviceCredentials.deviceName(),
            recovery = deviceCredentials.recoveryId(),
        )
        return when (result) {
            is AppResult.Success -> {
                // 恢复场景下服务端会换发 token，旧的要作废
                deviceCredentials.save(result.value.token)
                refreshSession()
                true
            }

            is AppResult.Failure -> {
                // 后端拒绝时 msg 才是诊断依据（凭据无效 / 账号禁用 / 找回冷却），
                // 只留一句"登录失败"等于把线索全丢了
                deviceLoginFailure = (result.error as? AppError.Business)?.message
                false
            }
        }
    }

    /**
     * 用户主动点"设备一键登录"：先解除登出压制，否则 [silentDeviceSignIn] 会直接返回 false。
     *
     * 与静默登录分开命名，是为了让"用户点了按钮"和"冷启动顺手登录"在日志与测试里可区分。
     */
    suspend fun deviceSignInNow(notifyFailure: (String) -> Unit = {}): Boolean {
        deviceCredentials.clearSuppressed()
        deviceLoginFailure = null
        val signedIn = silentDeviceSignIn()
        if (!signedIn) notifyFailure(deviceLoginFailure ?: DEVICE_LOGIN_FAILED_MESSAGE)
        return signedIn
    }

    /** 登录成功：解除"登出后不再自动设备登录"的压制，并推一次会话流。 */
    fun onSignedIn() {
        deviceCredentials.clearSuppressed()
        // 换了账号：收藏/历史/积分这些带身份的快照立刻作废，避免新账号先看到旧账号的内容
        pageSnapshot.clearAllInBackground()
        // 收藏还有一份进程内副本，光清磁盘不会让它失效
        favoriteRepository.resetLocalCache()
        refreshSession()
    }

    /**
     * 退出登录后的本地收尾。
     *
     * 不压住的话，下次冷启动会立刻静默建号/登录，用户会觉得"登出没生效"。
     */
    fun onSignedOut() {
        deviceCredentials.suppress()

        // 内存里的资料缓存也要清：ProfileDestination / UserCenterDestination 都拿它当首帧
        // `initialProfile`，不清的话登出后重进还是上一个人的昵称与头像
        userRepository.clearCachedProfile()
        // 登出即清快照：否则下一个人（或下次登录的另一个账号）会先看到上一个人的收藏与历史
        pageSnapshot.clearAllInBackground()
        favoriteRepository.resetLocalCache()
        // jar 里那份还在的话 sessionSnapshot() 仍判成"已登录"，
        // 用户退登后点头像会被送进「个人资料」→ 看到"登录后查看资料"的空页
        apiClient.clearSession()
        refreshSession()
    }

    val authRepository: AuthRepository = DefaultAuthRepository(
        api = apiClient.api,
        cookies = { apiClient.sessionCookies() },
        store = sessionStore,
    )

    val startupRepository: StartupRepository = DefaultStartupRepository(api = apiClient.api)

    /** 首帧快照目录：首页冷启动先铺上一次的内容，网络回来再原地覆盖。 */
    private val snapshotStore = SnapshotStore(File(context.filesDir, "snapshots"))

    private val homeSnapshot = HomeSnapshotStore(snapshotStore)
    private val detailSnapshot = DetailSnapshotStore(snapshotStore)
    private val filterSnapshot = FilterSnapshotStore(snapshotStore)
    /** 通用页面快照（专题等非首页结构）；与首页那三份共用同一个目录。 */
    private val pageSnapshot = PageSnapshotStore(snapshotStore)

    val vodRepository: VodRepository = DefaultVodRepository(
        api = apiClient.api,
        typeApi = apiClient.typeApi,
        searchApi = apiClient.searchApi,
        homeApi = apiClient.homeApi,
        snapshot = homeSnapshot,
        detailSnapshot = detailSnapshot,
        filterSnapshot = filterSnapshot,
    )

    /** 首页「继续观看」：`v1.user/videoProgressList`，读 cuican_vlog 最近 10 条。 */
    val continueWatchingRepository: ContinueWatchingRepository =
        DefaultContinueWatchingRepository(api = apiClient.progressApi, snapshot = pageSnapshot)
    val favoriteRepository: FavoriteRepository = DefaultFavoriteRepository(
        api = apiClient.api,
        snapshot = pageSnapshot,
    )

    /**
     * 剧场页数据源：短剧就是 `v1.vod` 里后台指定的那个分类。
     *
     * 分类号走启动配置下发（`shortplay_type_id`），后台没配时由仓库回落到内置常量，
     * 所以换站只需要在后台填一次分类 id，不用改代码。
     */
    val shortPlayRepository: ShortPlayRepository = DefaultShortPlayRepository(
        vodRepository = vodRepository,
        snapshot = pageSnapshot,
        configuredTypeId = { startupRepository.cached()?.shortplayTypeId ?: 0 },
    )

    val userRepository: UserRepository = DefaultUserRepository(
        api = apiClient.api,
        profileApi = apiClient.profileApi,
    )

    /** 积分中心：签到与今日任务，积分本身存在 mac_user.user_points。 */
    val pointsRepository: PointsRepository = DefaultPointsRepository(
        api = apiClient.pointsApi,
        snapshot = pageSnapshot,
    )

    /** 反馈（留言板 v1.gbook）。 */
    val feedbackRepository: FeedbackRepository = DefaultFeedbackRepository(api = apiClient.gbookApi)

    val commentRepository: CommentRepository = DefaultCommentRepository(api = apiClient.commentApi)

    val danmuRepository: DanmuRepository = DefaultDanmuRepository(api = apiClient.cmsDanmakuApi)

    val topicRepository: TopicRepository = DefaultTopicRepository(
        api = apiClient.api,
        snapshot = pageSnapshot,
    )

    val liveRepository: LiveRepository = DefaultLiveRepository(
        api = apiClient.api,
        snapshot = pageSnapshot,
    )

    val rankRepository: RankRepository = DefaultRankRepository(
        api = apiClient.api,
        snapshot = pageSnapshot,
    )

    val accessRepository: AccessRepository = DefaultAccessRepository(api = apiClient.api)

    /** 直投激励视频解锁（`v1.ad/session` 起的三步）：判定全在服务端，客户端不写本地"已解锁"。 */
    val adUnlockRepository: AdUnlockRepository = DefaultAdUnlockRepository(api = apiClient.api)

    /** 播放位置读写：观看记录与断点续播共用（对外暴露，详情页与播放页都要用）。 */
    val playbackRepository: PlaybackRepository =
        DefaultPlaybackRepository(api = apiClient.api, progressApi = apiClient.progressApi)

    /** H5 支付地址的 host 由启动参数注入，不在 feature 层写死域名。 */
    val payRepository: PayRepository = DefaultPayRepository(
        api = apiClient.api,
        h5Host = apiHost,
        snapshot = pageSnapshot,
    )
    val withdrawRepository: WithdrawRepository = DefaultWithdrawRepository(
        api = apiClient.api,
        snapshot = pageSnapshot,
    )

    /**
     * 冷启动把持久化的会话 cookie 灌回内存 jar。
     *
     * 进程重启后 jar 是空的，不灌回去所有需要登录的接口都会返回「请先登录」，
     * 而本地会话明明还在（页面显示着昵称）。宿主在进入首页前调用一次。
     */
    suspend fun restoreSessionCookies() {
        val session = sessionStore.load() ?: return
        apiClient.restoreSessionCookies(session.toCookieMap())
        refreshSession()
    }

    fun clearSession() {
        apiClient.clearSession()
        refreshSession()
    }

    /** 弹幕数据源：把仓库包一层，feature 模块看不到 HTTP。 */
    private val danmuSource: DanmuSource = object : DanmuSource {
        override suspend fun load(target: DanmuTarget, atTimeMs: Long): AppResult<List<Danmu>> =
            withContext(Dispatchers.IO) { danmuRepository.list(target) }

        override suspend fun send(
            target: DanmuTarget,
            content: String,
            atTimeMs: Long,
            colorHex: String,
        ): AppResult<Unit> = withContext(Dispatchers.IO) {
            danmuRepository.send(target, content, atTimeMs, colorHex)
        }

        override suspend fun fetchOfficial(url: String): AppResult<List<Danmu>> =
            withContext(Dispatchers.IO) { AppResult.Success(apiClient.fetchOfficialDanmu(url)) }
    }

    /**
     * 组装一个播放页依赖。
     *
     * 线路探测走 [ApiClient.probePlayUrl]（旧 的 JSON 模式）：
     * 另外还有 WebView 嗅探分支，那套开了文件访问与证书放行，不迁。
     * 试看参数来自 `AccessRepository.checkTrySee`，由调用方（PlayerEntry）先取好传进来。
     */
    fun playerDeps(gate: TrySeeGate? = null): PlayerDeps = PlayerDeps(
        // 线路探测：纯 HTTP 优先，必要时用受限 WebView 兜底（放开的证书与文件访问已全部反转）。
        probe = RestrictedWebProbe(appContext, apiClient),
        progressSink = object : ProgressSink {
            override suspend fun reportWatchedSeconds(
                sourceIndex: Int,
                episodeIndex: Int,
                seconds: Long,
            ): AppResult<Unit> = playbackRepository.reportWatchedSeconds(seconds)

            override suspend fun savePosition(
                vodId: Int,
                sourceIndex: Int,
                episodeIndex: Int,
                positionSec: Int,
            ): AppResult<Unit> = playbackRepository.saveProgress(
                vodId = vodId,
                sourceIndex = sourceIndex,
                episodeIndex = episodeIndex,
                positionSec = positionSec,
            )
        },
        danmuSource = danmuSource,
        trySeeAccess = gate?.access ?: com.starveil.core.model.TrySeeAccess.FREE,
        trySeeMinutes = gate?.trySeeMinutes ?: 0,
    )
}

/** 设备登录失败的提示文案：后端拒绝与断网都归到这一句，不再静默。 */
private const val DEVICE_LOGIN_FAILED_MESSAGE = "设备登录失败，请稍后再试或用账号密码登录"
