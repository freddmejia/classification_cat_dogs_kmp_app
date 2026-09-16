@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package botix.dev.scannercatdogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDeviceRotationCoordinator
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureInput
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPreset1280x720
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.defaultDeviceWithDeviceType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreFoundation.CFRetain
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetPixelFormatType
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_sync

private const val FIRST_FRAME_TIMEOUT_MS = 6000L
private const val SMOOTHING = 0.35f
private const val MIN_FRAME_INTERVAL_MS = 120L
private const val DEFAULT_ROTATION_DEGREES = 90

@Composable
actual fun rememberScanner(): Scanner {
    val scope = rememberCoroutineScope()
    val scanner = remember { IosScanner(scope) }

    LaunchedEffect(scanner) {
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
    val iosScanner = scanner as? IosScanner ?: return
    UIKitView(
        factory = { PreviewHostView(iosScanner::onPreviewLayout) },
        modifier = modifier,
        update = { view -> iosScanner.bind(view) },
        onRelease = { view -> iosScanner.unbind(view) },
    )
}

private class PreviewHostView(
    private val onLayout: (PreviewHostView) -> Unit,
) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {

    val previewLayer = AVCaptureVideoPreviewLayer().apply {
        videoGravity = AVLayerVideoGravityResizeAspectFill
    }

    init {
        backgroundColor = UIColor.blackColor
        layer.addSublayer(previewLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        previewLayer.frame = bounds
        CATransaction.commit()
        onLayout(this)
    }
}

private class FrameDelegate(
    private val onFrame: (CMSampleBufferRef) -> Unit,
) : NSObject(), AVCaptureVideoDataOutputSampleBufferDelegateProtocol {

    @ObjCSignatureOverride
    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputSampleBuffer: CMSampleBufferRef?,
        fromConnection: AVCaptureConnection,
    ) {
        didOutputSampleBuffer?.let(onFrame)
    }
}

private class IosScanner(
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

    private val session = AVCaptureSession()
    private val output = AVCaptureVideoDataOutput()
    private val delegate = FrameDelegate(::onFrame)
    private val sessionQueue = dispatch_queue_create("botix.dev.scannercatdogs.session", null)
    private val videoQueue = dispatch_queue_create("botix.dev.scannercatdogs.video", null)

    @Volatile
    private var running = false

    @Volatile
    private var smoothed: Float? = null

    @Volatile
    private var lastFrameAt: TimeSource.Monotonic.ValueTimeMark? = null

    @Volatile
    private var rotationCoordinator: AVCaptureDeviceRotationCoordinator? = null

    @Volatile
    private var regionOfInterest: NormalizedRect? = null

    private var classifier: IosCatDogClassifier? = null
    private var hostView: PreviewHostView? = null
    private var boundLens: Lens? = null
    private var boundDevice: AVCaptureDevice? = null
    private var lensesDetected = false
    private var released = false

    fun refreshPermission() {
        if (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusAuthorized) {
            if (cameraStatus != CameraStatus.Unavailable) cameraStatus = CameraStatus.Ready
        } else {
            cameraStatus = CameraStatus.PermissionRequired
        }
    }

    private fun onPermissionResult(granted: Boolean) {
        cameraStatus = if (granted) CameraStatus.Ready else CameraStatus.PermissionRequired
    }

    override fun requestPermission() {
        if (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusNotDetermined) {
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                scope.launch { onPermissionResult(granted) }
            }
        } else {
            val settings = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
            UIApplication.sharedApplication.openURL(settings, emptyMap<Any?, Any?>(), null)
        }
    }

    override fun start() {
        if (released || isRunning) return
        isRunning = true
        running = true
        smoothed = null
        scanState = ScanState.Waiting
        lastFrameAt = null
        attachDelegate()
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
        output.setSampleBufferDelegate(null, null)
        if (scanState is ScanState.Waiting) scanState = ScanState.Idle
    }

    override fun switchLens() {
        if (!canSwitchLens) return
        lens = if (lens == Lens.BACK) Lens.FRONT else Lens.BACK
        smoothed = null
        configureSession()
    }

    private fun attachDelegate() {
        if (boundLens == null) return
        if (running) {
            output.setSampleBufferDelegate(delegate, videoQueue)
        } else {
            output.setSampleBufferDelegate(null, null)
        }
    }

    fun bind(view: PreviewHostView) {
        if (released) return
        if (hostView !== view) {
            hostView = view
            view.previewLayer.session = session
            boundDevice?.let { rotationCoordinator = AVCaptureDeviceRotationCoordinator(it, view.previewLayer) }
        }
        if (!lensesDetected) {
            lensesDetected = true
            canSwitchLens = deviceFor(Lens.BACK) != null && deviceFor(Lens.FRONT) != null
        }
        configureSession()
    }

    fun unbind(view: PreviewHostView) {
        if (hostView !== view) return
        view.previewLayer.session = null
        hostView = null
    }

    fun onPreviewLayout(view: PreviewHostView) {
        if (hostView !== view) return
        updatePreviewGeometry(view)
    }

    private fun updatePreviewGeometry(view: PreviewHostView) {
        val coordinator = rotationCoordinator
        val connection = view.previewLayer.connection
        if (coordinator != null && connection != null) {
            val angle = coordinator.videoRotationAngleForHorizonLevelPreview
            if (connection.isVideoRotationAngleSupported(angle)) connection.videoRotationAngle = angle
        }
        if (boundLens == null) return
        regionOfInterest = view.previewLayer.metadataOutputRectOfInterestForRect(view.bounds).useContents {
            NormalizedRect(origin.x, origin.y, size.width, size.height)
        }
    }

    private fun deviceFor(value: Lens): AVCaptureDevice? = AVCaptureDevice.defaultDeviceWithDeviceType(
        AVCaptureDeviceTypeBuiltInWideAngleCamera,
        AVMediaTypeVideo,
        if (value == Lens.BACK) AVCaptureDevicePositionBack else AVCaptureDevicePositionFront,
    )

    private fun configureSession() {
        if (released) return
        val view = hostView ?: return
        if (boundLens == lens) return

        val attempts = if (lens == Lens.BACK) listOf(Lens.BACK, Lens.FRONT) else listOf(Lens.FRONT, Lens.BACK)
        for (attempt in attempts) {
            val device = deviceFor(attempt) ?: continue
            val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null) ?: continue
            session.beginConfiguration()
            session.inputs.forEach { session.removeInput(it as AVCaptureInput) }
            if (session.canSetSessionPreset(AVCaptureSessionPreset1280x720)) {
                session.sessionPreset = AVCaptureSessionPreset1280x720
            }
            val inputAdded = session.canAddInput(input).also { if (it) session.addInput(input) }
            val outputReady = session.outputs.contains(output) ||
                session.canAddOutput(output).also { if (it) session.addOutput(output) }
            if (inputAdded && outputReady) {
                output.videoSettings = mapOf<Any?, Any?>(
                    pixelFormatKey() to NSNumber(unsignedInt = kCVPixelFormatType_32BGRA),
                )
                output.alwaysDiscardsLateVideoFrames = true
            }
            session.commitConfiguration()
            if (!inputAdded || !outputReady) continue

            lens = attempt
            boundLens = attempt
            boundDevice = device
            rotationCoordinator = AVCaptureDeviceRotationCoordinator(device, view.previewLayer)
            cameraStatus = CameraStatus.Ready
            attachDelegate()
            dispatch_async(sessionQueue) {
                if (!session.running) session.startRunning()
                dispatch_async(dispatch_get_main_queue()) {
                    hostView?.let { updatePreviewGeometry(it) }
                }
            }
            updatePreviewGeometry(view)
            return
        }
        boundLens = null
        boundDevice = null
        rotationCoordinator = null
        cameraStatus = CameraStatus.Unavailable
    }

    private fun pixelFormatKey(): NSString =
        CFBridgingRelease(CFRetain(kCVPixelBufferPixelFormatTypeKey)) as NSString

    private fun onFrame(sampleBuffer: CMSampleBufferRef) {
        if (released || !running) return
        val startedAt = TimeSource.Monotonic.markNow()
        val last = lastFrameAt
        if (last != null && (startedAt - last).inWholeMilliseconds < MIN_FRAME_INTERVAL_MS) return
        lastFrameAt = startedAt
        val outcome = runCatching {
            val pixelBuffer = checkNotNull(CMSampleBufferGetImageBuffer(sampleBuffer)) { "Empty camera frame." }
            check(CVPixelBufferGetPixelFormatType(pixelBuffer) == kCVPixelFormatType_32BGRA) {
                "Unexpected camera pixel format."
            }
            val rotation = rotationCoordinator?.videoRotationAngleForHorizonLevelCapture?.roundToInt()
                ?: DEFAULT_ROTATION_DEGREES
            CVPixelBufferLockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly)
            try {
                val base = checkNotNull(CVPixelBufferGetBaseAddress(pixelBuffer)) { "Unreadable camera frame." }
                val width = CVPixelBufferGetWidth(pixelBuffer).toInt()
                val height = CVPixelBufferGetHeight(pixelBuffer).toInt()
                val source = PixelSource(
                    data = base.reinterpret<UByteVar>(),
                    width = width,
                    height = height,
                    bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer).toInt(),
                    order = ChannelOrder.BGRA,
                )
                val crop = IosFramePreparation.viewfinderSquare(width, height, regionOfInterest)
                val engine = classifier ?: createClassifier().also { created -> classifier = created }
                engine.classify(source, crop, rotation)
            } finally {
                CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly)
            }
        }
        val latencyMs = startedAt.elapsedNow().inWholeMilliseconds.toInt()
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
                    output.setSampleBufferDelegate(null, null)
                    scanState = ScanState.Failure(error.message ?: "Classification failed.")
                }
            },
        )
    }

    private fun createClassifier(): IosCatDogClassifier {
        val path = checkNotNull(IosCatDogClassifier.bundledModelPath(ModelContract.DEFAULT_MODEL_ASSET)) {
            "Model ${ModelContract.DEFAULT_MODEL_ASSET} is not in the app bundle."
        }
        return IosCatDogClassifier(path)
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
        output.setSampleBufferDelegate(null, null)
        hostView?.previewLayer?.session = null
        hostView = null
        boundLens = null
        boundDevice = null
        rotationCoordinator = null
        dispatch_async(sessionQueue) {
            if (session.running) session.stopRunning()
        }
        dispatch_sync(videoQueue) {}
        classifier?.close()
        classifier = null
    }
}
