package com.chengzi.btmax.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 品牌 fallback 色板：蓝牙蓝 #1B6EF3 系。
 * Android 12+ 且 [dynamicColor] 开启时使用动态取色。
 */
val fullbarsLightColorScheme = lightColorScheme(
    primary = Color(0xFF1B6EF3),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDAE2FF),
    onPrimaryContainer = Color(0xFF001849),
    secondary = Color(0xFF006874),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF97F0FF),
    onSecondaryContainer = Color(0xFF001F24),
    tertiary = Color(0xFF725572),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFDD7FA),
    onTertiaryContainer = Color(0xFF2A122C),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191B25),
    surfaceVariant = Color(0xFFE0E2F0),
    onSurfaceVariant = Color(0xFF444653),
    outline = Color(0xFF747683),
)

val fullbarsDarkColorScheme = darkColorScheme(
    primary = Color(0xFFAEC6FF),
    onPrimary = Color(0xFF002C72),
    primaryContainer = Color(0xFF0048A8),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFF4FD8EB),
    onSecondary = Color(0xFF00363D),
    secondaryContainer = Color(0xFF004F58),
    onSecondaryContainer = Color(0xFF97F0FF),
    tertiary = Color(0xFFE0BBDD),
    onTertiary = Color(0xFF422742),
    tertiaryContainer = Color(0xFF5A3D5A),
    onTertiaryContainer = Color(0xFFFDD7FA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surface = Color(0xFF111320),
    onSurface = Color(0xFFE1E1EC),
    surfaceVariant = Color(0xFF444653),
    onSurfaceVariant = Color(0xFFC4C6D6),
    outline = Color(0xFF8E90A0),
)

/**
 * 音质满格全局主题。默认跟随系统深浅色，Android 12+ 默认启用动态取色。
 */
@Composable
fun FullBarsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> fullbarsDarkColorScheme
        else -> fullbarsLightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
