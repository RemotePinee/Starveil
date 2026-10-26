package com.starveil.feature.user

import androidx.compose.runtime.Immutable
import com.starveil.core.model.ContinueWatching

/** 观看记录：`v1.user/videoProgressList` 最近 10 条（服务端固定条数）。 */
@Immutable
data class HistoryUiState(
    val items: List<ContinueWatching> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
) {

    val isEmpty: Boolean get() = !isLoading && errorMessage == null && items.isEmpty()
}
