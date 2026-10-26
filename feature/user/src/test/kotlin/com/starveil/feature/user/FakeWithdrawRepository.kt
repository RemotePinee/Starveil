package com.starveil.feature.user

import com.starveil.core.common.AppResult
import com.starveil.core.data.WithdrawRepository
import com.starveil.core.model.WithdrawChannel
import com.starveil.core.model.WithdrawTips
import kotlinx.coroutines.CompletableDeferred

/** 上传成功时服务端回的相对路径，与线上格式一致（`/upload/withdraw/...`）。 */
const val FAKE_QRCODE_PATH: String = "/upload/withdraw/12_1700000000.jpg"

/** 提现仓库假实现：可控结果 + 最近一次调用参数，便于断言"传了什么、有没有传"。 */
class FakeWithdrawRepository(
    var tipsResult: AppResult<WithdrawTips> = AppResult.Success(
        WithdrawTips(
            minAmountYuan = 20,
            availableYuan = 5000,
            ratioText = "当前可提现金额5000元，提现比例10金币=1元",
            hint = "最低提现20元",
        ),
    ),
    var applyResult: AppResult<String> = AppResult.Success("提交成功"),
    var uploadResult: AppResult<String> = AppResult.Success(FAKE_QRCODE_PATH),
) : WithdrawRepository {

    var applyCalls: Int = 0
    var lastApply: AppliedWithdraw? = null
    var uploadCalls: Int = 0
    var lastUpload: UploadedQrcode? = null

    /** 非空时上传挂在这个信号量上，用来观察"上传中"这一帧。 */
    var uploadGate: CompletableDeferred<AppResult<String>>? = null

    override suspend fun tips(): AppResult<WithdrawTips> = tipsResult

    override suspend fun apply(
        amount: Int,
        channel: WithdrawChannel,
        account: String,
        name: String,
        qrcode: String,
    ): AppResult<String> {
        applyCalls += 1
        lastApply = AppliedWithdraw(amount, channel, account, name, qrcode)
        return applyResult
    }

    override suspend fun uploadQrcode(
        bytes: ByteArray,
        fileName: String,
        mime: String,
    ): AppResult<String> {
        uploadCalls += 1
        lastUpload = UploadedQrcode(byteCount = bytes.size, fileName = fileName, mime = mime)
        return uploadGate?.await() ?: uploadResult
    }
}

/** 一次提现申请的全部入参，方便逐字段断言。 */
data class AppliedWithdraw(
    val amount: Int,
    val channel: WithdrawChannel,
    val account: String,
    val name: String,
    val qrcode: String,
)

/** 一次收款码上传的入参摘要：字节只留长度，断言里不塞二进制。 */
data class UploadedQrcode(
    val byteCount: Int,
    val fileName: String,
    val mime: String,
)
