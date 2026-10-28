package com.chengzi.stayhifi.ui.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 二级页（drill-in）统一顶部栏：返回箭头 + 标题。
 * 运行日志页与设置页「特权方式」二级页共用，同一返回动作不再出现
 * 文字「返回」与箭头两种样式。
 *
 * 返回按钮为 material3 IconButton 默认 48dp 触控目标，
 * contentDescription「返回」（无障碍）。
 *
 * [windowInsets] 默认与独立 Scaffold 内的 TopAppBar 一致（自带状态栏内边距）；
 * 已处于外层 Scaffold content padding 之内的页面（如设置页二级页）传
 * [noWindowInsets]，避免状态栏内边距重复叠加。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubPageTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    TopAppBar(
        title = { Text(title) },
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                )
            }
        },
        windowInsets = windowInsets,
    )
}

/** 零内边距：页面已在外层 Scaffold content padding 之内时使用。 */
val noWindowInsets: WindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)
