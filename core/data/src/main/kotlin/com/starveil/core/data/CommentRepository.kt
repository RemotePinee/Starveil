package com.starveil.core.data

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.common.map
import com.starveil.core.model.CommentPage
import com.starveil.core.network.CommentApi
import com.starveil.core.network.mapper.toDomain

/**
 * 影片评论（`GET/POST v1.comment`，服务端 `CommentController.php`）。
 *
 * 列表公开、发布需登录。仓库不做本地登录判定：未登录时后端回 `code = 401`，
 * 页面在点发送前先查会话，避免游客白跑一次请求并把「请先登录」误显示成网络错误。
 *
 * 两个方法都带默认实现——与 [VodRepository.types] 同一套务实约定：
 * 其它 feature 的测试 fake 实现本接口时只关心用到的方法，接口扩方法不会集体编译失败。
 */
interface CommentRepository {

    /** 未实现的 fake 返回空页，UI 显示空态而不是崩溃。 */
    suspend fun list(
        vodId: Int,
        page: Int = FIRST_PAGE,
        limit: Int = COMMENT_PAGE_SIZE,
    ): AppResult<CommentPage> = AppResult.Success(CommentPage())

    /** 未实现的 fake 视为失败：发布是显式动作，静默成功会让 UI 误报「已发布」。 */
    suspend fun post(vodId: Int, content: String): AppResult<Unit> =
        AppResult.Failure(AppError.Unknown(cause = null))
}

class DefaultCommentRepository(private val api: CommentApi) : CommentRepository {

    override suspend fun list(vodId: Int, page: Int, limit: Int): AppResult<CommentPage> =
        apiCall { api.list(vodId = vodId, page = page, limit = limit) }
            .requireData()
            .map { envelope -> envelope.toDomain() }

    override suspend fun post(vodId: Int, content: String): AppResult<Unit> =
        apiCall { api.post(vodId = vodId.toString(), content = content) }.requireSuccess()
}

/** 详情页评论区一页 20 条；后端上限 60，超过会被静默截断。 */
const val COMMENT_PAGE_SIZE = 20

private const val FIRST_PAGE = 1
