package com.shashankanil.halo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Always dark: AMOLED black behind wallpaper-matched (Material You) accents. */
@Composable
fun HaloTheme(content: @Composable () -> Unit) {
    // minSdk 37, so Material You colours are always available.
    val base = dynamicDarkColorScheme(LocalContext.current)
    val scheme = base.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0B0B0F),
        surfaceContainer = Color(0xFF131318),
        surfaceContainerHigh = Color(0xFF1B1B21),
        surfaceContainerHighest = Color(0xFF24242B),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
