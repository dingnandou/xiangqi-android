package com.yijin.xiangqi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 视觉基调来自《01-产品规划与页面功能》§9：
// 浅木色棋盘、浅色背景、朱红强调、深色正文。
// M0 只验证引擎，主题保持克制，等棋盘组件（M2）落地再细化。

private val Accent = Color(0xFFA63A2A)
private val AccentDark = Color(0xFFD97A63)
private val WoodLight = Color(0xFFE9DCBE)
private val SurfaceLight = Color(0xFFF8F5EF)
private val InkLight = Color(0xFF2A2723)

private val LightScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = WoodLight,
    background = SurfaceLight,
    onBackground = InkLight,
    surface = Color.White,
    onSurface = InkLight,
    surfaceVariant = WoodLight,
    onSurfaceVariant = InkLight,
)

private val DarkScheme = darkColorScheme(
    primary = AccentDark,
    secondary = Color(0xFF4A3F31),
)

@Composable
fun YijinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}