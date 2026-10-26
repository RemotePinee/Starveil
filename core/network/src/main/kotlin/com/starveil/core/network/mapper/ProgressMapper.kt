package com.starveil.core.network.mapper

import com.starveil.core.model.ContinueWatching
import com.starveil.core.network.dto.VideoProgressDto

/** 播放进度 DTO → 领域模型。下标与秒数原样搬运，展示规则在 [com.starveil.core.model.ContinueWatchingRules]。 */
fun VideoProgressDto.toDomain(): ContinueWatching = ContinueWatching(
    vodId = vodId,
    vodName = vodName,
    vodPic = vodPic?.takeIf { it.isNotBlank() },
    sourceIndex = sourceIndex,
    episodeIndex = episodeIndex,
    progressSec = progressSec,
    updatedAt = updatedAt,
    // 类型名优先，缺失时退回分类串：短剧分流（竖屏播放器）靠它判断
    typeName = typeName?.takeIf { it.isNotBlank() } ?: vodClass,
)
