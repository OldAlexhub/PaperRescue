package com.oldalexhub.paperrescue.modules

import android.app.Activity
import android.content.Intent
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.oldalexhub.paperrescue.util.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Wraps Google's ML Kit Document Scanner (a Play Services delivered, ML-based
 * scanning UI) for Normal Scan: live edge detection, auto-capture, manual
 * crop/rotate, filters, and multi-page sessions all come from Google's own
 * production model rather than a hand-tuned OpenCV heuristic. This is what
 * gives Normal Scan a CamScanner-caliber feel. Rescue Scan is unaffected —
 * it keeps its own CameraX + OpenCV burst-fusion pipeline, since multi-frame
 * fusion has no equivalent here.
 *
 * Falls back cleanly: [startScan] rejects with E_MLKIT_SCANNER_UNAVAILABLE on
 * unsupported/low-RAM devices or any Play Services failure, so JS can fall
 * back to the custom ScannerActivity flow instead of breaking scanning.
 */
class MlkitScannerModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), ActivityEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingPromise: Promise? = null

    init {
        reactContext.addActivityEventListener(this)
    }

    override fun getName() = "PaperRescueMlkitScanner"

    @ReactMethod
    fun startScan(promise: Promise) {
        val activity = reactContext.currentActivity
        if (activity == null) {
            promise.reject("E_NO_ACTIVITY", "App is not in the foreground.")
            return
        }
        if (pendingPromise != null) {
            promise.reject("E_BUSY", "A scan is already in progress.")
            return
        }

        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        pendingPromise = promise
        try {
            GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
                .addOnSuccessListener { intentSender ->
                    try {
                        activity.startIntentSenderForResult(intentSender, REQUEST_CODE, null, 0, 0, 0, null)
                    } catch (e: Exception) {
                        pendingPromise = null
                        promise.reject("E_MLKIT_SCANNER_UNAVAILABLE", e.message, e)
                    }
                }
                .addOnFailureListener { exception ->
                    pendingPromise = null
                    promise.reject("E_MLKIT_SCANNER_UNAVAILABLE", exception.message, exception)
                }
        } catch (e: Exception) {
            pendingPromise = null
            promise.reject("E_MLKIT_SCANNER_UNAVAILABLE", e.message, e)
        }
    }

    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_CODE) return
        val promise = pendingPromise ?: return
        pendingPromise = null

        if (resultCode != Activity.RESULT_OK || data == null) {
            promise.resolve(Arguments.createMap().apply { putString("status", "cancelled") })
            return
        }

        val result = try {
            GmsDocumentScanningResult.fromActivityResultIntent(data)
        } catch (e: Exception) {
            promise.reject("E_MLKIT_SCANNER_FAILED", e.message, e)
            return
        }

        val pages = result?.pages
        if (result == null || pages.isNullOrEmpty()) {
            promise.resolve(Arguments.createMap().apply { putString("status", "cancelled") })
            return
        }

        scope.launch {
            try {
                val importsDir = Paths.importsDir(reactContext)
                val pathsArray = Arguments.createArray()
                for (page in pages) {
                    val outFile = File(importsDir, "${Paths.newId()}_mlkit.jpg")
                    reactContext.contentResolver.openInputStream(page.imageUri)?.use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (outFile.exists() && outFile.length() > 0) {
                        pathsArray.pushString(outFile.absolutePath)
                    }
                }
                val response = Arguments.createMap()
                response.putString("status", "captured")
                response.putArray("pages", pathsArray)
                promise.resolve(response)
            } catch (e: Exception) {
                promise.reject("E_MLKIT_SCANNER_FAILED", e.message, e)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {}

    companion object {
        private const val REQUEST_CODE = 9424
    }
}
