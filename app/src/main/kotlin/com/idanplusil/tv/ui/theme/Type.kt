package com.idanplusil.tv.ui.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography

// System font only. Roboto/Noto already carries Hebrew on-device, so adding
// values-iw later needs no font work and no APK growth.
//
// Sizes are floored for a three-metre viewing distance: 13sp is the absolute
// minimum anything readable may use.
val AppTypography = Typography(
    displaySmall = Typography().displaySmall.copy(fontSize = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = Typography().titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = Typography().titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 16.sp),
    labelLarge = Typography().labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    labelMedium = Typography().labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
)
