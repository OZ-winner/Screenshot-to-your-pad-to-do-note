package com.oz.tabletshotbridge

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore

class GallerySaver(private val context: Context) {
    fun saveImage(
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): Boolean {
        if (mimeType !in SUPPORTED_SCREENSHOT_MIME_TYPES) {
            return false
        }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PC Screenshots")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        var completed = false
        return try {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                if (resolver.update(uri, values, null, null) != 1) {
                    return false
                }
            }
            completed = true
            true
        } catch (_: Exception) {
            false
        } finally {
            if (!completed) {
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

}
