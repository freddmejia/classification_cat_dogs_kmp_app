package botix.dev.scannercatdogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier

sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
    data class Success(val classification: Classification) : ScanState
    data class Failure(val message: String) : ScanState
}

enum class CameraStatus { Starting, PermissionRequired, Ready, Unavailable }

@Stable
interface Scanner {
    val scanState: ScanState
    val cameraStatus: CameraStatus
    fun requestPermission()
    fun scan()
    fun reset()
}

@Composable
expect fun rememberScanner(): Scanner

@Composable
expect fun CameraPreview(scanner: Scanner, modifier: Modifier)
