package com.example.screenrec.recorder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore

/** Holds a pending MediaStore video entry plus its open descriptor. */
class MediaOutput private constructor(
    val uri: Uri,
    val pfd: ParcelFileDescriptor,
) {
    companion object {
        fun create(context: Context): MediaOutput {
            val name = "ScreenRec_${System.currentTimeMillis()}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRec")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Failed to create MediaStore entry")
            val pfd = resolver.openFileDescriptor(uri, "rw")
                ?: error("Failed to open file descriptor")
            return MediaOutput(uri, pfd)
        }
    }

    /** Clears IS_PENDING so the file becomes visible in the Gallery. */
    fun finish(context: Context) {
        try {
            pfd.close()
        } catch (_: Exception) {
        }
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    /** Removes a partial entry on failure. */
    fun discard(context: Context) {
        try {
            pfd.close()
        } catch (_: Exception) {
        }
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }
}
