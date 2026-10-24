package com.futurequestr.app

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = Color(0xFF2EE6A8), onPrimary = Color(0xFF05241A),
    secondary = Color(0xFF4A5FDC), onSecondary = Color.White,
    background = Color(0xFF0B0E2C), onBackground = Color.White,
    surface = Color(0xFF151A4A), onSurface = Color.White,
    surfaceVariant = Color(0xFF1E2560), onSurfaceVariant = Color(0xFFC5CAF0),
    error = Color(0xFFFF8A80),
    outline = Color(0xFF2B3275)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3846D4), onPrimary = Color.White,
    secondary = Color(0xFF14C78A), onSecondary = Color.White,
    background = Color.White, onBackground = Color(0xFF141A4A),
    surface = Color(0xFFF1F3FC), onSurface = Color(0xFF141A4A),
    surfaceVariant = Color(0xFFE3E7F8), onSurfaceVariant = Color(0xFF3A4170),
    error = Color(0xFFB3261E),
    outline = Color(0xFFD3D8EE)
)

val MintDark = Color(0xFF2EE6A8)
val MintLight = Color(0xFF14C78A)

/** The person's choice from the Account tab: 0 follows the phone, 1 is light, 2 is dark. */
@Composable
fun isDarkTheme(): Boolean = when (Store.themeMode) {
    1 -> false
    2 -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun accentColor(): Color = if (isDarkTheme()) MintDark else MintLight

@Composable
fun FQTheme(content: @Composable () -> Unit) {
    val dark = isDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colors.background.toArgb()
                window.navigationBarColor = colors.background.toArgb()
                window.setBackgroundDrawable(ColorDrawable(colors.background.toArgb()))
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(12.dp), extraLarge = RoundedCornerShape(16.dp)),
        content = content
    )
}
