package com.starveil.feature.user

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Context
import android.widget.Toast
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.PointsTask

/**
 * 积分中心：余额 + 签到 + 今日任务。
 *
 * 积分用途只有两个（见后端 `Payment::buyEpisode`）：买断单集、后台手动加减；
 * 开通会员走在线支付，不消耗积分——这一点写进底部说明，避免用户误以为能拿积分换会员。
 */
@Composable
fun PointsScreen(
    viewModel: PointsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 用系统 Toast，不用页内 Snackbar：签到/领取是"动作完成"的瞬时反馈，
    // 页内条会压住底部、还会跟着列表被挤动，看起来像页面出了问题。
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            PointsTopBar(onBack = onBack)
            LazyColumn(
                modifier = Modifier.weight(WEIGHT_FULL),
                contentPadding = PaddingValues(
                    start = Spacing.pageHorizontal,
                    end = Spacing.pageHorizontal,
                    top = Spacing.sm,
                    bottom = Spacing.xl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item {
                    PointsBalanceCard(
                        points = state.points,
                        signLabel = state.signLabel,
                        canSign = state.canSign,
                        onSign = viewModel::sign,
                    )
                }
                item { SectionTitle(text = TASK_TITLE) }
                items(items = state.tasks, key = { it.key }) { task ->
                    PointsTaskRow(
                        task = task,
                        submitting = state.submitting == task.key,
                        onClick = {
                            // 分享：先拉起系统分享面板，再上报领奖。Android 无法确认用户
                            if (task.key == SHARE_KEY) {
                                context.startActivity(
                                    shareIntent(title = state.shareTitle, url = state.shareUrl),
                                )
                                // 分享奖励走 `shareScore`（后端按天去重），和任务领取是两条路径
                                if (!task.claimed) viewModel.claim(task.key)
                            } else if (task.done) {
                                // 只有"做完待领"的任务才调 claim，已完成但没做完的按钮本来就是灰的
                                viewModel.claim(task.key)
                            }
                        },
                    )
                }
                item {
                    Text(
                        text = HINT,
                        style = MaterialTheme.typography.labelSmall,
                        color = starveilExtraColors.textFaint,
                    )
                }
            }
        }

    }
}

@Composable
private fun PointsTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = PhosphorIcons.Regular.ArrowLeft,
                contentDescription = "返回",
                tint = starveilExtraColors.textMuted,
                modifier = Modifier.size(IconSize.md).mirrorIconInRtl(),
            )
        }
        Text(
            text = PAGE_TITLE,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** 余额卡：大数字 + 右侧签到按钮（已签到时置灰）。 */
@Composable
private fun PointsBalanceCard(
    points: Int,
    signLabel: String,
    canSign: Boolean,
    onSign: () -> Unit,
) {
    Surface(
        shape = AppShape.large,
        color = starveilExtraColors.surfaceCard,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(WEIGHT_FULL)) {
                Text(
                    text = points.toString(),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = BALANCE_LABEL,
                    style = MaterialTheme.typography.labelMedium,
                    color = starveilExtraColors.textMuted,
                )
            }
            TextButton(onClick = onSign, enabled = canSign) { Text(text = signLabel) }
        }
    }
}

/** 一条任务：标题 + 可得积分 + 右侧"领取/未完成/已领取"。 */
@Composable
private fun PointsTaskRow(task: PointsTask, submitting: Boolean, onClick: () -> Unit) {
    Surface(
        shape = AppShape.medium,
        color = starveilExtraColors.surfaceCard,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(WEIGHT_FULL)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (task.points > 0) {
                    Text(
                        text = taskCaption(task),
                        style = MaterialTheme.typography.labelSmall,
                        color = starveilExtraColors.brandGold,
                    )
                }
            }
            // 操作区固定宽高：领取时换成转圈，尺寸不能跟着变，
            // 否则点一下卡片会缩一下、把下面的行整体顶上去。
            Box(
                modifier = Modifier
                    .width(ACTION_WIDTH)
                    .height(ACTION_HEIGHT),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(IconSize.item),
                        color = starveilExtraColors.textFaint,
                        strokeWidth = ACTION_STROKE,
                    )
                } else {
                    // 分享任务完成/领取后仍要能点：按钮还得负责拉起分享面板
                    TextButton(
                        onClick = onClick,
                        enabled = task.done || task.claimed || task.key == SHARE_KEY,
                    ) {
                        Text(
                            text = when {
                                task.done -> ACTION_CLAIM
                                task.claimed -> ACTION_CLAIMED
                                else -> NOT_READY
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 系统分享面板：文本 = 标题 + 落地链接；后台没配标题时用应用名。 */
private fun shareIntent(title: String, url: String): Intent {
    val subject = title.ifBlank { DEFAULT_SHARE_TITLE }
    val body = listOf(subject, url).filter { it.isNotBlank() }.joinToString(separator = "\n")
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    return Intent.createChooser(send, SHARE_CHOOSER_TITLE)
}


/** "已领取"三个字 + 按钮内边距，宽度不能再窄。 */
private val ACTION_WIDTH = Spacing.xxl * 2 + Spacing.lg

/** 与 TextButton 默认最小高度对齐：转圈与按钮互换时行高不变。 */
private val ACTION_HEIGHT = Spacing.xxl + Spacing.sm

/** 转圈描边：细一点，和 20dp 图标同量级。 */
private val ACTION_STROKE = Spacing.xxs
private const val PAGE_TITLE = "积分中心"
private const val BALANCE_LABEL = "可用积分"
private const val TASK_TITLE = "今日任务"
private const val ACTION_CLAIM = "领取"
private const val NOT_READY = "未完成"
private const val ACTION_CLAIMED = "已领取"

/** 任务副标题：有目标次数就带今日进度，"今日 1/3 · +3 积分"比只有分值清楚。 */
private fun taskCaption(task: PointsTask): String =
    if (task.target > 1) {
        "今日 ${task.progress}/${task.target} · +${task.points} 积分"
    } else {
        "+${task.points} 积分"
    }
private const val HINT = "积分可用于买断单集；开通会员走在线支付，不消耗积分。"
private const val WEIGHT_FULL = 1f

private const val DEFAULT_SHARE_TITLE = "Starveil"

private const val SHARE_CHOOSER_TITLE = "分享到"
