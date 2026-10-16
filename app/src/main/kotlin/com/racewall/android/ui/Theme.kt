package com.racewall.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.racewall.android.R

/** Every colour here comes from design/DESIGN-LOCK.md in the desktop repository. */
object Rw {
    val bg = Color(0xFF000000)
    val border = Color(0xFF1F1F1F)
    val border2 = Color(0xFF2C2C2C)
    val t1 = Color(0xFFD0D0D0)
    val t2 = Color(0xFF8A8A8A)
    val t3 = Color(0xFF5A5A5A)
    val hover = Color(0xFF111111)
    val brand = Color(0xFFA0151E)

    val soft = Color(0xFFFF3B3B)
    val medium = Color(0xFFFFD12E)
    val hard = Color(0xFFF0F0F0)
    val inter = Color(0xFF39B54A)
    val wet = Color(0xFF2F7FFF)
    val yellow = Color(0xFFFFD12E)
    val red = Color(0xFFFF3B3B)
    val sc = Color(0xFFFF9F1A)
    val purple = Color(0xFFB45CFF)
    val green = Color(0xFF2ECC71)

    /** The timing tower's row rule and its empty sector segment. */
    val rowRule = Color(0xFF0E0E0E)
    val segmentEmpty = Color(0xFF1F1F1F)

    /** Selawik: Microsoft's open stand-in for Segoe UI, with the same metrics and fixed-width digits. */
    val ui = FontFamily(
        Font(R.font.selawik_regular, FontWeight.Normal),
        Font(R.font.selawik_semibold, FontWeight.SemiBold),
    )

    /** Only the logo and the app icon use Russo One. */
    val logo = FontFamily(Font(R.font.russo_one, FontWeight.Normal))

    /** The desktop's `13px/1.35`, centred in its line like a browser does. */
    fun text(size: Float = 13f, color: Color = t1, weight: FontWeight = FontWeight.Normal, family: FontFamily = ui): TextStyle =
        TextStyle(
            color = color,
            fontFamily = family,
            fontWeight = weight,
            fontSize = size.sp,
            lineHeight = (size * 1.35f).sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        )

    val body: TextStyle = text()
}

val LocalTextStyle = staticCompositionLocalOf { Rw.body }

@Composable
fun RaceWallTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTextStyle provides Rw.body, content = content)
}

/** Font sizes in the desktop's CSS pixels. One CSS pixel is one dp on the tablet. */
val Float.px: TextUnit get() = this.sp
