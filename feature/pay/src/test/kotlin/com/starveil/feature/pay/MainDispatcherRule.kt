package com.starveil.feature.pay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * `viewModelScope` 固定跑在 Main 上，JVM 测试里必须替换主调度器，
 * 否则协程根本不会执行（表现为状态永远停在 Loading）。
 *
 * 与 `feature/home`、`feature/user` 各有一份：feature 之间不允许互相依赖
 * （AGENTS.md 第 1 节），所以测试脚手架只能各自复制。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}
