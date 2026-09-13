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
    override val isRunning: Boolean = false
    override val cameraStatus: CameraStatus = CameraStatus.Unavailable
    override val lens: Lens = Lens.BACK
    override val canSwitchLens: Boolean = false
    override fun requestPermission() {}
    override fun switchLens() {}
    override fun start() {}
    override fun stop() {}
}
