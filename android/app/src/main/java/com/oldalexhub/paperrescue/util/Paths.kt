package com.oldalexhub.paperrescue.util

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Single source of truth for where PaperRescue keeps files on disk. Everything
 * lives under app-private storage (no runtime storage permission required on
 * any Android version). JS mirrors this layout when composing paths, so the
 * segment names below must stay in sync with src/data/paths.ts.
 */
object Paths {
    fun documentsRoot(context: Context): File =
        File(context.filesDir, "documents").apply { mkdirs() }

    fun documentDir(context: Context, docId: String): File =
        File(documentsRoot(context), docId).apply { mkdirs() }

    fun pagesDir(context: Context, docId: String): File =
        File(documentDir(context, docId), "pages").apply { mkdirs() }

    fun rescueBurstDir(context: Context): File =
        File(context.cacheDir, "rescue_burst").apply { mkdirs() }

    fun newRescueSessionDir(context: Context): File =
        File(rescueBurstDir(context), UUID.randomUUID().toString()).apply { mkdirs() }

    fun capturesDir(context: Context): File =
        File(context.cacheDir, "captures").apply { mkdirs() }

    fun importsDir(context: Context): File =
        File(context.cacheDir, "imports").apply { mkdirs() }

    fun exportsDir(context: Context): File =
        File(context.cacheDir, "exports").apply { mkdirs() }

    fun newId(): String = UUID.randomUUID().toString()

    fun clearDirectoryContents(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
