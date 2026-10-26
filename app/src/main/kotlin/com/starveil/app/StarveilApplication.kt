package com.starveil.app

import android.app.Application

/**
 * 应用入口。刻意保持极薄：
 * - 不做广告/统计/推送 SDK 的无条件初始化（安全基线要求用户同意后再初始化）
 * - 不在这里读本地配置或发网络请求
 */
class StarveilApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(
            context = this,
            apiHost = getString(R.string.starveil_api_host),
            // 接口签名密钥不得写进仓库（docs/security.md 第 2 节）。
            // 首期占位为空串：后端不校验该参数，接入真实密钥前不影响功能。
            signatureSecret = "",
        )
    }
}
