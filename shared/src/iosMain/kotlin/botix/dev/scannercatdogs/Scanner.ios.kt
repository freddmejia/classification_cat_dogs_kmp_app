package botix.dev.scannercatdogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

@Composable
actual fun rememberScanner(): Scanner = remember { IOSScanner }

@Composable
actual fun CameraPreview(scanner: Scanner, modifier: Modifier) {
}

private object IOSScanner : Scanner {
    override val scanState: ScanState = ScanState.Idle
    override val cameraStatus: CameraStatus = CameraStatus.Unavailable
    override fun requestPermission() {}
    override fun scan() {}
    override fun reset() {}
}
