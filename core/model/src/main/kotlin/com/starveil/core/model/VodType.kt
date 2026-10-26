package com.starveil.core.model

/**
 * 影片分类（后端 `v1.vod/types`）。
 *
 * 首页顶部 Tab 用它渲染；`id = 0` 表示"首页/全部"，不参与请求过滤。
 */
data class VodType(
    val id: Int,
    val name: String,
    val parentId: Int = 0,
    /**
     * 该分类可用的筛选维度（`v1.vod/types` 新增的 `extend`）。
     *
     * 老后端不返回这个字段，所以必须给默认值：`VodType(id, name)` 这类
     * 已有调用一个都不用改。
     */
    val extend: TypeExtend = TypeExtend(),
)

/**
 * 分类的筛选维度，对应后端 `extend: {area, year, class, lang}`。
 *
 * 后端可能整个缺省、回空对象、或只给其中几个键，因此四个维度都用空列表兜底：
 * 空列表 = 该分类没有这一维，片库筛选页对应的整行不渲染。
 */
data class TypeExtend(
    val area: List<String> = emptyList(),
    val year: List<String> = emptyList(),
    /** 后端键名是 `class`（Kotlin 关键字），这里改叫 `classNames`。 */
    val classNames: List<String> = emptyList(),
    val lang: List<String> = emptyList(),
) {
    /** 四个维度全空：筛选页只剩「分类」「排序」两行。 */
    val isEmpty: Boolean
        get() = listOf(area, year, classNames, lang).all { it.isEmpty() }
}
