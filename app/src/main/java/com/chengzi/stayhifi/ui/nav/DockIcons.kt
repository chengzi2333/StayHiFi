package com.chengzi.stayhifi.ui.nav

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Task 22：包体积瘦身。原 `Icons.Default.Headphones` 来自
 * material-icons-extended（约 2000 枚图标类全量进 dex，debug 包 30M→77.8M）。
 * 这里仅内联本项目用到的 Headphones 一枚图标。
 *
 * Path 数据取自 google/material-design-icons 官方仓库
 * src/hardware/headphones/materialicons/24px.svg
 * （https://github.com/google/material-design-icons，Apache-2.0）。
 * viewport 24x24，fill-rule 为默认 nonzero，原 SVG 未指定 fill，故用 SolidColor(Color.Black)，
 * 运行时由 ImageVector 的 tint 覆盖。
 */
object StayHiFiIcons {
    private var _headphones: ImageVector? = null
    private var _smartphone: ImageVector? = null
    private var _bolt: ImageVector? = null
    private var _tune: ImageVector? = null

    val Headphones: ImageVector
        get() {
            if (_headphones != null) return _headphones!!
            _headphones = ImageVector.Builder(
                name = "StayHiFiIcons.Headphones",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Black),
                    pathFillType = PathFillType.NonZero,
                ) {
                    // M12,3
                    moveTo(12f, 3f)
                    // c-4.97,0-9,4.03-9,9
                    curveToRelative(-4.97f, 0f, -9f, 4.03f, -9f, 9f)
                    // v7
                    verticalLineToRelative(7f)
                    // c0,1.1,0.9,2,2,2
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    // h4
                    horizontalLineToRelative(4f)
                    // v-8
                    verticalLineToRelative(-8f)
                    // H5
                    horizontalLineTo(5f)
                    // v-1
                    verticalLineToRelative(-1f)
                    // c0,-3.87,3.13,-7,7,-7
                    curveToRelative(0f, -3.87f, 3.13f, -7f, 7f, -7f)
                    // s7,3.13,7,7
                    reflectiveCurveToRelative(7f, 3.13f, 7f, 7f)
                    // v1
                    verticalLineToRelative(1f)
                    // h-4
                    horizontalLineToRelative(-4f)
                    // v8
                    verticalLineToRelative(8f)
                    // h4
                    horizontalLineToRelative(4f)
                    // c1.1,0,2,-0.9,2,-2
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    // v-7
                    verticalLineToRelative(-7f)
                    // C21,7.03,16.97,3,12,3
                    curveTo(21f, 7.03f, 16.97f, 3f, 12f, 3f)
                    // z
                    close()
                }
            }.build()
            return _headphones!!
        }

    /**
     * 圆角矩形手机（Task 28 修正轮 F1）：Icons.Default.Phone 是电话听筒、
     * Icons.Default.PhoneAndroid 在已删的 material-icons-extended，故内联。
     * Path 取自官方仓库 src/hardware/smartphone/materialicons/24px.svg（Apache-2.0）：
     * M17 1.01L7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14z
     */
    val Smartphone: ImageVector
        get() {
            if (_smartphone != null) return _smartphone!!
            _smartphone = ImageVector.Builder(
                name = "StayHiFiIcons.Smartphone",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Black),
                    pathFillType = PathFillType.NonZero,
                ) {
                    // M17,1.01 L7,1 c-1.1,0 -2,0.9 -2,2 v18 c0,1.1 0.9,2 2,2 h10 c1.1,0 2,-0.9 2,-2 V3 c0,-1.1 -0.9,-1.99 -2,-1.99 z
                    moveTo(17f, 1.01f)
                    lineTo(7f, 1f)
                    curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                    verticalLineToRelative(18f)
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    horizontalLineToRelative(10f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    verticalLineTo(3f)
                    curveToRelative(0f, -1.1f, -0.9f, -1.99f, -2f, -1.99f)
                    close()
                    // M17,19 H7 V5 h10 v14 z
                    moveTo(17f, 19f)
                    horizontalLineTo(7f)
                    verticalLineTo(5f)
                    horizontalLineToRelative(10f)
                    verticalLineToRelative(14f)
                    close()
                }
            }.build()
            return _smartphone!!
        }

    /**
     * 闪电（v1.2.5 首页「立即拉满」主按钮图标）。
     * Path 取自 google/material-design-icons `action/bolt/materialicons/24px.svg`（Apache-2.0）：
     * M11 21h-1l1-7H7.5c-.58 0-.57-.32-.38-.66.19-.34.05-.08.07-.12C8.48 10.94 10.42 7.54 13 3h1
     * l-1 7h3.5c.49 0 .56.33.47.51l-.07.15C12.96 17.55 11 21 11 21z
     */
    val Bolt: ImageVector
        get() {
            if (_bolt != null) return _bolt!!
            _bolt = ImageVector.Builder(
                name = "StayHiFiIcons.Bolt",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Black),
                    pathFillType = PathFillType.NonZero,
                ) {
                    // M11,21 h-1 l1,-7 H7.5
                    moveTo(11f, 21f)
                    horizontalLineToRelative(-1f)
                    lineToRelative(1f, -7f)
                    horizontalLineTo(7.5f)
                    // c-.58,0 -.57,-.32 -.38,-.66
                    curveToRelative(-0.58f, 0f, -0.57f, -0.32f, -0.38f, -0.66f)
                    // .19,-.34 .05,-.08 .07,-.12
                    curveToRelative(0.19f, -0.34f, 0.05f, -0.08f, 0.07f, -0.12f)
                    // C8.48,10.94 10.42,7.54 13,3
                    curveTo(8.48f, 10.94f, 10.42f, 7.54f, 13f, 3f)
                    // h1 l-1,7 h3.5
                    horizontalLineToRelative(1f)
                    lineToRelative(-1f, 7f)
                    horizontalLineToRelative(3.5f)
                    // c.49,0 .56,.33 .47,.51
                    curveToRelative(0.49f, 0f, 0.56f, 0.33f, 0.47f, 0.51f)
                    // l-.07,.15
                    lineToRelative(-0.07f, 0.15f)
                    // C12.96,17.55 11,21 11,21 z
                    curveTo(12.96f, 17.55f, 11f, 21f, 11f, 21f)
                    close()
                }
            }.build()
            return _bolt!!
        }

    /**
     * 滑杆（v1.2.5 首页「自定义音质」按钮图标）。
     * Path 取自 google/material-design-icons `image/tune/materialicons/24px.svg`（Apache-2.0），
     * 已把相对指令换算成绝对坐标，便于逐段核对。
     */
    val Tune: ImageVector
        get() {
            if (_tune != null) return _tune!!
            _tune = ImageVector.Builder(
                name = "StayHiFiIcons.Tune",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Black),
                    pathFillType = PathFillType.NonZero,
                ) {
                    // M3,17 v2 h6 v-2 H3 z
                    moveTo(3f, 17f)
                    verticalLineTo(19f)
                    horizontalLineTo(9f)
                    verticalLineTo(17f)
                    horizontalLineTo(3f)
                    close()
                    // M3,5 v2 h10 V5 H3 z
                    moveTo(3f, 5f)
                    verticalLineTo(7f)
                    horizontalLineTo(13f)
                    verticalLineTo(5f)
                    horizontalLineTo(3f)
                    close()
                    // m10,16 v-2 h8 v-2 h-8 v-2 h-2 v6 h2 z
                    moveTo(13f, 21f)
                    verticalLineTo(19f)
                    horizontalLineTo(21f)
                    verticalLineTo(17f)
                    horizontalLineTo(13f)
                    verticalLineTo(15f)
                    horizontalLineTo(11f)
                    verticalLineTo(21f)
                    horizontalLineTo(13f)
                    close()
                    // M7,9 v2 H3 v2 h4 v2 h2 V9 H7 z
                    moveTo(7f, 9f)
                    verticalLineTo(11f)
                    horizontalLineTo(3f)
                    verticalLineTo(13f)
                    horizontalLineTo(7f)
                    verticalLineTo(15f)
                    horizontalLineTo(9f)
                    verticalLineTo(9f)
                    horizontalLineTo(7f)
                    close()
                    // m14,4 v-2 H11 v2 h10 z
                    moveTo(21f, 13f)
                    verticalLineTo(11f)
                    horizontalLineTo(11f)
                    verticalLineTo(13f)
                    horizontalLineTo(21f)
                    close()
                    // m-6,-4 h2 V7 h4 V5 h-4 V3 h-2 v6 z
                    moveTo(15f, 9f)
                    horizontalLineTo(17f)
                    verticalLineTo(7f)
                    horizontalLineTo(21f)
                    verticalLineTo(5f)
                    horizontalLineTo(17f)
                    verticalLineTo(3f)
                    horizontalLineTo(15f)
                    verticalLineTo(9f)
                    close()
                }
            }.build()
            return _tune!!
        }
}
