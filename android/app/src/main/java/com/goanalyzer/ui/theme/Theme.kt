package com.goanalyzer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.goanalyzer.data.ThemeManager
import com.goanalyzer.data.ThemeMode

// ============ 亮色主题 ============
private val LightColors = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9),
    onPrimaryContainer = Color(0xFF1B5E20),
    secondary = Color(0xFF00796B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2DFDB),
    onSecondaryContainer = Color(0xFF004D40),
    tertiary = Color(0xFFF57C00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE0B2),
    onTertiaryContainer = Color(0xFFE65100),
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF1A1A1A),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1A),
    surfaceContainerLow = Color(0xFFF0F1F2),
    surfaceContainerHigh = Color(0xFFE8E9EA),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF616161),
    error = Color(0xFFD32F2F),
    onError = Color.White,
    errorContainer = Color(0xFFFFCDD2),
    onErrorContainer = Color(0xFFB71C1C),
    outline = Color(0xFFBDBDBD),
    outlineVariant = Color(0xFFE0E0E0),
)

// ============ 暗色主题 ============
private val DarkColors = darkColorScheme(
    primary = Color(0xFF66BB6A),
    onPrimary = Color(0xFF1B5E20),
    primaryContainer = Color(0xFF2E7D32),
    onPrimaryContainer = Color(0xFFC8E6C9),
    secondary = Color(0xFF4DB6AC),
    onSecondary = Color(0xFF004D40),
    secondaryContainer = Color(0xFF00796B),
    onSecondaryContainer = Color(0xFFB2DFDB),
    tertiary = Color(0xFFFFB74D),
    onTertiary = Color(0xFFBF360C),
    tertiaryContainer = Color(0xFFF57C00),
    onTertiaryContainer = Color(0xFFFFE0B2),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE0E0E0),
    surfaceContainerLow = Color(0xFF252525),
    surfaceContainerHigh = Color(0xFF2C2C2C),
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFF9E9E9E),
    error = Color(0xFFEF5350),
    onError = Color(0xFF1A1A1A),
    errorContainer = Color(0xFFB71C1C),
    onErrorContainer = Color(0xFFFFCDD2),
    outline = Color(0xFF424242),
    outlineVariant = Color(0xFF333333),
)

@Composable
fun GoAnalyzerTheme(
    themeManager: ThemeManager? = null,
    content: @Composable () -> Unit
) {
    val darkTheme = if (themeManager != null) {
        val themeMode by themeManager.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
        when (themeMode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }
    } else {
        isSystemInDarkTheme()
    }

    // 始终使用自定义颜色方案（不使用 dynamic color，保持围棋主题一致性）
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
