package com.oldalexhub.paperrescue.scanner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.TransformExperimental
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.oldalexhub.paperrescue.OpenCVStatus
import com.oldalexhub.paperrescue.BuildConfig
import com.oldalexhub.paperrescue.R
import com.oldalexhub.paperrescue.util.BitmapIO
import com.oldalexhub.paperrescue.util.Paths
import com.oldalexhub.paperrescue.vision.DocScanCV
import com.oldalexhub.paperrescue.vision.DetectionMode
import com.oldalexhub.paperrescue.vision.DocumentTracker
import com.oldalexhub.paperrescue.vision.LiveFrameQuality
import com.oldalexhub.paperrescue.vision.QuadGeometry
import com.oldalexhub.paperrescue.vision.QuadRefiner
import com.oldalexhub.paperrescue.vision.RescueFusion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.opencv.core.Point
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Full-screen native camera experience for both Normal Scan and Rescue Scan.
 * Returns its result via [Activity.setResult] extras, parsed by ScannerModule.
 */
@ExperimentalCamera2Interop
@TransformExperimental
class ScannerActivity : AppCompatActivity() {

    private enum class Mode { NORMAL, RESCUE }
    private enum class UiState { LIVE, PROCESSING, REVIEW }
    private data class CaptureProcessingResult(
        val correctedPath: String,
        val normalizedCorners: DoubleArray,
        val confidence: Double,
        val autoCropSucceeded: Boolean,
    )

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: DocumentOverlayView
    private lateinit var reviewImageView: ImageView
    private lateinit var topBar: LinearLayout
    private lateinit var guidanceText: TextView
    private lateinit var pageCounterText: TextView
    private lateinit var processingText: TextView
    private lateinit var btnClose: ImageButton
    private lateinit var btnFlash: ImageButton
    private lateinit var btnGallery: ImageButton
    private lateinit var btnCapture: ImageButton
    private lateinit var btnModeNormal: TextView
    private lateinit var btnModeRescue: TextView
    private lateinit var btnRetake: LinearLayout
    private lateinit var btnUsePhoto: LinearLayout
    private lateinit var bottomControlsLive: LinearLayout
    private lateinit var bottomControlsReview: LinearLayout
    private lateinit var processingOverlay: LinearLayout

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var flashOn = false
    private var mode = Mode.NORMAL
    private var uiState = UiState.LIVE
    private var capturing = false

    private var lastRawPath: String? = null
    private var lastCorrectedPath: String? = null
    /** Corners as fractions (0..1) of the raw image's width/height — resolution-independent. */
    private var lastNormalizedCorners: DoubleArray? = null
    private var lastRescueReport: RescueFusion.Report? = null
    private var rescueSessionDir: File? = null

    private val documentTracker = DocumentTracker()
    private val imageProxyTransformFactory = ImageProxyTransformFactory().apply {
        setUsingRotationDegrees(false)
        setUsingCropRect(true)
    }
    @Volatile private var focusConverged: Boolean? = null
    @Volatile private var exposureConverged: Boolean? = null
    private var latestDocumentCenterInView: PointF? = null
    private var lastDetectionConfidence = 0.0
    private var lastAutoCropSucceeded = true
    private var lastAnalysisAtMs = 0L

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var analysisExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_scanner)
        analysisExecutor = Executors.newSingleThreadExecutor()

        bindViews()
        applyWindowInsets()
        // TextureView instead of SurfaceView: keeps the overlaid controls' touch
        // dispatch fully normal (SurfaceView's separate compositor window can
        // otherwise confuse hit-testing under overlapping siblings on some devices).
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        mode = if (intent.getStringExtra(EXTRA_MODE) == "rescue") Mode.RESCUE else Mode.NORMAL
        val pageNumber = intent.getIntExtra(EXTRA_PAGE_NUMBER, 1)
        pageCounterText.text = getString(R.string.scanner_page_format, pageNumber)
        updateModeUi()
        wireControls()

        if (!OpenCVStatus.isReady) {
            finishWithError(ERROR_PROCESSING_FAILED)
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            previewView.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        }
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        reviewImageView = findViewById(R.id.reviewImageView)
        topBar = findViewById(R.id.topBar)
        guidanceText = findViewById(R.id.guidanceText)
        pageCounterText = findViewById(R.id.pageCounterText)
        processingText = findViewById(R.id.processingText)
        btnClose = findViewById(R.id.btnClose)
        btnFlash = findViewById(R.id.btnFlash)
        btnGallery = findViewById(R.id.btnGallery)
        btnCapture = findViewById(R.id.btnCapture)
        btnModeNormal = findViewById(R.id.btnModeNormal)
        btnModeRescue = findViewById(R.id.btnModeRescue)
        btnRetake = findViewById(R.id.btnRetake)
        btnUsePhoto = findViewById(R.id.btnUsePhoto)
        bottomControlsLive = findViewById(R.id.bottomControlsLive)
        bottomControlsReview = findViewById(R.id.bottomControlsReview)
        processingOverlay = findViewById(R.id.processingOverlay)
    }

    /**
     * targetSdk 36 enforces edge-to-edge, so this Activity's content draws
     * behind the status bar and the gesture/3-button navigation area by
     * default. ReactActivity gets this handled for free (gradle.properties'
     * edgeToEdgeEnabled), but that flag explicitly does not apply to a plain
     * Activity like this one — so the top and bottom control bars need their
     * own padding pushed out by the system bar insets, or they'd render
     * partly underneath the status bar / nav bar on real devices.
     */
    private fun applyWindowInsets() {
        val topBarBasePaddingTop = topBar.paddingTop
        val bottomLiveBasePadding = bottomControlsLive.paddingBottom
        val bottomReviewBasePadding = bottomControlsReview.paddingBottom
        val guidanceBaseMarginTop = (guidanceText.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.updatePadding(top = topBarBasePaddingTop + bars.top)
            bottomControlsLive.updatePadding(bottom = bottomLiveBasePadding + bars.bottom)
            bottomControlsReview.updatePadding(bottom = bottomReviewBasePadding + bars.bottom)
            (guidanceText.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin = guidanceBaseMarginTop + bars.top
            guidanceText.requestLayout()
            windowInsets
        }
    }

    private fun wireControls() {
        btnClose.setOnClickListener {
            if (uiState == UiState.LIVE) {
                setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_STATUS, "cancelled"))
                finish()
            }
        }
        btnFlash.setOnClickListener {
            flashOn = !flashOn
            camera?.cameraControl?.enableTorch(flashOn)
            btnFlash.setImageResource(if (flashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        }
        btnGallery.setOnClickListener {
            setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_STATUS, "use_gallery"))
            finish()
        }
        btnCapture.setOnClickListener { onCaptureClicked() }
        btnModeNormal.setOnClickListener { mode = Mode.NORMAL; updateModeUi() }
        btnModeRescue.setOnClickListener { mode = Mode.RESCUE; updateModeUi() }
        btnRetake.setOnClickListener { returnToLiveCamera() }
        btnUsePhoto.setOnClickListener { finishWithCapturedResult() }
    }

    private fun updateModeUi() {
        btnModeNormal.setBackgroundResource(if (mode == Mode.NORMAL) R.drawable.bg_mode_pill_selected else R.drawable.bg_mode_pill_unselected)
        btnModeRescue.setBackgroundResource(if (mode == Mode.RESCUE) R.drawable.bg_mode_pill_selected else R.drawable.bg_mode_pill_unselected)
        btnCapture.setBackgroundResource(if (mode == Mode.RESCUE) R.drawable.bg_capture_button_rescue else R.drawable.bg_capture_button)
    }

    // ---- Camera setup ----

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                bindUseCases(provider)
            } catch (e: Exception) {
                finishWithError(ERROR_CAMERA_UNAVAILABLE)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
        val preview = Preview.Builder().setTargetRotation(rotation).build().also { it.surfaceProvider = previewView.surfaceProvider }
        val capture = ImageCapture.Builder()
            .setTargetRotation(rotation)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
        val analysisBuilder = ImageAnalysis.Builder()
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        Camera2Interop.Extender(analysisBuilder).setSessionCaptureCallback(
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    focusConverged = when (result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE)) {
                        android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
                        android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED,
                        android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                        android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> true
                        android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN,
                        android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> false
                        else -> null
                    }
                    exposureConverged = when (result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)) {
                        android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED,
                        android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_LOCKED,
                        android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> true
                        android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_SEARCHING,
                        android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> false
                        else -> null
                    }
                }
            },
        )
        val analysis = analysisBuilder.build()
        analysis.setAnalyzer(analysisExecutor) { proxy ->
            try {
                analyzeFrame(proxy)
            } finally {
                proxy.close()
            }
        }

        try {
            provider.unbindAll()
            val viewPort = previewView.viewPort ?: throw IllegalStateException("Preview viewport is not ready")
            val useCaseGroup = UseCaseGroup.Builder()
                .setViewPort(viewPort)
                .addUseCase(preview)
                .addUseCase(capture)
                .addUseCase(analysis)
                .build()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, useCaseGroup)
            imageCapture = capture
        } catch (e: Exception) {
            finishWithError(ERROR_CAMERA_UNAVAILABLE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                previewView.post { startCamera() }
            } else {
                finishWithError(ERROR_CAMERA_PERMISSION_DENIED)
            }
        }
    }

    // ---- Live frame analysis: overlay + auto-capture ----

    /**
     * Runs on a throttled background stream purely to draw the live guide box
     * and drive auto-capture; it is a best-effort approximation (analysis
     * resolution can differ slightly from the preview's crop). The actual
     * page corners used for the saved page are always re-detected from the
     * full-resolution captured photo in [processSingleCapture] / Rescue fusion.
     */
    private fun analyzeFrame(proxy: androidx.camera.core.ImageProxy) {
        val now = System.currentTimeMillis()
        if (uiState != UiState.LIVE || now - lastAnalysisAtMs < 170) return
        lastAnalysisAtMs = now
        if (!OpenCVStatus.isReady) return

        // Fast single-pass detection here — this runs on every live frame, so
        // it must stay cheap. The robust multi-threshold ensemble is reserved
        // for the actual capture (see processSingleCapture / RescueFusion).
        val gray = try { proxy.toGrayMat() } catch (e: Exception) { return }
        val srcW = gray.cols(); val srcH = gray.rows()
        val detection = DocScanCV.detectDocument(
            gray,
            DetectionMode.LIVE,
            documentTracker.previousQuad(srcW, srcH),
        )
        val tracking = documentTracker.update(
            detection,
            srcW,
            srcH,
            LiveFrameQuality(
                sharpness = DocScanCV.sharpnessScore(gray),
                glareRatio = DocScanCV.glareRatio(gray),
                meanBrightness = DocScanCV.brightnessStats(gray).first,
                focusConverged = focusConverged,
                exposureConverged = exposureConverged,
            ),
            now,
        )
        val analysisTransform = try { imageProxyTransformFactory.getOutputTransform(proxy) } catch (_: Exception) { null }
        val quad = tracking.smoothedQuad
        val imageArea = (srcW * srcH).toDouble()

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "confidence=${"%.3f".format(detection.confidence)} source=${detection.source} " +
                    "candidates=${detection.diagnostics.candidateCount}/${detection.diagnostics.acceptedCandidateCount} " +
                    "edge=${detection.diagnostics.candidates.firstOrNull()?.edgeStrengths} " +
                    "stability=${"%.3f".format(tracking.stabilityScore)} wait=${tracking.waitingReason} " +
                    "latencyMs=${detection.diagnostics.processingMs}",
            )
        }

        runOnUiThread {
            val previewTransform = previewView.outputTransform
            val mapped = if (quad != null && analysisTransform != null && previewTransform != null) {
                try {
                    val coordinates = FloatArray(8)
                    quad.points.forEachIndexed { index, point ->
                        coordinates[index * 2] = point.x.toFloat()
                        coordinates[index * 2 + 1] = point.y.toFloat()
                    }
                    CoordinateTransform(analysisTransform, previewTransform).mapPoints(coordinates)
                    Array(4) { i -> Point(coordinates[i * 2].toDouble(), coordinates[i * 2 + 1].toDouble()) }
                } catch (_: Exception) { null }
            } else null
            overlayView.setQuad(mapped)
            latestDocumentCenterInView = mapped?.let { points ->
                PointF(points.sumOf { it.x }.toFloat() / 4f, points.sumOf { it.y }.toFloat() / 4f)
            }
            lastDetectionConfidence = detection.confidence
            if (quad == null) {
                guidanceText.text = getString(R.string.scanner_guidance_align)
            } else {
                val area = areaOf(quad.points)
                if (area < imageArea * 0.3) {
                    guidanceText.text = getString(R.string.scanner_guidance_move_closer)
                } else {
                    guidanceText.text = when (tracking.waitingReason) {
                        "focus", "autofocus" -> getString(R.string.scanner_guidance_focus)
                        "glare" -> getString(R.string.scanner_guidance_glare)
                        "exposure", "auto_exposure" -> getString(R.string.scanner_guidance_light)
                        else -> getString(R.string.scanner_guidance_detected)
                    }

                    // ~150ms per tick, so 8 ticks keeps the same ~1.2s settle
                    // time as before despite the faster analysis rate.
                    if (mode == Mode.NORMAL && tracking.readyForAutoCapture && !capturing) {
                        documentTracker.markCaptured(now)
                        onCaptureClicked()
                    }
                }
            }
        }
        gray.release()
    }

    private fun areaOf(points: Array<Point>): Double {
        return QuadGeometry.area(points)
    }

    // ---- Capture flow ----

    private fun onCaptureClicked() {
        if (uiState != UiState.LIVE || capturing) return
        capturing = true
        if (mode == Mode.RESCUE) startRescueCapture() else startSingleCapture()
    }

    private fun setUiState(next: UiState, processingLabelRes: Int? = null) {
        uiState = next
        bottomControlsLive.visibility = if (next == UiState.LIVE) android.view.View.VISIBLE else android.view.View.GONE
        bottomControlsReview.visibility = if (next == UiState.REVIEW) android.view.View.VISIBLE else android.view.View.GONE
        processingOverlay.visibility = if (next == UiState.PROCESSING) android.view.View.VISIBLE else android.view.View.GONE
        reviewImageView.visibility = if (next == UiState.REVIEW) android.view.View.VISIBLE else android.view.View.GONE
        previewView.visibility = if (next == UiState.REVIEW) android.view.View.GONE else android.view.View.VISIBLE
        overlayView.visibility = if (next == UiState.LIVE) android.view.View.VISIBLE else android.view.View.GONE
        guidanceText.visibility = if (next == UiState.LIVE) android.view.View.VISIBLE else android.view.View.GONE
        if (processingLabelRes != null) processingText.setText(processingLabelRes)
    }

    private suspend fun capturePhoto(target: File): File = suspendCoroutine { continuation ->
        val capture = imageCapture
        if (capture == null) {
            continuation.resumeWithException(IllegalStateException("Camera not ready"))
            return@suspendCoroutine
        }
        val options = ImageCapture.OutputFileOptions.Builder(target).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    continuation.resume(target)
                }
                override fun onError(exception: ImageCaptureException) {
                    continuation.resumeWithException(exception)
                }
            },
        )
    }

    private suspend fun focusAndMeterDocument(): Boolean {
        val activeCamera = camera ?: return false
        return try {
            val center = latestDocumentCenterInView
            val viewX = center?.x ?: previewView.width / 2f
            val viewY = center?.y ?: previewView.height / 2f
            val point = previewView.meteringPointFactory.createPoint(viewX, viewY)
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB,
            ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
            val future = activeCamera.cameraControl.startFocusAndMetering(action)
            suspendCancellableCoroutine { continuation ->
                future.addListener({
                    if (continuation.isActive) {
                        val successful = try { future.get().isFocusSuccessful } catch (_: Exception) { false }
                        continuation.resume(successful)
                    }
                }, ContextCompat.getMainExecutor(this))
                continuation.invokeOnCancellation { future.cancel(true) }
            }
        } catch (e: Exception) {
            false
            // Best effort only — capture proceeds with whatever focus/exposure is current.
        }
    }

    private fun startSingleCapture() {
        setUiState(UiState.PROCESSING, R.string.scanner_processing_single)
        activityScope.launch {
            try {
                val file = File(Paths.capturesDir(this@ScannerActivity), "${Paths.newId()}.jpg")
                withTimeoutOrNull(1_600L) { focusAndMeterDocument() }
                capturePhoto(file)
                val processed = withContext(Dispatchers.Default) { processSingleCapture(file) }
                lastRawPath = file.absolutePath
                lastNormalizedCorners = processed.normalizedCorners
                lastCorrectedPath = processed.correctedPath
                lastDetectionConfidence = processed.confidence
                lastAutoCropSucceeded = processed.autoCropSucceeded
                lastRescueReport = null
                showReview(processed.correctedPath)
            } catch (e: Exception) {
                capturing = false
                finishWithError(ERROR_CAPTURE_FAILED)
            }
        }
    }

    private fun processSingleCapture(file: File): CaptureProcessingResult {
        val bitmap = BitmapIO.loadBitmap(file.absolutePath, BitmapIO.MAX_PAGE_DIMENSION)
        val mat = BitmapIO.bitmapToMat(bitmap)
        org.opencv.imgproc.Imgproc.cvtColor(mat, mat, org.opencv.imgproc.Imgproc.COLOR_RGBA2BGR)
        val detection = DocScanCV.detectDocumentScaled(mat)
        val initialQuad = detection.quad ?: insetFrameQuad(mat.cols(), mat.rows())
        val refinement = QuadRefiner.refineDocumentQuad(mat, initialQuad)
        val output = if (detection.isConfident) DocScanCV.warpToQuad(mat, refinement.quad) else mat.clone()
        val rgba = com.oldalexhub.paperrescue.modules.DocumentProcessingModule.toRgba(output)
        val outBitmap = BitmapIO.matToBitmap(rgba)
        val outFile = File(Paths.capturesDir(this), "${Paths.newId()}_corrected.jpg")
        BitmapIO.saveJpeg(outBitmap, outFile, 95)
        val normalized = normalizeQuad(refinement.quad, mat.cols(), mat.rows())
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "still confidence=${detection.confidence} source=${detection.source} " +
                    "candidates=${detection.diagnostics.candidateCount} refined=${refinement.refined} " +
                    "refineReason=${refinement.reason} edgeShift=${refinement.meanEdgeShiftPixels}",
            )
        }
        mat.release(); output.release(); rgba.release(); bitmap.recycle(); outBitmap.recycle()
        return CaptureProcessingResult(outFile.absolutePath, normalized, detection.confidence, detection.isConfident)
    }

    private fun insetFrameQuad(width: Int, height: Int): DocScanCV.Quad {
        val insetX = width * 0.06
        val insetY = height * 0.06
        return DocScanCV.Quad(arrayOf(
            Point(insetX, insetY),
            Point(width - 1.0 - insetX, insetY),
            Point(width - 1.0 - insetX, height - 1.0 - insetY),
            Point(insetX, height - 1.0 - insetY),
        ))
    }

    private fun normalizeQuad(quad: DocScanCV.Quad, width: Int, height: Int): DoubleArray {
        val result = DoubleArray(8)
        quad.points.forEachIndexed { i, p ->
            result[i * 2] = (p.x / width).coerceIn(0.0, 1.0)
            result[i * 2 + 1] = (p.y / height).coerceIn(0.0, 1.0)
        }
        return result
    }

    private fun startRescueCapture() {
        setUiState(UiState.PROCESSING, R.string.scanner_processing_rescue)
        activityScope.launch {
            val sessionDir = Paths.newRescueSessionDir(this@ScannerActivity)
            rescueSessionDir = sessionDir
            try {
                withTimeoutOrNull(1_600L) { focusAndMeterDocument() }
                val framePaths = mutableListOf<String>()
                repeat(RESCUE_BURST_COUNT) { i ->
                    val f = File(sessionDir, "frame_$i.jpg")
                    capturePhoto(f)
                    framePaths.add(f.absolutePath)
                }
                val outFile = File(Paths.capturesDir(this@ScannerActivity), "${Paths.newId()}_rescue.jpg")
                val report = withContext(Dispatchers.Default) {
                    RescueFusion.processFromFiles(framePaths).also {
                        val rgba = com.oldalexhub.paperrescue.modules.DocumentProcessingModule.toRgba(it.output)
                        val outBitmap = BitmapIO.matToBitmap(rgba)
                        BitmapIO.saveJpeg(outBitmap, outFile, 95)
                        outBitmap.recycle()
                        rgba.release()
                        it.output.release()
                    }
                }
                // The reference frame lives inside sessionDir, which is deleted below —
                // copy it to a permanent location first so manual re-crop stays possible.
                val referencePath = framePaths.getOrNull(report.referenceFrameIndex) ?: framePaths.firstOrNull()
                val permanentRawFile = File(Paths.capturesDir(this@ScannerActivity), "${Paths.newId()}_rescue_raw.jpg")
                if (referencePath != null) {
                    File(referencePath).copyTo(permanentRawFile, overwrite = true)
                }
                lastRawPath = if (referencePath != null) permanentRawFile.absolutePath else null
                lastNormalizedCorners = normalizeQuad(report.quad, report.referenceFrameWidth, report.referenceFrameHeight)
                lastCorrectedPath = outFile.absolutePath
                lastDetectionConfidence = report.detectionConfidence
                lastAutoCropSucceeded = report.autoCropSucceeded
                lastRescueReport = report
                showReview(outFile.absolutePath)
            } catch (e: Exception) {
                capturing = false
                finishWithError(ERROR_PROCESSING_FAILED)
            } finally {
                sessionDir.deleteRecursively()
            }
        }
    }

    private fun showReview(imagePath: String) {
        capturing = false
        val bitmap = BitmapFactory.decodeFile(imagePath)
        reviewImageView.setImageBitmap(bitmap)
        setUiState(UiState.REVIEW)
    }

    private fun returnToLiveCamera() {
        reviewImageView.setImageDrawable(null)
        lastRawPath = null; lastCorrectedPath = null; lastNormalizedCorners = null; lastRescueReport = null
        lastDetectionConfidence = 0.0
        lastAutoCropSucceeded = true
        latestDocumentCenterInView = null
        documentTracker.reset()
        setUiState(UiState.LIVE)
    }

    private fun finishWithCapturedResult() {
        val corrected = lastCorrectedPath
        val corners = lastNormalizedCorners
        if (corrected == null || corners == null) {
            returnToLiveCamera()
            return
        }

        val resultIntent = Intent().apply {
            putExtra(EXTRA_STATUS, "captured")
            putExtra(EXTRA_RAW_PATH, lastRawPath)
            putExtra(EXTRA_CORRECTED_PATH, corrected)
            putExtra(EXTRA_CORNERS, corners)
            putExtra(EXTRA_DETECTION_CONFIDENCE, lastDetectionConfidence)
            putExtra(EXTRA_NEEDS_MANUAL_CROP, !lastAutoCropSucceeded)
            lastRescueReport?.let { report ->
                putExtra(EXTRA_RESCUE_FRAMES_CAPTURED, report.framesCaptured)
                putExtra(EXTRA_RESCUE_FRAMES_USABLE, report.framesUsable)
                putExtra(EXTRA_RESCUE_ALIGNMENT_CONFIDENCE, report.alignmentConfidence)
                putExtra(EXTRA_RESCUE_FALLBACK_USED, report.fallbackUsed)
                putExtra(EXTRA_RESCUE_GLARE_FUSED, report.glareRegionsFused)
            }
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun finishWithError(code: String) {
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_STATUS, "error").putExtra(EXTRA_ERROR_CODE, code))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        rescueSessionDir?.deleteRecursively()
        activityScope.cancel()
        analysisExecutor.shutdown()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_PAGE_NUMBER = "pageNumber"
        const val EXTRA_STATUS = "status"
        const val EXTRA_ERROR_CODE = "errorCode"
        const val EXTRA_RAW_PATH = "rawImagePath"
        const val EXTRA_CORRECTED_PATH = "correctedImagePath"
        /** DoubleArray of 8 values, each a 0..1 fraction of rawImagePath's width/height (x0,y0,x1,y1,...). */
        const val EXTRA_CORNERS = "corners"
        const val EXTRA_DETECTION_CONFIDENCE = "detectionConfidence"
        const val EXTRA_NEEDS_MANUAL_CROP = "needsManualCrop"
        const val EXTRA_RESCUE_FRAMES_CAPTURED = "framesCaptured"
        const val EXTRA_RESCUE_FRAMES_USABLE = "framesUsable"
        const val EXTRA_RESCUE_ALIGNMENT_CONFIDENCE = "alignmentConfidence"
        const val EXTRA_RESCUE_FALLBACK_USED = "fallbackUsed"
        const val EXTRA_RESCUE_GLARE_FUSED = "glareRegionsFused"

        const val ERROR_CAMERA_PERMISSION_DENIED = "CAMERA_PERMISSION_DENIED"
        const val ERROR_CAMERA_UNAVAILABLE = "CAMERA_UNAVAILABLE"
        const val ERROR_CAPTURE_FAILED = "CAPTURE_FAILED"
        const val ERROR_PROCESSING_FAILED = "PROCESSING_FAILED"

        private const val REQUEST_CAMERA_PERMISSION = 8801
        private const val RESCUE_BURST_COUNT = 6
        private const val TAG = "PaperRescueDetection"
    }
}
