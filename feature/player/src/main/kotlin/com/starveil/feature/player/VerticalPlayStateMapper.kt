package com.starveil.feature.player

import com.starveil.core.model.ShortPlay
import com.starveil.core.model.Vod

internal fun verticalStateOf(vod: Vod): VerticalPlayUiState {
    val episodes = vod.playSources.getOrNull(VERTICAL_PLAY_SOURCE_INDEX)?.episodes.orEmpty()
        .mapIndexed { index, playUrl ->
            VerticalEpisode(
                index = index,
                number = playUrl.episodeNumber,
                title = playUrl.title.ifBlank { "第 ${playUrl.episodeNumber} 集" },
                url = playUrl.url,
            )
        }
    return VerticalPlayUiState(
        title = vod.name,
        cover = vod.poster,
        description = vod.blurb.orEmpty(),
        category = vod.typeName.orEmpty(),
        typeId = vod.typeId,
        episodes = episodes,
        isLoading = false,
        errorMessage = if (episodes.isEmpty()) MSG_NO_EPISODE else null,
    )
}

/** 首帧只复用展示字段，地址与权限仍等本次详情请求。 */
internal fun verticalInitialState(preview: ShortPlay?, cached: Vod?): VerticalPlayUiState = VerticalPlayUiState(
    title = preview?.title ?: cached?.name.orEmpty(),
    cover = preview?.cover ?: cached?.poster,
    description = preview?.blurb ?: cached?.blurb.orEmpty(),
    category = preview?.category ?: cached?.typeName.orEmpty(),
    typeId = cached?.typeId ?: 0,
    episodeCountHint = preview?.episodeCount
        ?: cached?.playSources?.getOrNull(VERTICAL_PLAY_SOURCE_INDEX)?.episodes?.size ?: 0,
)

internal fun VerticalPlayUiState.metadataOnly(): VerticalPlayUiState = VerticalPlayUiState(
    title = title, cover = cover, description = description, category = category, typeId = typeId,
    episodeCountHint = episodeCount,
)
