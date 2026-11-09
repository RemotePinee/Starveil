package com.starveil.feature.shortplay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.starveil.core.designsystem.component.PosterImage
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.starveil.core.designsystem.component.BlurredCoverBackdrop
import com.starveil.core.designsystem.component.imagePlate
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.ShortPlay

/**
 * 视频出画前的全屏封面；元数据与播放入口完全交给 app 注入的播放器。
 *
 * 走 [BlurredCoverBackdrop]：与视频层那条底用**同一个函数、同一个 URL**
 * （128px 解码 + `ImageBlurTransformation`），请求尺寸、变换与缓存键完全一致，
 * Coil 内存缓存直接命中。「切剧第一帧只能是模糊的」也在那里实现：命中就同步画，
 * 没命中只铺打底色，不会拿原图或上一部剧的封面顶上去。
 */
@Composable
internal fun ShortPlayBackdrop(play: ShortPlay) {
    BlurredCoverBackdrop(cover = play.cover, modifier = Modifier.fillMaxSize())
}

/**
 * 分类页网格里的一格：竖版封面 + 剧名 + 集数。
 *
 * 剧场页是全屏播放的一屏一条，没有可以复用的"卡片"；分类页是浏览态，
 * 所以卡片与它放在同一个文件里，封面比例沿用首页海报墙那一档。
 */
@Composable
internal fun ShortPlayPosterCard(
    play: ShortPlay,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        PosterImage(
            url = play.cover,
            contentDescription = play.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(POSTER_RATIO),
            shape = AppShape.small,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = play.title,
            style = MaterialTheme.typography.bodyMedium,
            // 分类页是普通页面（不压画面），标题跟主题走；浅色主题下写死白字会整行看不见
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (play.episodeCount > 0) {
            Text(
                text = "共 ${play.episodeCount} 集",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 竖版封面比例：短剧按竖屏出图，与首页海报墙同一档。 */
private const val POSTER_RATIO = 0.66f
