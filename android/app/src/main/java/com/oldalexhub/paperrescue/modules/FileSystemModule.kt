package com.oldalexhub.paperrescue.modules

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Generic filesystem primitives used by the JS-side document repository.
 * Everything operates on app-private storage (files dir / cache dir), so no
 * runtime storage permission is ever required.
 */
class FileSystemModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun getName() = "PaperRescueFileSystem"

    @ReactMethod
    fun getAppDirectories(promise: Promise) {
        try {
            val map: WritableMap = Arguments.createMap()
            map.putString("documentsDir", File(reactApplicationContext.filesDir, "documents").apply { mkdirs() }.absolutePath)
            map.putString("cacheDir", reactApplicationContext.cacheDir.absolutePath)
            map.putString("capturesDir", File(reactApplicationContext.cacheDir, "captures").apply { mkdirs() }.absolutePath)
            map.putString("importsDir", File(reactApplicationContext.cacheDir, "imports").apply { mkdirs() }.absolutePath)
            map.putString("exportsDir", File(reactApplicationContext.cacheDir, "exports").apply { mkdirs() }.absolutePath)
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject("E_IO", e.message, e)
        }
    }

    @ReactMethod
    fun writeTextFile(path: String, content: String, promise: Promise) {
        scope.launch {
            try {
                val file = File(path)
                file.parentFile?.mkdirs()
                file.writeText(content)
                promise.resolve(true)
            } catch (e: Exception) {
                promise.reject("E_IO", e.message, e)
            }
        }
    }

    @ReactMethod
    fun readTextFile(path: String, promise: Promise) {
        scope.launch {
            try {
                val file = File(path)
                if (!file.exists()) {
                    promise.reject("E_FILE_NOT_FOUND", "No file at $path")
                    return@launch
                }
                promise.resolve(file.readText())
            } catch (e: Exception) {
                promise.reject("E_IO", e.message, e)
            }
        }
    }

    @ReactMethod
    fun fileExists(path: String, promise: Promise) {
        promise.resolve(File(path).exists())
    }

    @ReactMethod
    fun deleteFile(path: String, promise: Promise) {
        scope.launch {
            try {
                val file = File(path)
                promise.resolve(!file.exists() || file.delete())
            } catch (e: Exception) {
                promise.reject("E_IO", e.message, e)
            }
        }
    }

    @ReactMethod
    fun deleteDirectory(path: String, promise: Promise) {
        scope.launch {
            try {
                val dir = File(path)
                promise.resolve(!dir.exists() || dir.deleteRecursively())
            } catch (e: Exception) {
                promise.reject("E_IO", e.message, e)
            }
        }
    }

    @ReactMethod
    fun makeDirectory(path: String, promise: Promise) {
        try {
            File(path).mkdirs()
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("E_IO", e.message, e)
        }
    }

    @ReactMethod
    fun moveFile(source: String, destination: String, promise: Promise) {
        scope.launch {
            try {
                val src = File(source)
                val dst = File(destination)
                dst.parentFile?.mkdirs()
                if (!src.renameTo(dst)) {
                    src.copyTo(dst, overwrite = true)
                    src.delete()
                }
                promise.resolve(true)
            } catch (e: Exception) {
                promise.reject("E_IO", e.message, e)
            }
        }
    }

    @ReactMethod
    fun copyFile(source: String, destination: String, promise: Promise) {
        scope.launch {
            try {
                val src = File(source)
                if (!src.exists()) {
                    promise.reject("E_FILE_NOT_FOUND", "No file at $source")
                    return@launch
                }
                val dst = File(destination)
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                promise.resolve(true)
            } catch (e: Exception) {
                promise.reject("E_IO", e.message, e)
            }
        }
    }

    @ReactMethod
    fun listDirectory(path: String, promise: Promise) {
        scope.launch {
            try {
                val dir = File(path)
                val names = dir.listFiles()?.map { it.name } ?: emptyList()
                val array = Arguments.createArray()
                names.forEach { array.pushString(it) }
                promise.resolve(array)
            } catch (e: Exception) {
                promise.reject("E_IO", e.message, e)
            }
        }
    }

    @ReactMethod
    fun getFileSize(path: String, promise: Promise) {
        try {
            val file = File(path)
            if (!file.exists()) {
                promise.reject("E_FILE_NOT_FOUND", "No file at $path")
                return
            }
            promise.resolve(file.length().toDouble())
        } catch (e: Exception) {
            promise.reject("E_IO", e.message, e)
        }
    }

    @ReactMethod
    fun getStorageInfo(promise: Promise) {
        try {
            val filesDir = reactApplicationContext.filesDir
            val map: WritableMap = Arguments.createMap()
            map.putDouble("freeBytes", filesDir.freeSpace.toDouble())
            map.putDouble("totalBytes", filesDir.totalSpace.toDouble())
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject("E_IO", e.message, e)
        }
    }
}
