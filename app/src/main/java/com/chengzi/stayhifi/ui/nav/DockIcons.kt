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
}
