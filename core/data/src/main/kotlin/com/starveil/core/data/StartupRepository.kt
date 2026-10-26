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
     * 同步取上次成功拉到的配置（进程内缓存），没有则 null。
     *
     * 首页骨架要「第一帧就是最终布局」，不能等网络回来再重排；启动时已经预取过一次，
     * 通常能直接命中。
     */
    fun cached(): StartupConfig? = null
}

class DefaultStartupRepository(
    private val api: StarveilApi,
) : StartupRepository {

    /** 上次成功的原始响应；皮肤覆盖只在映射时生效，所以缓存 DTO 而不是映射结果。 */
    @Volatile
    private var cachedDto: StartupDto? = null

    override suspend fun load(localThemeOverride: String?): AppResult<StartupConfig> {
        cachedDto?.let { return AppResult.Success(it.toDomain(localThemeOverride)) }
        return apiCall { api.startup() }
            .flatMap { envelope ->
                envelope.data
                    ?.let { dto ->
                        cachedDto = dto
                        AppResult.Success(dto.toDomain(localThemeOverride))
                    }
                    ?: businessFailure(envelope.code, envelope.msg)
            }
    }

    // 皮肤覆盖由调用方在映射时决定，这里只给服务端原值
    override fun cached(): StartupConfig? = cachedDto?.toDomain()
}
