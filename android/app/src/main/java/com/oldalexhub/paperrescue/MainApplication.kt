package com.oldalexhub.paperrescue

import android.app.Application
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.oldalexhub.paperrescue.core.PaperRescuePackage
import org.opencv.android.OpenCVLoader

class MainApplication : Application(), ReactApplication {

  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList =
        PackageList(this).packages.apply {
          // Native modules that power scanning, OCR, PDF export and Rescue Scan.
          add(PaperRescuePackage())
        },
    )
  }

  override fun onCreate() {
    super.onCreate()
    // Loaded once, locally bundled with the app (no external OpenCV Manager app
    // required). All vision code checks OpenCVStatus.isReady before running.
    OpenCVStatus.isReady = OpenCVLoader.initLocal()
    loadReactNative(this)
  }
}

/** Tracks whether the bundled OpenCV native library loaded successfully. */
object OpenCVStatus {
  @Volatile
  var isReady: Boolean = false
}
