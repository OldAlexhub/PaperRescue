package com.oldalexhub.paperrescue.modules

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Shares, opens, or saves-as exported PDFs/JPEGs via content:// URIs and Android's system pickers. */
class ShareModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), ActivityEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingSavePromise: Promise? = null
    private var pendingSaveSourcePath: String? = null

    init {
        reactContext.addActivityEventListener(this)
    }

    override fun getName() = "PaperRescueShare"

    private fun uriFor(path: String): Uri =
        FileProvider.getUriForFile(reactContext, "${reactContext.packageName}.fileprovider", File(path))

    @ReactMethod
    fun shareFile(path: String, mimeType: String, title: String, promise: Promise) {
        try {
            val activity = reactContext.currentActivity
            if (activity == null) {
                promise.reject("E_NO_ACTIVITY", "App is not in the foreground.")
                return
            }
            val uri = uriFor(path)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, title))
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("E_SHARE_FAILED", e.message, e)
        }
    }

    @ReactMethod
    fun shareFiles(paths: ReadableArray, mimeType: String, title: String, promise: Promise) {
        try {
            val activity = reactContext.currentActivity
            if (activity == null) {
                promise.reject("E_NO_ACTIVITY", "App is not in the foreground.")
                return
            }
            val uris = ArrayList<Uri>()
            for (i in 0 until paths.size()) uris.add(uriFor(paths.getString(i)!!))

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, title))
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("E_SHARE_FAILED", e.message, e)
        }
    }

    @ReactMethod
    fun openFile(path: String, mimeType: String, promise: Promise) {
        try {
            val activity = reactContext.currentActivity
            if (activity == null) {
                promise.reject("E_NO_ACTIVITY", "App is not in the foreground.")
                return
            }
            val uri = uriFor(path)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "Open with"))
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("E_SHARE_FAILED", e.message, e)
        }
    }

    /** "Save locally" — lets the user pick exactly where to save via Android's own Storage Access Framework picker. */
    @ReactMethod
    fun saveAs(sourcePath: String, suggestedName: String, mimeType: String, promise: Promise) {
        val activity = reactContext.currentActivity
        if (activity == null) {
            promise.reject("E_NO_ACTIVITY", "App is not in the foreground.")
            return
        }
        if (pendingSavePromise != null) {
            promise.reject("E_BUSY", "A save is already in progress.")
            return
        }
        pendingSavePromise = promise
        pendingSaveSourcePath = sourcePath
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        }
        try {
            activity.startActivityForResult(intent, REQUEST_CODE_SAVE_AS)
        } catch (e: Exception) {
            pendingSavePromise = null
            pendingSaveSourcePath = null
            promise.reject("E_NO_PICKER", "No file picker app is available.", e)
        }
    }

    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_CODE_SAVE_AS) return
        val promise = pendingSavePromise ?: return
        val sourcePath = pendingSaveSourcePath
        pendingSavePromise = null
        pendingSaveSourcePath = null

        val destUri = data?.data
        if (resultCode != Activity.RESULT_OK || destUri == null || sourcePath == null) {
            promise.resolve(Arguments.createMap().apply { putBoolean("cancelled", true) })
            return
        }

        scope.launch {
            try {
                reactContext.contentResolver.openOutputStream(destUri)?.use { output ->
                    File(sourcePath).inputStream().use { input -> input.copyTo(output) }
                }
                val result = Arguments.createMap()
                result.putBoolean("cancelled", false)
                result.putString("savedUri", destUri.toString())
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("E_SAVE_FAILED", e.message, e)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {}

    companion object {
        private const val REQUEST_CODE_SAVE_AS = 9423
    }
}
