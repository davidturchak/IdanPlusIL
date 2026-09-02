package com.idanplusil.tv.ui.theme

import androidx.compose.ui.graphics.Color

// Derived from the brand mark: its saturated pixels average #E5874C, with hue
// spanning roughly 9deg (red-orange) to 36deg (amber), centred near 23deg. The
// scheme below lifts that hue to the tones a dark surface needs while keeping
// every foreground role well clear of WCAG AA.

val Primary = Color(0xFFFFB25C)             // 10.88:1 on Background
val OnPrimary = Color(0xFF452200)
val PrimaryContainer = Color(0xFF6B3400)
val OnPrimaryContainer = Color(0xFFFFDCC2)

val Secondary = Color(0xFFFFB4A0)
val OnSecondary = Color(0xFF5C1A00)
val SecondaryContainer = Color(0xFF7C2D0F)
val OnSecondaryContainer = Color(0xFFFFDBD1)

val Tertiary = Color(0xFFE4C08A)
val OnTertiary = Color(0xFF402D0A)
val TertiaryContainer = Color(0xFF5A431B)
val OnTertiaryContainer = Color(0xFFFFDEB0)

// Not pure black: a faint cool cast keeps LCD panels from showing backlight
// blotch, and makes the warm brand orange read as warm by contrast. Still dark
// enough that OLED keeps most of the black-level benefit.
val Background = Color(0xFF0D0D10)
val OnBackground = Color(0xFFE8E6EA)        // 15.65:1
val Surface = Color(0xFF16161B)
val OnSurface = Color(0xFFE8E6EA)           // 14.54:1
val SurfaceVariant = Color(0xFF23232B)
val OnSurfaceVariant = Color(0xFFB6B3BC)    // 8.73:1
val InverseSurface = Color(0xFFE8E6EA)
val InverseOnSurface = Color(0xFF16161B)
val InversePrimary = Color(0xFF8A4600)

val ErrorColor = Color(0xFFFFB4AB)
val OnErrorColor = Color(0xFF690005)
val ErrorContainer = Color(0xFF93000A)
val OnErrorContainer = Color(0xFFFFDAD6)

val BorderColor = Color(0xFF32323C)
val BorderVariantColor = Color(0xFF23232B)
val ScrimColor = Color(0xFF000000)

/**
 * Roles the TV [androidx.tv.material3.ColorScheme] has no slot for.
 *
 * Focus is the single most important affordance in a remote-driven UI, so it
 * gets named tokens rather than ad-hoc colours at call sites.
 */
object BrandColors {
    val Orange = Color(0xFFE58000)          // the literal logo colour, 6.86:1 on Background
    val FocusRing = Color(0xFFFFB25C)
    val CardSurface = Color(0xFF17171C)
    /** A *warm* lift. A neutral grey lift is nearly invisible under TV gamma. */
    val CardSurfaceFocused = Color(0xFF2A2018)
    val LiveRed = Color(0xFFE5484D)
    val MonogramTop = Color(0xFF6B3400)
    val MonogramBottom = Color(0xFF3A1D00)
}
