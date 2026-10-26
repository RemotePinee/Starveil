package com.starveil.feature.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.VodType

/**
 * 顶部频道 Tab。
 *
 * 首项固定"首页"（typeId=0，表示不过滤），其后是后端分类。
 * 选中项加粗 + 底部短横线，未选中降到次级文字色；横滑而不是等分，
 * 频道多时不会把文字挤成两行。
 */
@Composable
fun HomeTabs(
    types: List<VodType>,
    selectedTypeId: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = listOf(VodType(id = 0, name = "首页")) + types
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        items(items = entries, key = { it.id }) { type ->
            TabItem(
                name = type.name,
                selected = type.id == selectedTypeId,
                onClick = { onSelect(type.id) },
            )
        }
    }
}

@Composable
private fun TabItem(name: String, selected: Boolean, onClick: () -> Unit) {
    val width by animateDpAsState(
        targetValue = if (selected) Spacing.lg else Spacing.xs,
        animationSpec = tween(Motion.MediumMs),
        label = "tab-indicator",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onBackground
        } else {
            starveilExtraColors.textMuted
        },
        animationSpec = tween(Motion.MediumMs),
        label = "tab-color",
    )
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(top = Spacing.sm, bottom = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
        )
        Spacer(Modifier.height(Spacing.xs))
        Box(
            modifier = Modifier
                .height(INDICATOR_HEIGHT)
                .width(width)
                .clip(AppShape.full)
                .background(if (selected) MaterialTheme.colorScheme.onBackground else Color.Transparent),
        )
    }
}

private val INDICATOR_HEIGHT = Spacing.xxs + Spacing.xxs / 2
