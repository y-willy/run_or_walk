package com.example.run_or_walk.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun paceText(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = height.sp,
    letterSpacing = 0.sp
)

// Generous line height keeps Korean copy readable at the system font scale.
val Typography = Typography(
    displayLarge = paceText(52, 60, FontWeight.Bold),
    displayMedium = paceText(44, 52, FontWeight.Bold),
    displaySmall = paceText(36, 44, FontWeight.Bold),
    headlineLarge = paceText(32, 42, FontWeight.Bold),
    headlineMedium = paceText(28, 38, FontWeight.Bold),
    headlineSmall = paceText(24, 34, FontWeight.Bold),
    titleLarge = paceText(22, 32, FontWeight.Bold),
    titleMedium = paceText(17, 26, FontWeight.SemiBold),
    titleSmall = paceText(15, 23, FontWeight.SemiBold),
    bodyLarge = paceText(16, 26),
    bodyMedium = paceText(14, 22),
    bodySmall = paceText(12, 19),
    labelLarge = paceText(14, 21, FontWeight.SemiBold),
    labelMedium = paceText(12, 18, FontWeight.Medium),
    labelSmall = paceText(11, 17, FontWeight.Medium)
)
