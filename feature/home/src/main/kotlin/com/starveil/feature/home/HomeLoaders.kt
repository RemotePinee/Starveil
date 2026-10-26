package com.starveil.feature.home

import com.starveil.core.common.AppResult
import com.starveil.core.data.ContinueWatchingRepository
import com.starveil.core.data.LiveRepository
import com.starveil.core.data.TopicRepository
import com.starveil.core.data.VodRepository
import com.starveil.core.model.ContinueWatching
import com.starveil.core.model.HomeModule
import com.starveil.core.model.LiveChannel
import com.starveil.core.model.Topic
import com.starveil.core.model.VodType

/**
 * 首页各内容位的取数与降级。
 *
 * 从 [HomeViewModel] 抽出来只为把状态持有者留在 detekt 的类体积阈值内：
 * 这些辅助函数不碰任何实例状态，输入只有仓库与后台模块配置；
 * 失败一律落成"这一块没有内容"，既不抛错，也不影响别的模块。
 */

/** 每日推荐：开关关着连请求都不发；条数只用于顶栏红点。 */
internal suspend fun VodRepository.homePushCount(pushEnabled: Boolean): Int =
    if (pushEnabled) (push() as? AppResult.Success)?.value ?: 0 else 0

/** 专题：模块没开就不请求；失败当"没有专题"，不整页报错。 */
internal suspend fun TopicRepository.homeTopics(modules: List<HomeModule>): List<Topic> =
    if (HomeModule.TOPIC in modules) {
        (list() as? AppResult.Success)?.value.orEmpty()
    } else {
        emptyList()
    }

/** 直播：同上，失败只是少一块横滑。 */
internal suspend fun LiveRepository.homeLives(modules: List<HomeModule>): List<LiveChannel> =
    if (HomeModule.LIVE in modules) {
        (list() as? AppResult.Success)?.value?.items.orEmpty()
    } else {
        emptyList()
    }

/**
 * 继续观看：只有 `home_modules` 含 continue 才请求。
 *
 * 未登录 / 网络失败一律当"没有记录"——首页正文不该因为这条辅助数据整体报错。
 */
internal suspend fun ContinueWatchingRepository.homeContinueWatching(
    modules: List<HomeModule>,
): List<ContinueWatching> =
    if (HomeModule.CONTINUE in modules) {
        (recent() as? AppResult.Success)?.value.orEmpty()
    } else {
        emptyList()
    }

/** 分类失败不阻断首页：只是没有 Tab，退回"首页"单选。 */
internal suspend fun VodRepository.homePrimaryTypes(): List<VodType> {
    val result = types()
    // 分类行只放一级分类；子分类（几十个"子类N"）留给筛选页，否则会横滑到没边
    return if (result is AppResult.Success) result.value.filter { it.parentId == 0 } else emptyList()
}
