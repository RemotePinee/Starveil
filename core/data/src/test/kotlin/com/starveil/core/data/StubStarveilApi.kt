package com.starveil.core.data

import com.starveil.core.network.StarveilApi
import com.starveil.core.network.dto.AdCompleteDto
import com.starveil.core.network.dto.AdProgressDto
import com.starveil.core.network.dto.AdSessionDto
import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.AvatarDto
import com.starveil.core.network.dto.BuyCardResultDto
import com.starveil.core.network.dto.FavoriteDto
import com.starveil.core.network.dto.GoldTipDto
import com.starveil.core.network.dto.GroupUpgradeDto
import com.starveil.core.network.dto.LiveDto
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
import com.starveil.core.network.dto.LivePageDto
import okhttp3.MultipartBody
import kotlinx.serialization.json.JsonElement

/**
 * 测试用 `StarveilApi` 空实现：每个方法默认抛 `UnsupportedOperationException`，
 * 单个测试只 override 自己关心的那几个。
 *
 * 目的是让"新增接口方法"只改这一处，而不是在每个手写 fake 里重复十几个 stub。
 * 异常消息固定 `not used in this test`，误调用时一眼能看出是测试脚手架问题。
 */
open class StubStarveilApi : StarveilApi {

    override suspend fun startup(): ApiEnvelopeDto<StartupDto> = unused()

    override suspend fun login(userName: String, password: String): ApiEnvelopeDto<JsonElement> = unused()

    override suspend fun listVod(
        type: Int,
        page: Int,
        limit: Int,
        classFilter: String,
        area: String,
        year: String,
        lang: String,
        by: String,
    ): ApiEnvelopeDto<PageDto<VodDto>> = unused()

    override suspend fun vodDetail(vodId: Int, relLimit: Int): ApiEnvelopeDto<VodDto> = unused()

    override suspend fun register(
        userName: String,
        password: String,
        passwordConfirm: String,
        uid: String,
        code: String,
    ): ApiEnvelopeDto<JsonElement> = unused()

    override suspend fun sendRegisterCode(phone: String, ac: String): ApiEnvelopeDto<String> = unused()

    override suspend fun resetPassword(
        phone: String,
        password: String,
        passwordConfirm: String,
        code: String,
        ac: String,
        type: String,
    ): ApiEnvelopeDto<String> = unused()

    override suspend fun sendResetCode(phone: String, ac: String): ApiEnvelopeDto<String> = unused()

    override suspend fun userInfo(): ApiEnvelopeDto<UserInfoDto> = unused()

    override suspend fun favoriteList(
        page: Int,
        limit: Int,
        ulogType: Int,
    ): ApiEnvelopeDto<PageDto<FavoriteDto>> = unused()

    override suspend fun favoriteAdd(
        vodId: String,
        moduleId: String,
        type: String,
    ): ApiEnvelopeDto<String> = unused()

    override suspend fun favoriteRemove(ids: String, type: String): ApiEnvelopeDto<String> = unused()

    override suspend fun addWatchTime(viewSeconds: Int): ApiEnvelopeDto<ScoreDto> = unused()

    override suspend fun topicList(): ApiEnvelopeDto<PageDto<TopicDto>> = unused()

    override suspend fun topicDetail(topicId: String): ApiEnvelopeDto<TopicDetailDto> = unused()

    override suspend fun liveList(page: Int, limit: Int, group: String): ApiEnvelopeDto<LivePageDto> = unused()

    override suspend fun vodRank(
        orderBy: String,
        type: Int,
        page: Int,
        limit: Int,
    ): ApiEnvelopeDto<RankBoardDto> = unused()

    override suspend fun vodRankAll(): ApiEnvelopeDto<RankCatalogDto> = unused()

    override suspend fun checkVodTrySee(
        vodId: String,
        episodeNumber: String,
        moduleId: String,
    ): ApiEnvelopeDto<TrySeeDto> = unused()

    override suspend fun memberGroups(): ApiEnvelopeDto<MemberGroupCatalogDto> = unused()

    override suspend fun createOrder(price: String, payment: String): ApiEnvelopeDto<OrderDto> = unused()

    override suspend fun payTip(): ApiEnvelopeDto<PayTipDto> = unused()

    override suspend fun goldTip(): ApiEnvelopeDto<GoldTipDto> = unused()

    override suspend fun buyCard(cardPwd: String): ApiEnvelopeDto<BuyCardResultDto> = unused()

    override suspend fun buyEpisode(fields: Map<String, String>): ApiEnvelopeDto<String> = unused()

    override suspend fun orderStatus(orderCode: String): ApiEnvelopeDto<OrderStatusDto> = unused()

    override suspend fun upgradeGroup(
        period: String,
        groupId: String,
        orderCode: String,
    ): ApiEnvelopeDto<GroupUpgradeDto> = unused()

    override suspend fun withdrawApply(
        amount: String,
        channel: String,
        account: String,
        realName: String,
        qrcode: String?,
    ): ApiEnvelopeDto<WithdrawApplyResultDto> = unused()

    override suspend fun uploadWithdrawQrcode(file: MultipartBody.Part): ApiEnvelopeDto<AvatarDto> =
        unused()

    override suspend fun adSession(
        vodId: String,
        episodeNumber: String,
    ): ApiEnvelopeDto<AdSessionDto> = unused()

    override suspend fun adProgress(token: String, positionSec: Int): ApiEnvelopeDto<AdProgressDto> =
        unused()

    override suspend fun adComplete(token: String): ApiEnvelopeDto<AdCompleteDto> = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("not used in this test")
}
