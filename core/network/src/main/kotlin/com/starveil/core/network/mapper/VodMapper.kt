package com.starveil.core.network.mapper

import com.starveil.core.network.dto.PlaySourceDto
import com.starveil.core.network.dto.PlayerInfoDto
import com.starveil.core.network.dto.VodDto
import com.starveil.core.network.dto.VodTypeDto
import com.starveil.core.network.dto.VodTypeExtendDto
import com.starveil.core.model.PlayerInfo
import com.starveil.core.model.PlaySource
import com.starveil.core.model.PlayUrl
import com.starveil.core.model.TypeExtend
import com.starveil.core.model.Vod
import com.starveil.core.model.VodType

/**
 * DTO -> 领域模型。必须显式映射，禁止 DTO 泄漏进 UI（AGENTS.md 第 4 节）。
 */
fun VodDto.toDomain(): Vod = Vod(
    id = id,
    name = name,
    poster = poster,
    remarks = remarks,
    year = year,
    area = area,
    score = score,
    blurb = blurb,
    playSources = playSources.mapIndexed { index, dto -> dto.toDomain(index) },
    typeId = typeId,
    typeName = typeName,
)

fun PlaySourceDto.toDomain(index: Int): PlaySource = PlaySource(
    // 用数组下标而不是后端 sid：后端 array_values 重排后二者可能不一致
    index = index,
    name = from,
    episodes = urls.map { it.toDomain() },
    playerInfo = playerInfo.toDomain(),
)

fun com.starveil.core.network.dto.PlayUrlDto.toDomain(): PlayUrl = PlayUrl(
    episodeNumber = nid,
    title = name,
    url = url,
)

fun PlayerInfoDto.toDomain(): PlayerInfo = PlayerInfo(
    // parse2 / features 是逗号分隔
    parseTargets = parse2.toDelimitedList(COMMA),
    directLinkPatterns = features.toDelimitedList(COMMA),
    // issethead / headers 是双竖线分隔，
    // 这两处与上面两个字段的分隔符不同，搬过来不能统一成逗号
    headerPatterns = issetHead.toDelimitedList(HEADER_SEPARATOR),
    headers = headers.toHeaderMap(),
)

private const val COMMA = ","
private const val HEADER_SEPARATOR = "||"

private fun String?.toDelimitedList(separator: String): List<String> =
    this?.split(separator)
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

/**
 * `"Referer=>https://a/||User-Agent=>Mozilla"` → map。
 *
 * 在 ：按 `=>` 拆开，**只保留正好两段**的条目，
 * 其它静默丢弃（后端拼错时不会崩）。这里保持一致。
 */
private fun String?.toHeaderMap(): Map<String, String> =
    toDelimitedList(HEADER_SEPARATOR)
        .mapNotNull { entry ->
            val parts = entry.split("=>")
            if (parts.size != 2) return@mapNotNull null
            parts[0].trim() to parts[1].trim()
        }
        .toMap()

/** 分类 DTO → 领域模型。 */
fun VodTypeDto.toDomain(): VodType = VodType(
    id = id,
    name = name,
    parentId = parentId,
    extend = extend.toDomain(),
)

/**
 * `extend` → 筛选维度。
 *
 * 三种降级都落成空列表：整块缺失（null）、空对象（四个键都没有）、
 * 单个键为 null。空列表表示"没有这一维"，筛选页整行不渲染。
 */
fun VodTypeExtendDto?.toDomain(): TypeExtend = TypeExtend(
    area = this?.area.orEmptyValues(),
    year = this?.year.orEmptyValues(),
    classNames = this?.classNames.orEmptyValues(),
    lang = this?.lang.orEmptyValues(),
)

/**
 * 去掉空串并去重（保留后端顺序）。
 *
 * 后端拼 `extend` 时常留一个空项（`"大陆/"`），照搬会在筛选行里渲染出一颗
 * 点不动的空白胶囊；重复项会让同一条件出现两次。
 */
private fun List<String>?.orEmptyValues(): List<String> =
    this.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
