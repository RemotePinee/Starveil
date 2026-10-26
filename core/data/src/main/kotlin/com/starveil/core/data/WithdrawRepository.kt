package com.starveil.core.data

import com.starveil.core.common.AppResult
import com.starveil.core.common.AppError
import com.starveil.core.common.map
import com.starveil.core.common.onSuccess
import com.starveil.core.model.WithdrawChannel
import com.starveil.core.model.WithdrawTips
import com.starveil.core.network.StarveilApi
import com.starveil.core.network.dto.GoldTipDto
import com.starveil.core.network.mapper.toWithdrawTips
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 金币提现仓库：说明文案 + 提交申请。
 *
 * 把两件事写死在提现页里：
 * `getGlodTip()`（`:97-108`）只把 `info` 原样贴到 TextView，
 * `withdraw()`（`:167-183`）做四次判空后直接下发，失败分支是空的。
 * 把判空留在 UI 状态（金额整数、门槛、账号姓名），仓库只负责请求与错误收敛。
 */
interface WithdrawRepository {

    /**
     * 提现说明（`GET v1.user/goldTip`）。
     *
     * 后端把两个数字写在中文文案里：`msg` = "最低提现20元"、
     * `info` = "当前可提现金额5000元，提现比例10金币=1元"。解析交给
     * `:core:network` 的 `toWithdrawTips()`，解析不到按 0 降级，不算失败。
     */
    suspend fun tips(): AppResult<WithdrawTips>

    /**
     * 提交提现申请（`POST v1.user/goldWithdrawApply`）。
     *
     * 真实字段比任务描述多一个 [channel]：旧接口四个表单字段
     * `num`（金额，元）`type`（1 支付宝 / 2 微信）`account` `realname`
     * 少了渠道后端无法打款，所以按真实签名给出。
     *
     * @param amount 提现金额，单位元，必须是正整数
     * @param channel 收款渠道，下发时取 [WithdrawChannel.code]
     * @param account 收款账号（支付宝账号 / 微信号）
     * @param name 收款姓名，后端要求实名
     * @param qrcode 收款码相对路径（`/upload/withdraw/...`），空串表示不下发该字段
     * @return 成功时是后端提示（`data.info`，线上是"提交成功"），只代表"已提交待审核"
     */
    suspend fun apply(
        amount: Int,
        channel: WithdrawChannel,
        account: String,
        name: String,
        qrcode: String = "",
    ): AppResult<String>

    /**
     * 上传收款码（`POST v1.upload/withdraw`，multipart 字段名 `file`），返回站内相对路径。
     *
     * 传字节而不是 uri：读相册要走 `ContentResolver`，而仓库构造只拿得到 `StarveilApi`
     * （`:app` 的 AppContainer 注入，没有 Context），与头像上传
     * （[UserRepository.uploadAvatar]）保持同一套路——字节在页面侧读，仓库只管上传。
     *
     * 默认实现返回失败，好让其它模块的手写 fake 不必跟着改；真正接上传的仓库覆盖它。
     */
    suspend fun uploadQrcode(bytes: ByteArray, fileName: String, mime: String): AppResult<String> =
        AppResult.Failure(AppError.Business(UNSUPPORTED_CODE, UNSUPPORTED_QRCODE_MESSAGE))

    /**
     * 上次成功响应的提现说明快照（冷启动首帧用）。
     *
     * 给默认实现：其它模块手写的 fake 不必为快照额外实现一遍。
     */
    fun cachedTips(): WithdrawTips? = null
}

/**
 * 仓库实现。请求失败一律由 [apiCall] 收敛成 `AppResult.Failure`，
 * 业务失败（`code != 200`）由 [requireData] 转成 [com.starveil.core.common.AppError.Business]。
 *
 * 无状态、可复用，构造只依赖 [StarveilApi] 与可选的快照存储，由 `:app` 的 AppContainer 注入。
 *
 * @param snapshot 提现说明首帧快照；为 null 时不落盘（测试与其它入口构造本类时不必提供）。
 */
class DefaultWithdrawRepository(
    private val api: StarveilApi,
    private val snapshot: PageSnapshotStore? = null,
) : WithdrawRepository {

    // 提现说明是配置文案：跟着登录态走，不按年龄失效
    override fun cachedTips(): WithdrawTips? =
        snapshot?.read(
            PageSnapshotStore.KEY_WITHDRAW,
            serializer<GoldTipDto>(),
            SnapshotStore.KEEP_FOREVER,
        )?.toWithdrawTips()

    override suspend fun tips(): AppResult<WithdrawTips> =
        apiCall { api.goldTip() }
            .requireData()
            .onSuccess { tip -> snapshot?.write(PageSnapshotStore.KEY_WITHDRAW, serializer<GoldTipDto>(), tip) }
            .map { tip -> tip.toWithdrawTips() }

    override suspend fun apply(
        amount: Int,
        channel: WithdrawChannel,
        account: String,
        name: String,
        qrcode: String,
    ): AppResult<String> = apiCall {
        api.withdrawApply(
            amount = amount.toString(),
            channel = channel.code.toString(),
            account = account,
            realName = name,
            qrcode = qrcode.ifBlank { null },
        )
    }.requireData()
        .map { result -> result.info.ifBlank { DEFAULT_SUBMITTED_MESSAGE } }

    /**
     * 收款码上传：响应体与头像同构，路径原样回给页面，提交时作为 `qrcode` 下发。
     * 回空路径按业务失败处理——否则页面会显示"已上传"却提交不了。
     */
    override suspend fun uploadQrcode(
        bytes: ByteArray,
        fileName: String,
        mime: String,
    ): AppResult<String> {
        val part = MultipartBody.Part.createFormData(
            name = QRCODE_PART_NAME,
            filename = fileName,
            body = bytes.toRequestBody(mime.toMediaType()),
        )
        return when (val result = apiCall { api.uploadWithdrawQrcode(part) }.requireData()) {
            is AppResult.Success -> result.value.file.takeIf { it.isNotBlank() }
                ?.let { path -> AppResult.Success(path) }
                ?: businessFailure(SUCCESS_CODE, EMPTY_QRCODE_MESSAGE)

            is AppResult.Failure -> result
        }
    }
}

/** `data.info` 为空时的兜底文案：请求成功就说明申请已落库，别让页面陷入"未知结果"。 */
private const val DEFAULT_SUBMITTED_MESSAGE = "提交成功，等待审核"

/** multipart 字段名：与头像上传一致，服务端只认 `file`。 */
private const val QRCODE_PART_NAME = "file"

/** 上传接口回空路径时的提示：这次上传等于白做，不能让页面显示"已上传"。 */
private const val EMPTY_QRCODE_MESSAGE = "收款码上传失败，请重试"

/** 没接上传实现的仓库（手写 fake）的默认失败文案。 */
private const val UNSUPPORTED_QRCODE_MESSAGE = "当前版本不支持收款码上传"

/** 本地"该实现没接这个能力"的业务码，与 [UserRepository] 的 unsupported() 保持一致。 */
private const val UNSUPPORTED_CODE = -1
