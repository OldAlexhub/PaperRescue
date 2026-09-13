package com.oldalexhub.paperrescue.core

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager
import com.oldalexhub.paperrescue.modules.DocumentProcessingModule
import com.oldalexhub.paperrescue.modules.FileSystemModule
import com.oldalexhub.paperrescue.modules.GalleryModule
import com.oldalexhub.paperrescue.modules.MlkitScannerModule
import com.oldalexhub.paperrescue.modules.OcrModule
import com.oldalexhub.paperrescue.modules.PdfModule
import com.oldalexhub.paperrescue.modules.QualityModule
import com.oldalexhub.paperrescue.modules.RescueFusionModule
import com.oldalexhub.paperrescue.modules.ScannerModule
import com.oldalexhub.paperrescue.modules.ShareModule

/** Registers every PaperRescue native module: scanning, vision, OCR, PDF export, files and sharing. */
class PaperRescuePackage : ReactPackage {

    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> = listOf(
        FileSystemModule(reactContext),
        GalleryModule(reactContext),
        ScannerModule(reactContext),
        MlkitScannerModule(reactContext),
        DocumentProcessingModule(reactContext),
        RescueFusionModule(reactContext),
        QualityModule(reactContext),
        OcrModule(reactContext),
        PdfModule(reactContext),
        ShareModule(reactContext),
    )

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}
