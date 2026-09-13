package com.oldalexhub.paperrescue.modules

import android.app.Activity
import android.content.Intent
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.oldalexhub.paperrescue.scanner.ScannerActivity

/** Launches the native full-screen scanner (Normal Scan / Rescue Scan) and relays its result. */
class ScannerModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), ActivityEventListener {

    private var pendingPromise: Promise? = null

    init {
        reactContext.addActivityEventListener(this)
    }

    override fun getName() = "PaperRescueScanner"

    @ReactMethod
    fun startScan(mode: String, pageNumber: Int, promise: Promise) {
        val activity = reactContext.currentActivity
        if (activity == null) {
            promise.reject("E_NO_ACTIVITY", "App is not in the foreground.")
            return
        }
        if (pendingPromise != null) {
            promise.reject("E_BUSY", "A scan is already in progress.")
            return
        }
        pendingPromise = promise
        val intent = Intent(activity, ScannerActivity::class.java).apply {
            putExtra(ScannerActivity.EXTRA_MODE, mode)
            putExtra(ScannerActivity.EXTRA_PAGE_NUMBER, pageNumber)
        }
        try {
            activity.startActivityForResult(intent, REQUEST_CODE)
        } catch (e: Exception) {
            pendingPromise = null
            promise.reject("E_CAMERA_UNAVAILABLE", e.message, e)
        }
    }

    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_CODE) return
        val promise = pendingPromise ?: return
        pendingPromise = null

        if (data == null) {
            promise.resolve(Arguments.createMap().apply { putString("status", "cancelled") })
            return
        }

        when (data.getStringExtra(ScannerActivity.EXTRA_STATUS)) {
            "captured" -> {
                val corners = data.getDoubleArrayExtra(ScannerActivity.EXTRA_CORNERS)
                val cornersArray = Arguments.createArray()
                corners?.forEach { cornersArray.pushDouble(it) }

                val result = Arguments.createMap().apply {
                    putString("status", "captured")
                    putString("rawImagePath", data.getStringExtra(ScannerActivity.EXTRA_RAW_PATH))
                    putString("correctedImagePath", data.getStringExtra(ScannerActivity.EXTRA_CORRECTED_PATH))
                    putArray("corners", cornersArray)
                }

                if (data.hasExtra(ScannerActivity.EXTRA_RESCUE_FRAMES_CAPTURED)) {
                    val rescue = Arguments.createMap().apply {
                        putInt("framesCaptured", data.getIntExtra(ScannerActivity.EXTRA_RESCUE_FRAMES_CAPTURED, 0))
                        putInt("framesUsable", data.getIntExtra(ScannerActivity.EXTRA_RESCUE_FRAMES_USABLE, 0))
                        putDouble("alignmentConfidence", data.getDoubleExtra(ScannerActivity.EXTRA_RESCUE_ALIGNMENT_CONFIDENCE, 0.0))
                        putBoolean("fallbackUsed", data.getBooleanExtra(ScannerActivity.EXTRA_RESCUE_FALLBACK_USED, false))
                        putBoolean("glareRegionsFused", data.getBooleanExtra(ScannerActivity.EXTRA_RESCUE_GLARE_FUSED, false))
                    }
                    result.putMap("rescueReport", rescue)
                }
                promise.resolve(result)
            }
            "use_gallery" -> promise.resolve(Arguments.createMap().apply { putString("status", "use_gallery") })
            "error" -> {
                val code = data.getStringExtra(ScannerActivity.EXTRA_ERROR_CODE) ?: "UNKNOWN"
                promise.reject(code, "Scanner reported error: $code")
            }
            else -> promise.resolve(Arguments.createMap().apply { putString("status", "cancelled") })
        }
    }

    override fun onNewIntent(intent: Intent) {}

    companion object {
        private const val REQUEST_CODE = 9422
    }
}
