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
}
