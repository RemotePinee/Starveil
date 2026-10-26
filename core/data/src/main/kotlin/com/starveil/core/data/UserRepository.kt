package com.starveil.core.data

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.common.map
import com.starveil.core.model.UserProfile
import com.starveil.core.network.ProfileApi
import com.starveil.core.network.StarveilApi
import com.starveil.core.network.mapper.toDomain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 用户资料。
 *
 * 契约来自旧 ：`GET v1.user/detail`，无参数，
 * 身份靠 cookie 里的 user_id / user_check 判定（会话见 [com.starveil.core.model.Session]）。
 *
 * 未登录时后端返回 `code=403`、`msg="您未登陆"`，必须作为
 * [com.starveil.core.common.AppError.Business] 交给 UI 引导登录，不要当网络错误重试
 * （旧 就是在这里弹登录框的）。
 */
interface UserRepository {

    suspend fun loadProfile(): AppResult<UserProfile>

    /** 上一次成功拉到的资料，进程内缓存；没拉到过返回 null。 */
    fun cachedProfile(): UserProfile? = null

    /**
     * 丢掉进程内的资料缓存。
     *
     * 登出必须调用：页面拿 [cachedProfile] 当首帧 `initialProfile`，留着就是
     * "B 登进来先看到 A 的昵称与头像"。给默认实现是为了其它模块手写的测试 fake
     * 不用跟着改——接口加抽象方法会让它们集体编译失败（本项目踩过）。
     */
    fun clearCachedProfile() {}

    /**
     * 改昵称（插件 `UserController::postIndex`）。
     *
     * 插件白名单只有 `user_nick_name` / `user_portrait` 两列，其它字段传了也会被丢掉。
     * 成功后顺手重拉一次资料，返回值就是最新资料。
     */
    suspend fun updateNickName(nickName: String): AppResult<UserProfile> = unsupported()

    /**
     * 上传头像（插件 `UploadController::postUser`），返回站内相对路径（`/upload/avatar/...`）。
     *
     * 服务端上传成功即把路径写进 `user_portrait`，调用方拿到返回值后再 [loadProfile] 就能读到新头像。
     */
    suspend fun uploadAvatar(bytes: ByteArray, fileName: String, mime: String): AppResult<String> =
        unsupported()
}

class DefaultUserRepository(
    private val api: StarveilApi,
    private val profileApi: ProfileApi? = null,
) : UserRepository {

    @Volatile
    private var lastProfile: UserProfile? = null

    override suspend fun loadProfile(): AppResult<UserProfile> {
        val result = apiCall { api.userInfo() }.requireData().map { it.toDomain() }
        if (result is AppResult.Success) lastProfile = result.value
        return result
    }

    override fun cachedProfile(): UserProfile? = lastProfile

    override fun clearCachedProfile() {
        lastProfile = null
    }

    override suspend fun updateNickName(nickName: String): AppResult<UserProfile> {
        val api = profileApi ?: return unsupported()
        return when (val update = apiCall { api.updateNickName(nickName) }.requireData()) {
            is AppResult.Success -> loadProfile()
            is AppResult.Failure -> update
        }
    }

    override suspend fun uploadAvatar(
        bytes: ByteArray,
        fileName: String,
        mime: String,
    ): AppResult<String> {
        val profileApi = profileApi ?: return unsupported()
        val part = MultipartBody.Part.createFormData(
            name = AVATAR_PART_NAME,
            filename = fileName,
            body = bytes.toRequestBody(mime.toMediaType()),
        )
        return when (val result = apiCall { profileApi.uploadAvatar(part) }.requireData()) {
            is AppResult.Success -> AppResult.Success(result.value.file)
            is AppResult.Failure -> result
        }
    }
}

/** 插件读的是 `$_FILES['file']`，字段名不能改。 */
private const val AVATAR_PART_NAME = "file"

/** 给 fake / 未接 [ProfileApi] 的调用方用的占位错误。 */
private const val UNSUPPORTED_CODE = -1
private const val UNSUPPORTED_MESSAGE = "当前实现不支持该操作"

private fun unsupported(): AppResult.Failure =
    AppResult.Failure(AppError.Business(UNSUPPORTED_CODE, UNSUPPORTED_MESSAGE))
