package com.starveil.core.network.mapper

import com.starveil.core.model.RankCatalog
import com.starveil.core.model.RankGroup
import com.starveil.core.model.Vod
import com.starveil.core.network.dto.RankBoardDto
import com.starveil.core.network.dto.RankCatalogDto
import com.starveil.core.network.dto.RankGroupDto

/**
 * 榜单 DTO -> 领域模型。
 *
 * `vodphb` 与 `vodPhbAll` 是两条独立契约（一个平铺、一个按分类分行），映射也分成两个入口，
 * 不合并：
 * - [RankBoardDto.toDomain] 只取 list，返回 `List<Vod>`；
 * - [RankCatalogDto.toDomain] 保留 `zhui` / `list` 两层，返回 [RankCatalog]。
 */
fun RankBoardDto.toDomain(): List<Vod> = list.map { it.toDomain() }

fun RankCatalogDto.toDomain(): RankCatalog = RankCatalog(
    promoted = promoted.map { it.toDomain() },
    // 后端偶尔回一条 vod_type_name 为空（或只有空白、typeId=0）的分组：
    // 分类标签没有文字，只剩一块底色，看着像"多出来一个圆点"。这类分组没有可读名字，
    // 也点不进去，直接在映射层丢掉，别让它流到 UI。
    groups = groups
        .filter { it.typeId > 0 && it.typeName.isNotBlank() }
        .map { it.toDomain() },
)

fun RankGroupDto.toDomain(): RankGroup = RankGroup(
    typeId = typeId,
    typeName = typeName,
    typeImage = typeImage,
    vods = vods.map { it.toDomain() },
)
