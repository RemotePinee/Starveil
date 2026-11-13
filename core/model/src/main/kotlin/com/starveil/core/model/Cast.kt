package com.starveil.core.model

/**
 * 局域网里可投屏的一台设备（DLNA MediaRenderer）。
 *
 * 只保留控制需要的东西：`controlUrl` 收 AVTransport 指令，`renderingControlUrl`
 * 收音量/静音指令。设备名来自它的 `friendlyName`，拿不到时用 IP 兜底。
 */
data class CastDevice(
    /** UDN，做去重用：同一台电视可能同时被 SSDP 与描述文档报到两次。 */
    val id: String,
    val name: String,
    val host: String,
    val controlUrl: String,
    val renderingControlUrl: String? = null,
)

/** 远端播放器状态，名字对齐 UPnP 的 `CurrentTransportState`。 */
enum class CastPlaybackState {
    Idle,
    Playing,
    Paused,
    Buffering,
    Stopped,
    Error,
}
