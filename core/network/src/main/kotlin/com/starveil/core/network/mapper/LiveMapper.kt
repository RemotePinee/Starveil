package com.starveil.core.network.mapper

import com.starveil.core.model.LiveChannel
import com.starveil.core.network.dto.LiveDto

/**
 * 直播 DTO -> 领域模型。
 *
 * 后端 bean 的 `img` / `url` 换成 [LiveChannel.cover] / [LiveChannel.url]：
 * 后端给什么用什么，旧直播页没有做相对路径补全，也没有二次签名
 */
fun LiveDto.toDomain(): LiveChannel = LiveChannel(
    id = id,
    name = name,
    cover = img,
    url = url,
    group = group,
)
