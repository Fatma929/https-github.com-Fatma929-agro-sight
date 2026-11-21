package com.example.ui.theme

import android.app.Activity
import android.view.View
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// Unified Material 3 Light Color Scheme matching the Official AgroSight Logo:
//   • Primary:   Botanical Leaf Green (#1E6744) & Soft Botanical Sage (#D3E7E0)
//   • Secondary: Satellite Data Pixel Blue (#198AC7 / #308DC2) & Soft Pixel Blue (#E1F2FA)
//   • Canvas:    Warm Ivory (#FCFBF7 / #F4F3ED) & Subtitle Charcoal-Sage (#1A3326 / #57685D)
val AgroSightLogoLightColorScheme = lightColorScheme(
    primary = AgroSightBotanicalGreen,
    onPrimary = AgroSightIvory,
    primaryContainer = AgroSightBotanicalSage,
    onPrimaryContainer = AgroSightBotanicalDark,
    inversePrimary = Color(0xFF7FD4A6),
    secondary = AgroSightPixelBlue,
    onSecondary = Color.White,
    secondaryContainer = AgroSightPixelBlueSoft,
    onSecondaryContainer = Color(0xFF0A476B),
    tertiary = AmberWarning,
    onTertiary = Color.White,
    tertiaryContainer = AmberSoft,
    onTertiaryContainer = Color(0xFF78350F),
    error = RoseDanger,
    onError = Color.White,
    errorContainer = RoseSoft,
    onErrorContainer = Color(0xFF7F1D1D),
    background = AgroSightIvory,
    onBackground = AgroSightCharcoalInk,
    surface = Color.White,
    onSurface = AgroSightCharcoalInk,
    surfaceVariant = ForestCardElevated,
    onSurfaceVariant = MintTextSoft,
    surfaceTint = AgroSightBotanicalGreen,
    inverseSurface = AgroSightCharcoalInk,
    inverseOnSurface = AgroSightIvory,
    outline = ForestCardBorder,
    outlineVariant = AgroSightBotanicalSage,
    scrim = Color(0x66132D20),
    surfaceBright = Color.White,
    surfaceDim = AgroSightIvoryDeep,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = AgroSightIvory,
    surfaceContainer = Color(0xFFF3F7F4),
    surfaceContainerHigh = ForestCardElevated,
    surfaceContainerHighest = AgroSightBotanicalSage
)

// Complementary Dark Botanical Material 3 Color Scheme derived from the AgroSight Logo
val AgroSightLogoDarkColorScheme = darkColorScheme(
    primary = Color(0xFF6CD29B),
    onPrimary = Color(0xFF0B3521),
    primaryContainer = AgroSightBotanicalGreen,
    onPrimaryContainer = AgroSightBotanicalSage,
    inversePrimary = AgroSightBotanicalGreen,
    secondary = Color(0xFF66C3F4),
    onSecondary = Color(0xFF07344F),
    secondaryContainer = AgroSightPixelBlue,
    onSecondaryContainer = AgroSightPixelBlueSoft,
    tertiary = AmberLight,
    onTertiary = Color(0xFF451A03),
    tertiaryContainer = Color(0xFF78350F),
    onTertiaryContainer = AmberSoft,
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = RoseSoft,
    background = Slate950,
    onBackground = AgroSightIvory,
    surface = Slate900,
    onSurface = AgroSightIvory,
    surfaceVariant = Slate800,
    onSurfaceVariant = AgroSightBotanicalSage,
    surfaceTint = Color(0xFF6CD29B),
    inverseSurface = AgroSightIvory,
    inverseOnSurface = Slate950,
    outline = Slate700,
    outlineVariant = Slate800
)

val AgroSightShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun AgroSightTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        AgroSightLogoDarkColorScheme
    } else {
        AgroSightLogoLightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.layoutDirection = View.LAYOUT_DIRECTION_RTL
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AgroSightShapes
        ) {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalTextStyle provides LocalTextStyle.current.merge(
                    TextStyle(
                        fontFamily = CairoFontFamily,
                        textAlign = TextAlign.Right,
                        textDirection = TextDirection.Rtl
                    )
                )
            ) {
                content()
            }
        }
    }
}
