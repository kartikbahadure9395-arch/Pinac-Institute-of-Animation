package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val PinacColorScheme = lightColorScheme(
    primary = PinacYellowAccent,
    secondary = PinacDarkCardLightHex,
    background = PinacBlackHex,
    surface = PinacDarkCardHex,
    onPrimary = DeepSlate900,
    onSecondary = WhiteText,
    onBackground = WhiteText,
    onSurface = WhiteText,
    error = RiskRed,
    errorContainer = RiskRed,
    onErrorContainer = SoftWhite
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false, // Default false for Geometric Balance light backdrop styling
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = PinacColorScheme,
        typography = Typography,
        content = content
    )
}
