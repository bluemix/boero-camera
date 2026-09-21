package com.boerocamera.app.viewmodel

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import com.boerocamera.app.utils.WebPImageSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Photo capture implementation kept separate from the recording controls.
 * This also keeps the pre-focus recording change from removing the existing
 * photo-mode API used by MainActivity.
 */
fun CameraViewModel.takePhoto(
    context: Context,
    onDone: (Boolean, String?) -> Unit
) {
    val capture = imageCapture ?: run {
        onDone(false, "Camera not ready")
        return
    }
    val settings = state.value ?: CameraState()

    updateState { copy(captureStatus = "Capturing…") }

    capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    val saved = WebPImageSaver.save(
                        context = context,
                        image = image,
                        quality = settings.webpQuality,
                        lossless = settings.losslessWebP
                    )
                    image.close()
                    withContext(Dispatchers.Main) {
                        updateState { copy(captureStatus = null) }
                        onDone(saved != null, saved)
                    }
                } catch (error: Exception) {
                    image.close()
                    withContext(Dispatchers.Main) {
                        updateState { copy(captureStatus = null) }
                        onDone(false, null)
                    }
                }
            }
        }

        override fun onError(exception: ImageCaptureException) {
            updateState { copy(captureStatus = null) }
            onDone(false, null)
        }
    })
}
