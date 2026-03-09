package com.boerocamera.app.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.OutputStream

object WebPImageSaver {

    private const val TAG = "WebPImageSaver"

    /**
     * Converts an ImageProxy (JPEG or YUV) to WebP and saves it to the
     * MediaStore Pictures/Boero Camera directory.
     *
     * @return the display name of the saved file, or null on failure
     */
    fun save(
        context: Context,
        image: ImageProxy,
        quality: Int = 82,
        lossless: Boolean = false,
        savePath: String = "DCIM/Camera"
    ): String? {
        return try {
            val bitmap = imageProxyToBitmap(image)
            val rotatedBitmap = rotateBitmap(bitmap, image.imageInfo.rotationDegrees)
            val webpBytes = encodeWebP(rotatedBitmap, quality, lossless)
            saveToMediaStore(context, webpBytes, lossless, savePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save WebP", e)
            null
        }
    }

    // ─── ImageProxy → Bitmap ──────────────────────────────────────────────────

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        // CameraX with ImageCapture.OnImageCapturedCallback gives us JPEG by default
        val buffer = image.planes[0].buffer
        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Failed to decode image buffer")
    }

    // ─── Rotation ─────────────────────────────────────────────────────────────

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it != bitmap) bitmap.recycle() }
    }

    // ─── WebP Encoding ────────────────────────────────────────────────────────

    private fun encodeWebP(bitmap: Bitmap, quality: Int, lossless: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        val format = if (lossless) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
        }
        bitmap.compress(format, quality, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    // ─── MediaStore Save ──────────────────────────────────────────────────────

    private fun saveToMediaStore(
        context: Context,
        bytes: ByteArray,
        lossless: Boolean,
        savePath: String = "DCIM/Camera"
    ): String? {
        val fileName = "IMG_${System.currentTimeMillis()}.webp"
        val mimeType = "image/webp"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, savePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, contentValues) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { stream: OutputStream ->
                stream.write(bytes)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            Log.i(TAG, "Saved WebP: $fileName (${bytes.size / 1024} KB, lossless=$lossless)")
            return fileName
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to MediaStore", e)
            resolver.delete(uri, null, null)
            return null
        }
    }
}
