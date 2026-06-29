package org.milkdev.dreamplayer.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import org.jetbrains.compose.resources.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.milkdev.dreamplayer.SetSystemBarAppearance
import org.milkdev.dreamplayer.generated.resources.Res
import org.milkdev.dreamplayer.generated.resources.google_sans_rounded_bold
import org.milkdev.dreamplayer.generated.resources.google_sans_rounded_extralight
import org.milkdev.dreamplayer.generated.resources.google_sans_rounded_light
import org.milkdev.dreamplayer.generated.resources.google_sans_rounded_medium
import org.milkdev.dreamplayer.generated.resources.google_sans_rounded_regular
import org.milkdev.dreamplayer.generated.resources.google_sans_rounded_thin
import org.milkdev.dreamplayer.generated.resources.sn_pro_bold
import org.milkdev.dreamplayer.generated.resources.sn_pro_bold_italic
import org.milkdev.dreamplayer.generated.resources.sn_pro_extrabold
import org.milkdev.dreamplayer.generated.resources.sn_pro_extrabold_italic
import org.milkdev.dreamplayer.generated.resources.sn_pro_extralight
import org.milkdev.dreamplayer.generated.resources.sn_pro_extralight_italic
import org.milkdev.dreamplayer.generated.resources.sn_pro_italic
import org.milkdev.dreamplayer.generated.resources.sn_pro_light
import org.milkdev.dreamplayer.generated.resources.sn_pro_light_italic
import org.milkdev.dreamplayer.generated.resources.sn_pro_medium
import org.milkdev.dreamplayer.generated.resources.sn_pro_medium_italic
import org.milkdev.dreamplayer.generated.resources.sn_pro_regular

@Immutable
data class AppSpacings(
    val default: Dp = 0.dp,
    val extraSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 12.dp,
    val large: Dp = 16.dp,
    val extraLarge: Dp = 24.dp,
    val screenPadding: Dp = 20.dp
)

val LocalAppSpacings = staticCompositionLocalOf { AppSpacings() }

data class AppCustomTypography(
    val googleSans: Typography,
    val snPro: Typography
)

val LocalAppTypography = staticCompositionLocalOf<AppCustomTypography> {
    error("No AppCustomTypography provided! Make sure to wrap your content in AppTheme.")
}

val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    surface = SurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurface = OnSurfaceLight
)

val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurface = OnSurfaceDark
)

@Composable
fun createAppTypography(): AppCustomTypography {
    val googleSansFontFamily = FontFamily(
        Font(Res.font.google_sans_rounded_regular, FontWeight.Normal),
        Font(Res.font.google_sans_rounded_medium, FontWeight.Medium),
        Font(Res.font.google_sans_rounded_bold, weight = FontWeight.Bold),
        Font(Res.font.google_sans_rounded_light, weight = FontWeight.Light),
        Font(Res.font.google_sans_rounded_thin, weight = FontWeight.Thin),
        Font(Res.font.google_sans_rounded_extralight, weight = FontWeight.ExtraLight),
    )

    val snProFontFamily = FontFamily(
        Font(Res.font.sn_pro_extralight, weight = FontWeight.ExtraLight, style = FontStyle.Normal),
        Font(Res.font.sn_pro_extralight_italic, weight = FontWeight.ExtraLight, style = FontStyle.Italic),
        Font(Res.font.sn_pro_light, weight = FontWeight.Light, style = FontStyle.Normal),
        Font(Res.font.sn_pro_light_italic, weight = FontWeight.Light, style = FontStyle.Italic),
        Font(Res.font.sn_pro_regular, weight = FontWeight.Normal, style = FontStyle.Normal),
        Font(Res.font.sn_pro_italic, weight = FontWeight.Normal, style = FontStyle.Italic),
        Font(Res.font.sn_pro_medium, weight = FontWeight.Medium, style = FontStyle.Normal),
        Font(Res.font.sn_pro_medium_italic, weight = FontWeight.Medium, style = FontStyle.Italic),
        Font(Res.font.sn_pro_bold, weight = FontWeight.Bold, style = FontStyle.Normal),
        Font(Res.font.sn_pro_bold_italic, weight = FontWeight.Bold, style = FontStyle.Italic),
        Font(Res.font.sn_pro_extrabold, weight = FontWeight.ExtraBold, style = FontStyle.Normal),
        Font(Res.font.sn_pro_extrabold_italic, weight = FontWeight.ExtraBold, style = FontStyle.Italic),
    )

    val googleSansTypography = Typography(
        displayLarge = TextStyle(fontFamily = googleSansFontFamily, fontWeight = FontWeight.Light, fontSize = 57.sp, lineHeight = 64.sp),
        displayMedium = TextStyle(fontFamily = googleSansFontFamily, fontWeight = FontWeight.Normal, fontSize = 45.sp, lineHeight = 52.sp),
        displaySmall = TextStyle(fontFamily = googleSansFontFamily, fontWeight = FontWeight.Normal, fontSize = 36.sp, lineHeight = 44.sp),
        headlineLarge = TextStyle(fontFamily = googleSansFontFamily, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
        headlineMedium = TextStyle(fontFamily = googleSansFontFamily, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 36.sp),
        headlineSmall = TextStyle(fontFamily = googleSansFontFamily, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp),
        titleLarge = TextStyle(fontFamily = googleSansFontFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium = TextStyle(fontFamily = googleSansFontFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
        titleSmall = TextStyle(fontFamily = googleSansFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
        bodyLarge = TextStyle(fontFamily = googleSansFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
        bodyMedium = TextStyle(fontFamily = googleSansFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
        bodySmall = TextStyle(fontFamily = googleSansFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
        labelLarge = TextStyle(fontFamily = googleSansFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
        labelMedium = TextStyle(fontFamily = googleSansFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
        labelSmall = TextStyle(fontFamily = googleSansFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    )

    val snProTypography = Typography(
        displayLarge = TextStyle(fontFamily = snProFontFamily, fontWeight = FontWeight.Light, fontSize = 57.sp, lineHeight = 64.sp),
        displayMedium = TextStyle(fontFamily = snProFontFamily, fontWeight = FontWeight.Normal, fontSize = 45.sp, lineHeight = 52.sp),
        displaySmall = TextStyle(fontFamily = snProFontFamily, fontWeight = FontWeight.Normal, fontSize = 36.sp, lineHeight = 44.sp),
        headlineLarge = TextStyle(fontFamily = snProFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 40.sp),
        headlineMedium = TextStyle(fontFamily = snProFontFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
        headlineSmall = TextStyle(fontFamily = snProFontFamily, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp),
        titleLarge = TextStyle(fontFamily = snProFontFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium = TextStyle(fontFamily = snProFontFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
        titleSmall = TextStyle(fontFamily = snProFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
        bodyLarge = TextStyle(fontFamily = snProFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
        bodyMedium = TextStyle(fontFamily = snProFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
        bodySmall = TextStyle(fontFamily = snProFontFamily, fontWeight = FontWeight.Light, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
        labelLarge = TextStyle(fontFamily = snProFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
        labelMedium = TextStyle(fontFamily = snProFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
        labelSmall = TextStyle(fontFamily = snProFontFamily, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp)
    )

    return AppCustomTypography(
        googleSans = googleSansTypography,
        snPro = snProTypography
    )
}

@Composable
expect fun rememberPlatformColorScheme(darkTheme: Boolean): ColorScheme

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val customTypography = createAppTypography()
    val spacings = AppSpacings()
    val colorScheme = rememberPlatformColorScheme(darkTheme = darkTheme)

    SetSystemBarAppearance(isDark = darkTheme)
    CompositionLocalProvider(
        LocalAppTypography provides customTypography,
        LocalAppSpacings provides spacings
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = customTypography.googleSans,
            content = content
        )
    }
}

object AppTheme {
    val typography: AppCustomTypography
        @Composable
        get() = LocalAppTypography.current

    val spacing: AppSpacings
        @Composable
        get() = LocalAppSpacings.current
}
