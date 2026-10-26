package com.starveil.core.model

/**
 * 某部影片的播放断点（服务端 `cuican_vlog` 的一行）。
 *
 * `positionSec` 是播放位置，不是累计观看时长——续播要的是"看到哪"。
 * 0/0/0 表示没看过，调用方按从头播处理。
 */
data class VideoProgress(
    val sourceIndex: Int = 0,
    val episodeIndex: Int = 0,
    val positionSec: Int = 0,
) {
    /** 是否值得续播：太靠前（<10 秒）没必要跳，观感上等于从头。 */
    val resumable: Boolean get() = positionSec >= RESUME_MIN_SECONDS

    companion object {
        /** 低于这个秒数不续播，避免"点开就跳 2 秒"的怪异感。 */
        const val RESUME_MIN_SECONDS = 10
    }
}
