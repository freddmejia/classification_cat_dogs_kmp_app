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
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private const val CONFIDENCE_FLOOR = 0.65f

@Composable
fun ScannerScreen(scanner: Scanner = rememberScanner()) {
    val state = scanner.scanState
    val status = scanner.cameraStatus
    val accent by animateColorAsState(accentOf(state), tween(250))

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
                .pinchToZoom(scanner, enabled = status == CameraStatus.Ready && scanner.canZoom)
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
            ResultBanner(state, status, scanner.isRunning, accent, scanner::requestPermission)
            Spacer(Modifier.weight(1f))
            if (status == CameraStatus.Ready) {
                if (scanner.canZoom) {
                    ZoomChip(
                        ratio = scanner.zoomRatio,
                        onReset = { scanner.zoomBy(scanner.minZoomRatio / scanner.zoomRatio) },
                    )
                    Spacer(Modifier.height(16.dp))
                }
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    ScanButton(
                        running = scanner.isRunning,
                        accent = accent,
                        onClick = { if (scanner.isRunning) scanner.stop() else scanner.start() },
                    )
                    if (scanner.canSwitchLens) {
                        LensButton(
                            lens = scanner.lens,
                            onClick = scanner::switchLens,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = when {
                        scanner.isRunning -> "Live - tap stop to end"
                        state is ScanState.Live -> "Stopped - tap scan to resume"
                        else -> "Point at a cat or a dog"
                    },
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
    running: Boolean,
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
            targetState = bannerKind(state, status),
            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) },
        ) { kind ->
            when (kind) {
                BannerKind.Unavailable -> Message(
                    title = "Camera unavailable",
                    body = "No usable camera was found on this device.",
                )

                BannerKind.Permission -> PermissionRequest(onRequestPermission)

                BannerKind.Starting -> Message(
                    title = "Starting camera",
                    body = "One moment.",
                )

                BannerKind.Failure -> Message(
                    title = "Scan failed",
                    body = (state as? ScanState.Failure)?.message ?: "",
                    titleColor = ScannerColors.Danger,
                )

                BannerKind.Waiting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = accent,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(14.dp))
                    Text("Starting live scan", color = ScannerColors.OnSurface, fontSize = 18.sp)
                }

                BannerKind.Result -> {
                    val live = state as? ScanState.Live
                    if (live != null) {
                        ResultContent(
                            classification = live.classification,
                            latencyMs = live.latencyMs,
                            live = running,
                            accent = accent,
                        )
                    }
                }

                BannerKind.Idle -> Message(
                    title = "Ready to scan",
                    body = "Tap scan to start live detection.",
                )
            }
        }
    }
}

@Composable
private fun ResultContent(
    classification: Classification,
    latencyMs: Int,
    live: Boolean,
    accent: Color,
) {
    val animated by animateFloatAsState(classification.confidence, tween(if (live) 160 else 600))
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
                text = when {
                    classification.confidence < CONFIDENCE_FLOOR -> "NOT SURE"
                    classification.label == Label.DOG -> "DOG"
                    else -> "CAT"
                },
                color = ScannerColors.OnSurface,
                fontSize = if (classification.confidence < CONFIDENCE_FLOOR) 30.sp else 44.sp,
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
            text = if (live) {
                "live - dog probability " + decimalText(classification.dogProbability) + " - " + latencyMs + " ms"
            } else {
                "stopped - dog probability " + decimalText(classification.dogProbability)
            },
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
private fun ScanButton(running: Boolean, accent: Color, onClick: () -> Unit) {
    val ring by animateFloatAsState(if (running) 0.9f else 1f, tween(300))
    val fill = if (running) ScannerColors.Danger else ScannerColors.OnSurface
    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .border(3.dp, accent.copy(alpha = ring), CircleShape)
            .padding(7.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (running) "STOP" else "SCAN",
            color = if (running) ScannerColors.OnSurface else ScannerColors.Background,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LensButton(lens: Lens, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(ScannerColors.Surface.copy(alpha = 0.9f))
            .border(1.dp, ScannerColors.Outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (lens == Lens.BACK) "TO\nFRONT" else "TO\nREAR",
            color = ScannerColors.OnSurface,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ZoomChip(ratio: Float, onReset: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(ScannerColors.Surface.copy(alpha = 0.9f))
            .border(1.dp, ScannerColors.Outline, CircleShape)
            .clickable(onClick = onReset)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(
            text = zoomText(ratio),
            color = ScannerColors.OnSurface,
            fontSize = 13.sp,
        )
    }
}

private fun Modifier.pinchToZoom(scanner: Scanner, enabled: Boolean): Modifier =
    if (!enabled) this else pointerInput(scanner) {
        detectTransformGestures(panZoomLock = true) { _, _, zoom, _ -> scanner.zoomBy(zoom) }
    }

private enum class BannerKind { Unavailable, Permission, Starting, Failure, Waiting, Result, Idle }

private fun bannerKind(state: ScanState, status: CameraStatus): BannerKind = when {
    status == CameraStatus.Unavailable -> BannerKind.Unavailable
    status == CameraStatus.PermissionRequired -> BannerKind.Permission
    status == CameraStatus.Starting -> BannerKind.Starting
    state is ScanState.Failure -> BannerKind.Failure
    state is ScanState.Waiting -> BannerKind.Waiting
    state is ScanState.Live -> BannerKind.Result
    else -> BannerKind.Idle
}

private fun accentOf(state: ScanState): Color = when {
    state is ScanState.Live && state.classification.confidence >= CONFIDENCE_FLOOR ->
        accentFor(state.classification.label)
    state is ScanState.Failure -> ScannerColors.Danger
    else -> ScannerColors.Neutral
}

private fun percentText(value: Float): String {
    val percent = (value * 100f).toInt().coerceIn(0, 100)
    return percent.toString() + "%"
}

private fun zoomText(value: Float): String {
    val scaled = (value * 10f).roundToInt().coerceIn(1, 999)
    return (scaled / 10).toString() + "." + (scaled % 10).toString() + "x"
}

private fun decimalText(value: Float): String {
    val scaled = (value * 1000f).toInt().coerceIn(0, 1000)
    return (scaled / 1000).toString() + "." + (scaled % 1000).toString().padStart(3, '0')
}
