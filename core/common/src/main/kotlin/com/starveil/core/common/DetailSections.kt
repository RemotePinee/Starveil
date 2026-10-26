package com.starveil.core.common

/**
 * 详情页三个区块的开关，来自 `startup.detail_sections`。
 *
 * 放在 `:core:common` 而不是 `:core:model`：`detail` 页通过 designsystem 的
 * CompositionLocal 拿它，而 designsystem 只依赖 common。放 model 的话
 * designsystem 又要为了一个三布尔值反向依赖，方向就反了。
 *
 * 缺字段一律按「显示」处理：老后端不返回时行为与加开关之前完全一致。
 */
data class DetailSections(
    /** 底部「相关推荐」整块。 */
    val recommend: Boolean = true,
    /** 标题行右侧的评分数字。 */
    val score: Boolean = true,
    /** 「讨论」Tab 与评论列表。 */
    val comment: Boolean = true,
) {
    companion object {
        val ALL = DetailSections()
    }
}
