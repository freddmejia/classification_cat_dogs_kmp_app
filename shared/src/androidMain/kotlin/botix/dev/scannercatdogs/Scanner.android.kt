package botix.dev.scannercatdogs

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SCAN_TIMEOUT_MS = 6000L

@Composable
actual fun rememberScanner(): Scanner {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scanner = remember(context) { AndroidScanner(context.applicationContext, scope) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scanner.onPermissionResult(granted)
    }

    LaunchedEffect(scanner) {
        scanner.permissionRequester = { launcher.launch(Manifest.permission.CAMERA) }
        scanner.refreshPermission()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, scanner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scanner.refreshPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(scanner) {
        onDispose { scanner.release() }
    }

    return scanner
}

@Composable
actual fun CameraPreview(scanner: Scanner, modifier: Modifier) {
    val androidScanner = scanner as? AndroidScanner ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        },
        update = { view -> androidScanner.bind(view, lifecycleOwner) },
    )
}

private class AndroidScanner(
    private val context: Context,
    private val scope: CoroutineScope,
) : Scanner {

    override var scanState: ScanState by mutableStateOf(ScanState.Idle)
        private set

    override var cameraStatus: CameraStatus by mutableStateOf(CameraStatus.Starting)
        private set

    var permissionRequester: (() -> Unit)? = null

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val pendingScan = AtomicBoolean(false)
    private var classifier: AndroidCatDogClassifier? = null
    private var boundTo: LifecycleOwner? = null
    private var released = false

    fun refreshPermission() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            if (cameraStatus != CameraStatus.Unavailable) cameraStatus = CameraStatus.Ready
        } else {
            cameraStatus = CameraStatus.PermissionRequired
        }
    }

    fun onPermissionResult(granted: Boolean) {
        cameraStatus = if (granted) CameraStatus.Ready else CameraStatus.PermissionRequired
    }

    override fun requestPermission() {
        permissionRequester?.invoke()
    }

    override fun reset() {
        scanState = ScanState.Idle
    }

    override fun scan() {
        if (scanState is ScanState.Scanning) return
        scanState = ScanState.Scanning
        pendingScan.set(true)
        scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (pendingScan.compareAndSet(true, false)) {
                scanState = ScanState.Failure("No camera frame arrived.")
            }
        }
    }

    fun bind(view: PreviewView, lifecycleOwner: LifecycleOwner) {
        if (released || boundTo === lifecycleOwner) return
        boundTo = lifecycleOwner
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (released) return@addListener
            runCatching {
                val provider = future.get()
                val preview = Preview.Builder().build().apply {
                    surfaceProvider = view.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .apply { setAnalyzer(analysisExecutor, ::onFrame) }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }.onFailure {
                boundTo = null
                cameraStatus = CameraStatus.Unavailable
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun onFrame(image: ImageProxy) {
        image.use {
            if (released || !pendingScan.compareAndSet(true, false)) return
            val result = runCatching {
                val frame = uprightSquare(it.toBitmap(), it.imageInfo.rotationDegrees)
                val engine = classifier
                    ?: AndroidCatDogClassifier(context.assets).also { created -> classifier = created }
                engine.classify(frame).also { frame.recycle() }
            }
            scope.launch {
                scanState = result.fold(
                    onSuccess = { classification -> ScanState.Success(classification) },
                    onFailure = { error -> ScanState.Failure(error.message ?: "Classification failed.") },
                )
            }
        }
    }

    private fun uprightSquare(source: Bitmap, rotationDegrees: Int): Bitmap {
        val size = minOf(source.width, source.height)
        val left = (source.width - size) / 2
        val top = (source.height - size) / 2
        val matrix = Matrix().apply {
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(source, left, top, size, size, matrix, true)
    }

    fun release() {
        released = true
        pendingScan.set(false)
        analysisExecutor.shutdown()
        runCatching { analysisExecutor.awaitTermination(500, TimeUnit.MILLISECONDS) }
        classifier?.close()
        classifier = null
    }
}
