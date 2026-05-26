package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Geometric Balance Design Theme - Styling Tokens
val PinacBlackHex = Color(0xFFF6F6F6)            // Clean light slate/grey backdrop
val PinacDarkCardHex = Color(0xFFFFFFFF)          // Crisp white container cards
val PinacDarkCardLightHex = Color(0xFFE2E8F0)     // Slate 200 borders/dividers
val PinacYellowAccent = Color(0xFFF5C400)         // Striking Geometric Yellow Accent
val PinacYellowAccentLight = Color(0xFFFFF9DB)    // Light tinted yellow highlight
val WhiteText = Color(0xFF0F172A)                 // Primary high-contrast text: Deep Slate 900
val MutedText = Color(0xFF64748B)                 // Secondary description text: Slate 500

// Explicit Theme-Compliant Constants for Contrast Overlay blocks
val DeepSlate900 = Color(0xFF0F172A)              // Rich dark slate
val CleanSlate400 = Color(0xFF94A3B8)             // Middle slate for dark card subtitles
val SoftWhite = Color(0xFFFFFFFF)                 // Pure white for text overlay on dark blocks

// Material 3 Light/Dark Mappings (Maintains fallback compatibility)
val PrimaryDark = PinacYellowAccent
val SecondaryDark = PinacDarkCardLightHex
val BackgroundDark = PinacBlackHex
val SurfaceDark = PinacDarkCardHex
val OnPrimaryDark = DeepSlate900
val OnSecondaryDark = WhiteText
val OnBackgroundDark = WhiteText
val OnSurfaceDark = WhiteText

// Error and State colors (re-styled to be modern geometric-compliant)
val CleanGreen = Color(0xFF10B981)                // Emerald Green
val RiskRed = Color(0xFFEF4444)                   // Rose Red
val BorderAlertYellow = Color(0xFFF59E0B)          // Amber Yellow
val HolidayGrey = Color(0xFF94A3B8)               // Slate Grey
