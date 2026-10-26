package com.starveil.core.network

import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.AvatarDto
import kotlinx.serialization.json.JsonElement
import okhttp3.MultipartBody
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/**
 * 账号资料的写接口，单独成一个接口而不是塞进 [StarveilApi]：
 * 后者被十几处测试 fake 实现，每加一个方法都要改一圈 fake，这里独立出来互不影响。
 */
interface ProfileApi {

    /** 改昵称：插件白名单只有 `user_nick_name` / `user_portrait`，其余字段传了会被丢掉。 */
    @FormUrlEncoded
    @POST(ApiPaths.USER_PROFILE)
    suspend fun updateNickName(
        @Field("user_nick_name") nickName: String,
    ): ApiEnvelopeDto<JsonElement>

    /** 上传头像：multipart 字段名固定 `file`，服务端顺手把新路径写进 `user_portrait`。 */
    @Multipart
    @POST(ApiPaths.USER_AVATAR)
    suspend fun uploadAvatar(
        @Part file: MultipartBody.Part,
    ): ApiEnvelopeDto<AvatarDto>
}
