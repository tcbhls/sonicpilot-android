package com.sonicpilot.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.sonicpilot.music.data.model.AppAccentStyle
import com.sonicpilot.music.data.model.AppThemeMode

/**
 * 响应式主题调色盘（支持 跟随系统 / 纯净浅色 / 极夜深色 + 4 套经典强调色实时热切换）
 */
@Immutable
data class SonicPalette(
    val isDark: Boolean,
    val bgDeep: Color,
    val surfaceCard: Color,
    val surfaceElevated: Color,
    val hairline: Color,
    val accent: Color,
    val onAccent: Color,
    val primaryActionBg: Color,
    val onPrimaryAction: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val emeraldOk: Color,
    val roseFavorite: Color
)

private fun buildPalette(isDark: Boolean, accentStyle: AppAccentStyle): SonicPalette {
    val accent = Color(accentStyle.hexColor)
    return if (isDark) {
        SonicPalette(
            isDark = true,
            bgDeep = Color(0xFF0B0C10),
            surfaceCard = Color(0xFF14161D),
            surfaceElevated = Color(0xFF1E212B),
            hairline = Color(0xFF262A36),
            accent = accent,
            onAccent = Color.White,
            primaryActionBg = Color(0xFFF4F5F7),
            onPrimaryAction = Color(0xFF0B0C10),
            textPrimary = Color(0xFFF4F5F7),
            textSecondary = Color(0xFF9499A6),
            textMuted = Color(0xFF606573),
            emeraldOk = Color(0xFF22C55E),
            roseFavorite = Color(0xFFFB566E)
        )
    } else {
        // 对标网易云/QQ音乐/Apple Music 旗舰浅色极简白：云雾灰白底 + 纯白净卡片 + 高对比雅墨字
        SonicPalette(
            isDark = false,
            bgDeep = Color(0xFFF5F6F9),
            surfaceCard = Color(0xFFFFFFFF),
            surfaceElevated = Color(0xFFECEEF3),
            hairline = Color(0xFFE3E6EE),
            accent = accent,
            onAccent = Color.White,
            primaryActionBg = Color(0xFF14161D),
            onPrimaryAction = Color.White,
            textPrimary = Color(0xFF14161D),
            textSecondary = Color(0xFF646A78),
            textMuted = Color(0xFF9AA0AE),
            emeraldOk = Color(0xFF16A34A),
            roseFavorite = Color(0xFFE11D48)
        )
    }
}

val LocalSonicPalette = staticCompositionLocalOf {
    buildPalette(isDark = false, accentStyle = AppAccentStyle.CLOUD_RED)
}

/**
 * 动态配色访问入口（与现有页面无缝衔接，随浅色/深色/强调色自动实时变色）
 */
object SonicColors {
    val isDark: Boolean @Composable @ReadOnlyComposable get() = LocalSonicPalette.current.isDark
    val BgDeep: Color @Composable @ReadOnlyComposable get() = LocalSonicPalette.current.bgDeep
    val SurfaceCard: Color @Composable @ReadOnlyComposable get() = LocalSonicPalette.current.surfaceCard
    val SurfaceElevated: Color @Composable @ReadOnlyComposable get() = LocalSonicPalette.current.surfaceElevated
    val Hairline: Color @Composable @ReadOnlyComposable get() = LocalSonicPalette.current.hairline

    val GoldAccent: Color @Composable @ReadOnlyComposable get() = LocalSonicPalette.current.accent
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalSonicPalette.current.accent
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalSonicPalette.current.onAccent

    // 主按钮高对比色（浅色模式是雅墨黑，深色模式是珍珠白）
    val PearlWhite: Color @Composable @ReadOnlyComposable get() = LocalSonicPalette.current.primaryActionBg
    val OnPearlWhite: Color @Composable @ReadOnlyComposable get() = LocalSonicPalette.current.onPrimaryAction

    val EmeraldOk: Color @Composable @ReadOnlyComposable get() = LocalSonicPalette.current.emeraldOk
    val RoseFavorite: Color @Composable @ReadOnlyComposable get() = LocalSonicPalette.current.roseFavorite

    val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalSonicPalette.current.textPrimary
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalSonicPalette.current.textSecondary
    val TextMuted: Color @Composable @ReadOnlyComposable get() = LocalSonicPalette.current.textMuted
}

private val SonicTypography = Typography(
    headlineMedium = TextStyle(fontSize = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 15.5.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun resolveIsDarkTheme(mode: AppThemeMode): Boolean = when (mode) {
    AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    AppThemeMode.LIGHT -> false
    AppThemeMode.DARK -> true
}

@Composable
fun SonicPilotTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    accentStyle: AppAccentStyle = AppAccentStyle.CLOUD_RED,
    content: @Composable () -> Unit
) {
    val isDark = resolveIsDarkTheme(themeMode)
    val palette = buildPalette(isDark, accentStyle)

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            secondary = palette.accent,
            onSecondary = palette.onAccent,
            background = palette.bgDeep,
            onBackground = palette.textPrimary,
            surface = palette.surfaceCard,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceElevated,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.hairline
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            secondary = palette.accent,
            onSecondary = palette.onAccent,
            background = palette.bgDeep,
            onBackground = palette.textPrimary,
            surface = palette.surfaceCard,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceElevated,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.hairline
        )
    }

    CompositionLocalProvider(LocalSonicPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SonicTypography,
            content = content
        )
    }
}
