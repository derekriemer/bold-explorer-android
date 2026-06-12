package com.boldexplorer.gpx

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

object GpxFileWriter {
    fun writeToDownloads(
        context: Context,
        filename: String,
        contents: String,
    ): Result<Unit> =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values =
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, filename)
                        put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                val resolver = context.contentResolver
                val uri =
                    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("could not create file in Downloads")
                resolver.openOutputStream(uri)!!.use { it.write(contents.toByteArray()) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                java.io.File(dir, filename).writeText(contents)
            }
        }
}
