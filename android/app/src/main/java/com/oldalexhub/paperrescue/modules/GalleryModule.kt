package com.oldalexhub.paperrescue.modules

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.oldalexhub.paperrescue.util.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Imports images using Android's own system picker (Photo Picker on Android 13+,
 * the Storage Access Framework document picker as a compatible fallback below
 * that) — no broad storage permission is ever requested.
 */
class GalleryModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), ActivityEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingPromise: Promise? = null

    init {
        reactContext.addActivityEventListener(this)
    }

    override fun getName() = "PaperRescueGallery"

    @ReactMethod
    fun pickImages(maxCount: Int, promise: Promise) {
        val activity = currentActivity
        if (activity == null) {
            promise.reject("E_NO_ACTIVITY", "App is not in the foreground.")
            return
        }
        if (pendingPromise != null) {
            promise.reject("E_BUSY", "An import is already in progress.")
            return
        }
        pendingPromise = promise

        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                if (maxCount > 1) {
                    val cap = try {
                        MediaStore.getPickImagesMaxLimit()
                    } catch (e: Exception) {
                        maxCount
                    }
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, maxCount.coerceAtMost(cap))
                }
            }
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, maxCount > 1)
            }
        }

        try {
            activity.startActivityForResult(intent, REQUEST_CODE)
        } catch (e: Exception) {
            pendingPromise = null
            promise.reject("E_NO_PICKER", "No image picker app is available.", e)
        }
    }

    override fun onActivityResult(activity: Activity?, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_CODE) return
        val promise = pendingPromise ?: return
        pendingPromise = null

        if (resultCode != Activity.RESULT_OK || data == null) {
            promise.resolve(Arguments.createArray())
            return
        }

        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } ?: data.data?.let { uris.add(it) }

        if (uris.isEmpty()) {
            promise.resolve(Arguments.createArray())
            return
        }

        scope.launch {
            try {
                val importsDir = Paths.importsDir(reactContext)
                val result = Arguments.createArray()
                for (uri in uris) {
                    val outFile = File(importsDir, "${Paths.newId()}.jpg")
                    reactContext.contentResolver.openInputStream(uri)?.use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (outFile.exists() && outFile.length() > 0) {
                        result.pushString(outFile.absolutePath)
                    }
                }
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("E_IMPORT_FAILED", e.message, e)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {}

    companion object {
        private const val REQUEST_CODE = 9421
    }
}
