package com.starveil.core.data

import com.starveil.core.common.AppResult
import com.starveil.core.model.StartupConfig
import com.starveil.core.network.StarveilApi
import com.starveil.core.network.dto.StartupDto
import com.starveil.core.network.mapper.toDomain

/**
 * 启动配置来源。
 *
 * 与影片仓库分开：启动配置决定皮肤、Tab、广告位，生命周期是"每次冷启动一次"，
 * 和列表分页不是一类东西。
 */
interface StartupRepository {

    /**
     * @param localThemeOverride 本地皮肤覆盖值，非空时优先于服务端。
     */
    suspend fun load(localThemeOverride: String? = null): AppResult<StartupConfig>

    /**
     * 同步取上次成功拉到的配置（进程内缓存，**可能已过期**），没有则 null。
     *
     * 首页骨架要「第一帧就是最终布局」，不能等网络回来再重排；启动时已经预取过一次，
     * 通常能直接命中。过期与否交给 [load] 判断：首帧宁可用旧配置，也不要空白。
     */
    fun cached(): StartupConfig? = null

    /**
     * 作废进程内缓存的有效期：下一次 [load] 必回源。
     *
     * 只动"新不新"，不清 [cached] 的内容——首帧仍然用上次的配置起步，网络回来再原地覆盖。
     * 调用点：`MainActivity.onResume`（从后台回前台）与 `AppContainer.onSignedIn`。
     * 默认空实现：各 feature 的测试 fake 只关心 [load] 的返回值，不必跟着实现。
     */
    fun invalidate() {}
}

class DefaultStartupRepository(
    private val api: StarveilApi,
    /** 取当前时刻，只用来判 [CACHE_TTL_MS]；提成参数是为了测试能换成假时钟。 */
    private val nowMs: () -> Long = System::currentTimeMillis,
) : StartupRepository {

    /** 上次成功的原始响应；皮肤覆盖只在映射时生效，所以缓存 DTO 而不是映射结果。 */
    @Volatile
    private var cachedDto: StartupDto? = null

    /** [cachedDto] 写入的时刻，与它成对读写。 */
    @Volatile
    private var cachedAtMs: Long = 0L

    override suspend fun load(localThemeOverride: String?): AppResult<StartupConfig> {
        freshCached()?.let { return AppResult.Success(it.toDomain(localThemeOverride)) }
        return apiCall { api.startup() }
            .flatMap { envelope ->
                envelope.data
                    ?.let { dto ->
                        remember(dto)
                        AppResult.Success(dto.toDomain(localThemeOverride))
                    }
                    ?: businessFailure(envelope.code, envelope.msg)
            }
    }

    // 皮肤覆盖由调用方在映射时决定，这里只给服务端原值
    override fun cached(): StartupConfig? = cachedDto?.toDomain()

    override fun invalidate() {
        // 只把时间戳归零：内容留着给 cached() 的首帧用，load() 会因"太久没更新"回源
        cachedAtMs = 0L
    }

    /**
     * 只有 [CACHE_TTL_MS] 内的缓存才顶替网络请求。
     *
     * `forceLogin`、广告位、`loginMethods` 都是后台可改项，缓存一场进程生命周期的后果是
     * "后台关了强制登录，用户必须杀进程才生效"。时钟回拨按已过期处理，宁可多一次请求。
     */
    private fun freshCached(): StartupDto? =
        cachedDto?.takeIf { nowMs() - cachedAtMs in 0 until CACHE_TTL_MS }

    private fun remember(dto: StartupDto) {
        cachedAtMs = nowMs()
        cachedDto = dto
    }

    private companion object {
        /** 进程内缓存寿命：5 分钟。切前台另有主动 [invalidate]，这里只是兜底。 */
        const val CACHE_TTL_MS = 5L * 60 * 1000
    }
}
