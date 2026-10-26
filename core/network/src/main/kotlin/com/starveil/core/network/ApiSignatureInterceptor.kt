package com.starveil.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 给每个请求补 apikey / keytime。
 *
 * 在 79 个接口方法里逐个手写这两个参数，
 * 漏一个就被后端当成未签名的请求。集中到拦截器后，接口定义只剩业务参数。
 *
 * 后端对这两个参数**不做任何校验**（见 docs/security.md 第 4 节），
 * 它们只是既有契约的一部分，不是安全机制，不要在上面做防刷。
 */
class ApiSignatureInterceptor(private val signature: ApiSignature) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // 已显式带签名的不重复追加，否则 URL 上会出现两组同名参数
        if (request.url.queryParameter(API_KEY_PARAM) != null) return chain.proceed(request)

        val sign = signature.current()
        val signedUrl = request.url.newBuilder()
            .addQueryParameter(API_KEY_PARAM, sign.apiKey)
            .addQueryParameter(KEY_TIME_PARAM, sign.keyTime)
            .build()
        return chain.proceed(request.newBuilder().url(signedUrl).build())
    }

    private companion object {
        const val API_KEY_PARAM = "apikey"
        const val KEY_TIME_PARAM = "keytime"
    }
}
