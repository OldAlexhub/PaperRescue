package com.oldalexhub.paperrescue.scanner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
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
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.oldalexhub.paperrescue.OpenCVStatus
import com.oldalexhub.paperrescue.R
import com.oldalexhub.paperrescue.util.BitmapIO
import com.oldalexhub.paperrescue.util.Paths
import com.oldalexhub.paperrescue.vision.DocScanCV
import com.oldalexhub.paperrescue.vision.RescueFusion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Point
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.hypot

/**
 * Full-screen native camera experience for both Normal Scan and Rescue Scan.
 * Returns its result via [Activity.setResult] extras, parsed by ScannerModule.
 */
class ScannerActivity : AppCompatActivity() {

    private enum class Mode { NORMAL, RESCUE }
    private enum class UiState { LIVE, PROCESSING, REVIEW }

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: DocumentOverlayView
    private lateinit var reviewImageView: ImageView
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

    private var stableFrameCount = 0
    private var lastCentroid: Point? = null
    private var lastAnalysisAtMs = 0L

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var analysisExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)
        analysisExecutor = Executors.newSingleThreadExecutor()

        bindViews()
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
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        }
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        reviewImageView = findViewById(R.id.reviewImageView)
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
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisExecutor) { proxy ->
            try {
                analyzeFrame(proxy)
            } finally {
                proxy.close()
            }
        }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
            imageCapture = capture
        } catch (e: Exception) {
            finishWithError(ERROR_CAMERA_UNAVAILABLE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
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
        if (uiState != UiState.LIVE || now - lastAnalysisAtMs < 350) return
        lastAnalysisAtMs = now
        if (!OpenCVStatus.isReady) return

        val gray = try { proxy.toGrayMat() } catch (e: Exception) { return }
        val quad = DocScanCV.findDocumentQuad(gray)
        val srcW = gray.cols(); val srcH = gray.rows()
        val imageArea = (srcW * srcH).toDouble()

        runOnUiThread {
            overlayView.setQuad(quad?.points, srcW, srcH)
            if (quad == null) {
                guidanceText.text = getString(R.string.scanner_guidance_align)
                stableFrameCount = 0
                lastCentroid = null
            } else {
                val area = areaOf(quad.points)
                if (area < imageArea * 0.3) {
                    guidanceText.text = getString(R.string.scanner_guidance_move_closer)
                    stableFrameCount = 0
                } else {
                    guidanceText.text = getString(R.string.scanner_guidance_detected)
                    val centroid = centroidOf(quad.points)
                    val moved = lastCentroid?.let { hypot(it.x - centroid.x, it.y - centroid.y) } ?: Double.MAX_VALUE
                    if (moved < srcW * 0.02) stableFrameCount++ else stableFrameCount = 0
                    lastCentroid = centroid

                    if (mode == Mode.NORMAL && stableFrameCount >= 4 && !capturing) {
                        onCaptureClicked()
                    }
                }
            }
        }
        gray.release()
    }

    private fun areaOf(points: Array<Point>): Double {
        var area = 0.0
        for (i in points.indices) {
            val p1 = points[i]; val p2 = points[(i + 1) % points.size]
            area += p1.x * p2.y - p2.x * p1.y
        }
        return kotlin.math.abs(area) / 2.0
    }

    private fun centroidOf(points: Array<Point>): Point {
        val cx = points.sumOf { it.x } / points.size
        val cy = points.sumOf { it.y } / points.size
        return Point(cx, cy)
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
        capture.targetRotation = windowManager.defaultDisplay.rotation
        val options = ImageCapture.OutputFileOptions.Builder(target).build()
        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            options,
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

    private fun triggerCenterFocus() {
        try {
            val factory = previewView.meteringPointFactory
            val point = factory.createPoint(previewView.width / 2f, previewView.height / 2f)
            val action = FocusMeteringAction.Builder(point).build()
            camera?.cameraControl?.startFocusAndMetering(action)
        } catch (e: Exception) {
            // Best effort only — capture proceeds with whatever focus/exposure is current.
        }
    }

    private fun startSingleCapture() {
        setUiState(UiState.PROCESSING, R.string.scanner_processing_single)
        activityScope.launch {
            try {
                val file = File(Paths.capturesDir(this@ScannerActivity), "${Paths.newId()}.jpg")
                capturePhoto(file)
                val (correctedPath, normalizedCorners) = withContext(Dispatchers.Default) { processSingleCapture(file) }
                lastRawPath = file.absolutePath
                lastNormalizedCorners = normalizedCorners
                lastCorrectedPath = correctedPath
                lastRescueReport = null
                showReview(correctedPath)
            } catch (e: Exception) {
                capturing = false
                finishWithError(ERROR_CAPTURE_FAILED)
            }
        }
    }

    private fun processSingleCapture(file: File): Pair<String, DoubleArray> {
        val bitmap = BitmapIO.loadBitmap(file.absolutePath, BitmapIO.MAX_PAGE_DIMENSION)
        val mat = BitmapIO.bitmapToMat(bitmap)
        org.opencv.imgproc.Imgproc.cvtColor(mat, mat, org.opencv.imgproc.Imgproc.COLOR_RGBA2BGR)
        val quad = DocScanCV.findDocumentQuad(mat) ?: DocScanCV.fullFrameQuad(mat)
        val warped = DocScanCV.warpToQuad(mat, quad)
        val outBitmap = BitmapIO.matToBitmap(com.oldalexhub.paperrescue.modules.DocumentProcessingModule.toRgba(warped))
        val outFile = File(Paths.capturesDir(this), "${Paths.newId()}_corrected.jpg")
        BitmapIO.saveJpeg(outBitmap, outFile, 95)
        val normalized = normalizeQuad(quad, mat.cols(), mat.rows())
        mat.release(); warped.release(); bitmap.recycle(); outBitmap.recycle()
        return Pair(outFile.absolutePath, normalized)
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
                withContext(Dispatchers.Main) { triggerCenterFocus() }
                val framePaths = mutableListOf<String>()
                repeat(RESCUE_BURST_COUNT) { i ->
                    val f = File(sessionDir, "frame_$i.jpg")
                    capturePhoto(f)
                    framePaths.add(f.absolutePath)
                }
                val outFile = File(Paths.capturesDir(this@ScannerActivity), "${Paths.newId()}_rescue.jpg")
                val report = withContext(Dispatchers.Default) {
                    RescueFusion.processFromFiles(framePaths).also {
                        val outBitmap = BitmapIO.matToBitmap(com.oldalexhub.paperrescue.modules.DocumentProcessingModule.toRgba(it.output))
                        BitmapIO.saveJpeg(outBitmap, outFile, 95)
                        outBitmap.recycle()
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
        stableFrameCount = 0
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
    }
}
