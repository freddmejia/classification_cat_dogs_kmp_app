package botix.dev.scannercatdogs

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object ScannerColors {
    val Background = Color(0xFF07070B)
    val Surface = Color(0xFF14141C)
    val SurfaceVariant = Color(0xFF1E1E29)
    val Outline = Color(0xFF2E2E3E)
    val OnSurface = Color(0xFFF3F3F8)
    val OnSurfaceMuted = Color(0xFF9A9AAE)
    val Dog = Color(0xFFFFB020)
    val Cat = Color(0xFFA78BFA)
    val Neutral = Color(0xFF6B7A99)
    val Danger = Color(0xFFFF6B6B)
}

fun accentFor(label: Label): Color = when (label) {
    Label.DOG -> ScannerColors.Dog
    Label.CAT -> ScannerColors.Cat
}

private val scannerTypography = Typography(
    displayLarge = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp),
)

@Composable
fun ScannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ScannerColors.OnSurface,
            background = ScannerColors.Background,
            surface = ScannerColors.Surface,
            surfaceVariant = ScannerColors.SurfaceVariant,
            onSurface = ScannerColors.OnSurface,
            onBackground = ScannerColors.OnSurface,
            outline = ScannerColors.Outline,
            error = ScannerColors.Danger,
        ),
        typography = scannerTypography,
        content = content,
    )
}
