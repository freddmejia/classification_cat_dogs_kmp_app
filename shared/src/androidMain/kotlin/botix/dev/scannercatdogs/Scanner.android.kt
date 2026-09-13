package botix.dev.scannercatdogs

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val FIRST_FRAME_TIMEOUT_MS = 6000L
private const val SMOOTHING = 0.35f
private const val MIN_FRAME_INTERVAL_MS = 120L

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
            when (event) {
                Lifecycle.Event.ON_RESUME -> scanner.refreshPermission()
                Lifecycle.Event.ON_PAUSE -> scanner.stop()
                else -> Unit
            }
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

    override var isRunning: Boolean by mutableStateOf(false)
        private set

    override var cameraStatus: CameraStatus by mutableStateOf(CameraStatus.Starting)
        private set

    override var lens: Lens by mutableStateOf(Lens.BACK)
        private set

    override var canSwitchLens: Boolean by mutableStateOf(false)
        private set

    var permissionRequester: (() -> Unit)? = null

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var running = false

    @Volatile
    private var smoothed: Float? = null

    @Volatile
    private var lastFrameAt = 0L

    private var classifier: AndroidCatDogClassifier? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
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

    override fun start() {
        if (released || isRunning) return
        isRunning = true
        running = true
        smoothed = null
        scanState = ScanState.Waiting
        lastFrameAt = 0L
        attachAnalyzer()
        scope.launch {
            delay(FIRST_FRAME_TIMEOUT_MS)
            if (running && scanState is ScanState.Waiting) {
                stop()
                scanState = ScanState.Failure("No camera frames arrived.")
            }
        }
    }

    override fun stop() {
        if (!isRunning) return
        running = false
        isRunning = false
        runCatching { analysis?.clearAnalyzer() }
        if (scanState is ScanState.Waiting) scanState = ScanState.Idle
    }

    override fun switchLens() {
        if (!canSwitchLens) return
        lens = if (lens == Lens.BACK) Lens.FRONT else Lens.BACK
        smoothed = null
        cameraProvider?.let { bindUseCases(it) }
    }

    private fun attachAnalyzer() {
        val target = analysis ?: return
        if (running) target.setAnalyzer(analysisExecutor, ::onFrame) else target.clearAnalyzer()
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
            var built: ImageAnalysis? = null
            val bound = runCatching {
                val preview = Preview.Builder().build().apply {
                    surfaceProvider = view.surfaceProvider
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                built = imageAnalysis
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selectorFor(attempt), preview, imageAnalysis)
            }.isSuccess
            if (bound) {
                analysis = built
                lens = attempt
                boundLens = attempt
                boundOwner = lifecycleOwner
                cameraStatus = CameraStatus.Ready
                attachAnalyzer()
                return
            }
        }
        analysis = null
        boundLens = null
        boundOwner = null
        cameraStatus = CameraStatus.Unavailable
    }

    private fun onFrame(image: ImageProxy) {
        image.use {
            if (released || !running) return
            val startedAt = System.nanoTime()
            val sinceLast = (startedAt - lastFrameAt) / 1_000_000L
            if (lastFrameAt != 0L && sinceLast < MIN_FRAME_INTERVAL_MS) return
            lastFrameAt = startedAt
            val outcome = runCatching {
                val frame = FramePreparation.uprightSquare(it.toBitmap(), it.imageInfo.rotationDegrees)
                val engine = classifier
                    ?: AndroidCatDogClassifier(context.assets).also { created -> classifier = created }
                engine.classify(frame).also { frame.recycle() }
            }
            val latencyMs = ((System.nanoTime() - startedAt) / 1_000_000L).toInt()
            outcome.fold(
                onSuccess = { classification ->
                    val blended = smooth(classification.dogProbability)
                    scope.launch {
                        if (running) scanState = ScanState.Live(Classification(blended), latencyMs)
                    }
                },
                onFailure = { error ->
                    running = false
                    scope.launch {
                        isRunning = false
                        runCatching { analysis?.clearAnalyzer() }
                        scanState = ScanState.Failure(error.message ?: "Classification failed.")
                    }
                },
            )
        }
    }

    private fun smooth(probability: Float): Float {
        val previous = smoothed
        val blended = if (previous == null) {
            probability
        } else {
            SMOOTHING * probability + (1f - SMOOTHING) * previous
        }
        smoothed = blended
        return blended
    }

    fun release() {
        released = true
        running = false
        isRunning = false
        runCatching { analysis?.clearAnalyzer() }
        runCatching { cameraProvider?.unbindAll() }
        analysis = null
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
