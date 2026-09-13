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

    override var lens: Lens by mutableStateOf(Lens.BACK)
        private set

    override var canSwitchLens: Boolean by mutableStateOf(false)
        private set

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val pendingScan = AtomicBoolean(false)
    private var classifier: AndroidCatDogClassifier? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var owner: LifecycleOwner? = null
    private var boundLens: Lens? = null
    private var boundOwner: LifecycleOwner? = null
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

    override fun switchLens() {
        if (!canSwitchLens) return
        lens = if (lens == Lens.BACK) Lens.FRONT else Lens.BACK
        cameraProvider?.let { bindUseCases(it) }
    }

    fun bind(view: PreviewView, lifecycleOwner: LifecycleOwner) {
        if (released) return
        previewView = view
        owner = lifecycleOwner
        val existing = cameraProvider
        if (existing != null) {
            bindUseCases(existing)
            return
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (released) return@addListener
            runCatching { future.get() }
                .onSuccess { provider ->
                    cameraProvider = provider
                    bindUseCases(provider)
                    detectLenses(provider)
                }
                .onFailure { cameraStatus = CameraStatus.Unavailable }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun detectLenses(provider: ProcessCameraProvider) {
        val facings = runCatching {
            provider.availableCameraInfos.mapNotNull { runCatching { it.lensFacing }.getOrNull() }
        }.getOrDefault(emptyList())
        canSwitchLens = facings.contains(CameraSelector.LENS_FACING_BACK) &&
            facings.contains(CameraSelector.LENS_FACING_FRONT)
    }

    private fun selectorFor(value: Lens): CameraSelector = when (value) {
        Lens.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
        Lens.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        if (released) return
        val view = previewView ?: return
        val lifecycleOwner = owner ?: return
        if (boundLens == lens && boundOwner === lifecycleOwner) return

        val attempts = if (lens == Lens.BACK) listOf(Lens.BACK, Lens.FRONT) else listOf(Lens.FRONT, Lens.BACK)
        for (attempt in attempts) {
            val bound = runCatching {
                val preview = Preview.Builder().build().apply {
                    surfaceProvider = view.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .apply { setAnalyzer(analysisExecutor, ::onFrame) }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selectorFor(attempt), preview, analysis)
            }.isSuccess
            if (bound) {
                lens = attempt
                boundLens = attempt
                boundOwner = lifecycleOwner
                cameraStatus = CameraStatus.Ready
                return
            }
        }
        boundLens = null
        boundOwner = null
        cameraStatus = CameraStatus.Unavailable
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
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
        previewView = null
        owner = null
        boundOwner = null
        boundLens = null
        analysisExecutor.shutdown()
        runCatching { analysisExecutor.awaitTermination(500, TimeUnit.MILLISECONDS) }
        classifier?.close()
        classifier = null
    }
}
