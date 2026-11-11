package com.aiutil.videodownloader.ui.theme

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

private val Blue = Color(0xFF1E6DFF)
private val BlueDark = Color(0xFF8AB4FF)

private val LightColors = lightColorScheme(
    primary = Blue,
    secondary = Color(0xFF4A5B7A),
    tertiary = Color(0xFF00897B),
)

private val DarkColors = darkColorScheme(
    primary = BlueDark,
    secondary = Color(0xFFB3C3E0),
    tertiary = Color(0xFF4DB6AC),
)

@Composable
fun VideoDownloaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        // Android 12+ 는 사용자 배경화면 색을 따라간다.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colors, content = content)
}
