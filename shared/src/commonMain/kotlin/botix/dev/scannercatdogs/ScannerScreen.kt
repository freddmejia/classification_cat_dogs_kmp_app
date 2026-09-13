package botix.dev.scannercatdogs

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ScannerScreen(scanner: Scanner = rememberScanner()) {
    val state = scanner.scanState
    val status = scanner.cameraStatus
    val accent by animateColorAsState(accentOf(state), tween(400))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScannerColors.Background),
    ) {
        if (status == CameraStatus.Ready) {
            CameraPreview(scanner, Modifier.fillMaxSize())
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to ScannerColors.Background.copy(alpha = 0.92f),
                        0.32f to Color.Transparent,
                        0.62f to Color.Transparent,
                        1f to ScannerColors.Background.copy(alpha = 0.95f),
                    )
                ),
        )
        if (status == CameraStatus.Ready) {
            Viewfinder(accent, Modifier.align(Alignment.Center))
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeContentPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ResultBanner(state, status, accent, scanner::requestPermission)
            Spacer(Modifier.weight(1f))
            if (status == CameraStatus.Ready) {
                ScanButton(
                    scanning = state is ScanState.Scanning,
                    accent = accent,
                    onClick = scanner::scan,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = if (state is ScanState.Success) "Tap to scan again" else "Point at a cat or a dog",
                    color = ScannerColors.OnSurfaceMuted,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun ResultBanner(
    state: ScanState,
    status: CameraStatus,
    accent: Color,
    onRequestPermission: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(ScannerColors.Surface.copy(alpha = 0.88f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(28.dp))
            .padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
        AnimatedContent(
            targetState = BannerKey(state, status),
            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) },
        ) { key ->
            val keyState = key.state
            when {
                key.status == CameraStatus.Unavailable -> Message(
                    title = "Camera unavailable",
                    body = "This platform has no scanner yet.",
                )

                key.status == CameraStatus.PermissionRequired -> PermissionRequest(onRequestPermission)

                key.status == CameraStatus.Starting -> Message(
                    title = "Starting camera",
                    body = "One moment.",
                )

                keyState is ScanState.Failure -> Message(
                    title = "Scan failed",
                    body = keyState.message,
                    titleColor = ScannerColors.Danger,
                )

                keyState is ScanState.Scanning -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = accent,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(14.dp))
                    Text("Analysing", color = ScannerColors.OnSurface, fontSize = 18.sp)
                }

                keyState is ScanState.Success -> ResultContent(keyState.classification, accent)

                else -> Message(
                    title = "Ready to scan",
                    body = "Frame the animal and tap the button.",
                )
            }
        }
    }
}

@Composable
private fun ResultContent(classification: Classification, accent: Color) {
    val animated by animateFloatAsState(classification.confidence, tween(600))
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (classification.label == Label.DOG) "DOG" else "CAT",
                color = ScannerColors.OnSurface,
                fontSize = 44.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = percentText(classification.confidence),
                color = accent,
                fontSize = 26.sp,
            )
        }
        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(ScannerColors.SurfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "dog probability " + decimalText(classification.dogProbability),
            color = ScannerColors.OnSurfaceMuted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun PermissionRequest(onRequestPermission: () -> Unit) {
    Column {
        Text("Camera access needed", color = ScannerColors.OnSurface, fontSize = 20.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "The scanner reads frames from the camera. Nothing leaves the device.",
            color = ScannerColors.OnSurfaceMuted,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(ScannerColors.OnSurface)
                .clickable(onClick = onRequestPermission)
                .padding(horizontal = 20.dp, vertical = 11.dp),
        ) {
            Text("Allow camera", color = ScannerColors.Background, fontSize = 14.sp)
        }
    }
}

@Composable
private fun Message(title: String, body: String, titleColor: Color = ScannerColors.OnSurface) {
    Column {
        Text(title, color = titleColor, fontSize = 20.sp)
        Spacer(Modifier.height(6.dp))
        Text(body, color = ScannerColors.OnSurfaceMuted, fontSize = 13.sp)
    }
}

@Composable
private fun Viewfinder(accent: Color, modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth(0.84f)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(32.dp))
            .border(2.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(32.dp)),
    )
}

@Composable
private fun ScanButton(scanning: Boolean, accent: Color, onClick: () -> Unit) {
    val ring by animateFloatAsState(if (scanning) 0.3f else 1f, tween(300))
    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .border(3.dp, accent.copy(alpha = ring), CircleShape)
            .padding(7.dp)
            .clip(CircleShape)
            .background(if (scanning) ScannerColors.SurfaceVariant else ScannerColors.OnSurface)
            .clickable(enabled = !scanning, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (scanning) {
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                color = accent,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = "SCAN",
                color = ScannerColors.Background,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private data class BannerKey(val state: ScanState, val status: CameraStatus)

private fun accentOf(state: ScanState): Color = when (state) {
    is ScanState.Success -> accentFor(state.classification.label)
    is ScanState.Failure -> ScannerColors.Danger
    else -> ScannerColors.Neutral
}

private fun percentText(value: Float): String {
    val percent = (value * 100f).toInt().coerceIn(0, 100)
    return percent.toString() + "%"
}

private fun decimalText(value: Float): String {
    val scaled = (value * 1000f).toInt().coerceIn(0, 1000)
    return (scaled / 1000).toString() + "." + (scaled % 1000).toString().padStart(3, '0')
}
