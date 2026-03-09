package com.boerocamera.app.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.*
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.boerocamera.app.utils.Av1VideoHelper
import com.boerocamera.app.utils.WebPImageSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class CameraMode { PHOTO, VIDEO }
enum class FlashMode { OFF, ON, AUTO, TORCH }
enum class FocusMode { AUTO, MANUAL, CONTINUOUS, MACRO }
enum class WhiteBalance { AUTO, DAYLIGHT, CLOUDY, SHADE, TUNGSTEN, FLUORESCENT }

data class CameraState(
    val mode: CameraMode = CameraMode.PHOTO,
    val isRecording: Boolean = false,
    val isFrontCamera: Boolean = false,
    val flashMode: FlashMode = FlashMode.AUTO,
    val focusMode: FocusMode = FocusMode.CONTINUOUS,
    val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
    val zoom: Float = 1.0f,
    val minZoom: Float = 1.0f,
    val maxZoom: Float = 1.0f,
    val iso: Int? = null,                  // null = auto
    val shutterSpeed: Long? = null,        // null = auto, nanoseconds
    val exposureCompensation: Int = 0,     // in steps
    val exposureCompensationRange: Range<Int> = Range(0, 0),
    val hdrEnabled: Boolean = false,
    val nightModeEnabled: Boolean = false,
    val aspectRatio: Int = AspectRatio.RATIO_4_3,
    val videoQuality: Quality = Quality.FHD,
    val av1Available: Boolean = false,
    val useAv1: Boolean = false,
    val frameRate: Int = 30,
    val webpQuality: Int = 90,            // 0-100
    val losslessWebP: Boolean = false,
    val recordingDuration: Long = 0L,
    val captureStatus: String? = null
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CameraViewModel"
    }

    private val _state = MutableLiveData(CameraState())
    val state: LiveData<CameraState> = _state

    var imageCapture: ImageCapture? = null
    var videoCapture: VideoCapture<Recorder>? = null
    var camera: Camera? = null

    private var activeRecording: Recording? = null
    private var av1Session: Av1VideoHelper.RecordingSession? = null
    val av1Surface: Surface? get() = av1Session?.inputSurface
    var av1Preview: Preview? = null          // held so AV1 recording can attach codec surface
    var av1EncoderPreview: Preview? = null    // second Preview whose surface feeds the codec
    var av1Rotation: Int = 0                  // sensor rotation passed in from MainActivity
    private var timerJob: kotlinx.coroutines.Job? = null

    val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var _onCameraReady: (() -> Unit)? = null

    init {
        checkAv1Support()
    }

    fun loadSettings(context: Context) {
        val s = com.boerocamera.app.utils.CameraPreferences.load(context)
        updateState {
            copy(
                flashMode        = s.flashMode,
                focusMode        = s.focusMode,
                whiteBalance     = s.whiteBalance,
                hdrEnabled       = s.hdrEnabled,
                nightModeEnabled = s.nightModeEnabled,
                aspectRatio      = s.aspectRatio,
                videoQuality     = s.videoQuality,
                useAv1           = s.useAv1,
                webpQuality      = s.webpQuality,
                losslessWebP     = s.losslessWebP,
                exposureCompensation = s.exposureComp
            )
        }
    }

    fun saveSettings(context: Context) {
        val st = _state.value ?: return
        com.boerocamera.app.utils.CameraPreferences.save(context, st)
    }

    // ─── AV1 Detection ───────────────────────────────────────────────────────

    private fun checkAv1Support() {
        viewModelScope.launch(Dispatchers.IO) {
            val available = isAv1HardwareEncoderAvailable()
            // Only set av1Available — never touch useAv1 here.
            // useAv1 is a user preference owned entirely by loadPrefsIntoViewModel().
            updateState { copy(av1Available = available) }
            Log.i(TAG, "AV1 hardware encoder available: $available")
        }
    }

    private fun isAv1HardwareEncoderAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        return codecList.codecInfos.any { codec ->
            !codec.isEncoder.not() &&  // is encoder
            codec.isEncoder &&
            !codec.isSoftwareOnly &&
            codec.supportedTypes.any { type ->
                type.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true)
            }
        }
    }

    // ─── State Updates ───────────────────────────────────────────────────────

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun updateState(update: CameraState.() -> CameraState) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            _state.value = (_state.value ?: CameraState()).update()
        } else {
            // Dispatch to main thread so the update reads *current* state there,
            // preventing postValue() races where rapid background updates clobber each other.
            mainHandler.post {
                _state.value = (_state.value ?: CameraState()).update()
            }
        }
    }

    fun setMode(mode: CameraMode) = updateState { copy(mode = mode) }

    fun flipCamera() = updateState { copy(isFrontCamera = !isFrontCamera) }

    fun setFlashMode(flash: FlashMode) {
        updateState { copy(flashMode = flash) }
        applyFlashToCapture(flash)
    }

    fun setFocusMode(mode: FocusMode) = updateState { copy(focusMode = mode) }

    fun setWhiteBalance(wb: WhiteBalance) {
        updateState { copy(whiteBalance = wb) }
        applyWhiteBalance(wb)
    }

    fun setZoom(zoom: Float) {
        val st = _state.value ?: return
        val clamped = zoom.coerceIn(st.minZoom, st.maxZoom)
        camera?.cameraControl?.setZoomRatio(clamped)
        updateState { copy(zoom = clamped) }
    }

    fun setIso(iso: Int?) = updateState { copy(iso = iso) }

    fun setShutterSpeed(ns: Long?) = updateState { copy(shutterSpeed = ns) }

    fun setExposureCompensation(steps: Int) {
        camera?.cameraControl?.setExposureCompensationIndex(steps)
        updateState { copy(exposureCompensation = steps) }
    }

    fun setHdrEnabled(enabled: Boolean) = updateState { copy(hdrEnabled = enabled) }

    fun setNightMode(enabled: Boolean) = updateState { copy(nightModeEnabled = enabled) }

    fun setAspectRatio(ratio: Int) = updateState { copy(aspectRatio = ratio) }

    fun setVideoQuality(quality: Quality) = updateState { copy(videoQuality = quality) }

    fun setUseAv1(use: Boolean) = updateState { copy(useAv1 = use) }
    fun setFrameRate(fps: Int)   = updateState { copy(frameRate = fps) }

    fun setWebpQuality(q: Int) = updateState { copy(webpQuality = q) }

    fun setLosslessWebP(lossless: Boolean) = updateState { copy(losslessWebP = lossless) }

    fun onCameraInitialized(cam: Camera) {
        camera = cam
        val zoomState = cam.cameraInfo.zoomState.value
        updateState {
            copy(
                minZoom = zoomState?.minZoomRatio ?: 1f,
                maxZoom = zoomState?.maxZoomRatio ?: 1f,
                zoom = zoomState?.zoomRatio ?: 1f,
                exposureCompensationRange = cam.cameraInfo.exposureState.exposureCompensationRange
            )
        }
        _onCameraReady?.invoke()
    }

    // ─── Flash ───────────────────────────────────────────────────────────────

    private fun applyFlashToCapture(flash: FlashMode) {
        imageCapture?.flashMode = when (flash) {
            FlashMode.OFF   -> ImageCapture.FLASH_MODE_OFF
            FlashMode.ON    -> ImageCapture.FLASH_MODE_ON
            FlashMode.AUTO  -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.TORCH -> ImageCapture.FLASH_MODE_OFF // torch handled via CameraControl
        }
        if (flash == FlashMode.TORCH) {
            camera?.cameraControl?.enableTorch(true)
        } else {
            camera?.cameraControl?.enableTorch(false)
        }
    }

    // ─── White Balance ───────────────────────────────────────────────────────

    private fun applyWhiteBalance(wb: WhiteBalance) {
        val cam = camera ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        try {
            val camera2Control = Camera2CameraControl.from(cam.cameraControl)
            val awbMode = when (wb) {
                WhiteBalance.AUTO        -> android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO
                WhiteBalance.DAYLIGHT    -> android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                WhiteBalance.CLOUDY      -> android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                WhiteBalance.SHADE       -> android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_SHADE
                WhiteBalance.TUNGSTEN    -> android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
                WhiteBalance.FLUORESCENT -> android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
            }
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE, awbMode
                )
                .build()
            camera2Control.captureRequestOptions = options
        } catch (e: Exception) {
            Log.w(TAG, "Could not set white balance: ${e.message}")
        }
    }

    // ─── Manual Exposure (ISO + Shutter) ─────────────────────────────────────

    fun applyManualExposure() {
        val cam = camera ?: return
        val st = _state.value ?: return
        if (st.iso == null && st.shutterSpeed == null) return
        try {
            val camera2Control = Camera2CameraControl.from(cam.cameraControl)
            val builder = CaptureRequestOptions.Builder()
            st.iso?.let {
                builder.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, it
                )
                builder.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                    android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
                )
            }
            st.shutterSpeed?.let {
                builder.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, it
                )
            }
            camera2Control.captureRequestOptions = builder.build()
        } catch (e: Exception) {
            Log.w(TAG, "Could not apply manual exposure: ${e.message}")
        }
    }

    fun resetManualExposure() {
        val cam = camera ?: return
        try {
            val camera2Control = Camera2CameraControl.from(cam.cameraControl)
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                    android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON
                )
                .build()
            camera2Control.captureRequestOptions = options
        } catch (e: Exception) {
            Log.w(TAG, "Could not reset exposure: ${e.message}")
        }
    }

    // ─── Focus ────────────────────────────────────────────────────────────────

    fun tapToFocus(meteringPoint: MeteringPoint) {
        val action = FocusMeteringAction.Builder(meteringPoint)
            .addPoint(meteringPoint, FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB)
            .build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    fun cancelFocus() {
        camera?.cameraControl?.cancelFocusAndMetering()
    }

    // ─── Photo Capture ───────────────────────────────────────────────────────

    fun takePhoto(context: Context, onDone: (Boolean, String?) -> Unit) {
        val ic = imageCapture ?: run { onDone(false, "Camera not ready"); return }
        val st = _state.value ?: CameraState()

        updateState { copy(captureStatus = "Capturing…") }

        ic.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val saved = WebPImageSaver.save(
                            context = context,
                            image = image,
                            quality = st.webpQuality,
                            lossless = st.losslessWebP
                        )
                        image.close()
                        withContext(Dispatchers.Main) {
                            updateState { copy(captureStatus = null) }
                            onDone(saved != null, saved)
                        }
                    } catch (e: Exception) {
                        image.close()
                        Log.e(TAG, "WebP save failed", e)
                        withContext(Dispatchers.Main) {
                            updateState { copy(captureStatus = null) }
                            onDone(false, null)
                        }
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Capture error", exception)
                viewModelScope.launch(Dispatchers.Main) {
                    updateState { copy(captureStatus = null) }
                    onDone(false, null)
                }
            }
        })
    }

    // ─── Video Recording ─────────────────────────────────────────────────────

    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun startRecording(context: Context, onEvent: (VideoRecordEvent) -> Unit) {
        val st = _state.value ?: CameraState()

        if (st.useAv1 && st.av1Available && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startAv1Recording(context)
        } else {
            startCameraXRecording(context, onEvent)
        }
    }

    // ─── AV1 path (MediaCodec+MediaMuxer → WebM) ────────────────────────────

    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun startAv1Recording(context: Context) {
        val st = _state.value ?: CameraState()
        val (w, h) = qualityToDimensions(st.videoQuality)

        // Read bitrate pref (default 8 Mbps)
        val prefs = context.getSharedPreferences(
            com.boerocamera.app.ui.SettingsActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val bitrateMbps = prefs.getInt(com.boerocamera.app.ui.SettingsActivity.KEY_AV1_BITRATE, 5)
        val videoBitrate = bitrateMbps * 1_000_000

        val videoPath = prefs.getString(com.boerocamera.app.ui.SettingsActivity.KEY_STORAGE_PATH + "_video",
                com.boerocamera.app.ui.SettingsActivity.DEFAULT_VIDEO_PATH)!!
            val session = Av1VideoHelper.startSession(
            context, w, h,
            videoBitrate    = videoBitrate,
            frameRate       = st.frameRate,
            rotationDegrees = av1Rotation,
            savePath        = videoPath) ?: run {
            Log.e(TAG, "AV1 session failed to start")
            updateState { copy(captureStatus = "AV1 init failed") }
            return
        }
        av1Session = session

        // Wire the codec surface to the SECOND Preview use case (av1EncoderPreview),
        // which is bound to the camera alongside the viewfinder Preview in bindCamera().
        // This keeps the viewfinder running independently during recording.
        av1EncoderPreview?.setSurfaceProvider { request ->
            request.provideSurface(
                session.inputSurface,
                ContextCompat.getMainExecutor(context)
            ) { result ->
                Log.i(TAG, "AV1 codec surface result: ${result.resultCode}")
            }
        }

        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            var secs = 0L
            while (true) {
                kotlinx.coroutines.delay(1000)
                secs++
                updateState { copy(recordingDuration = secs) }
            }
        }
        updateState { copy(isRecording = true, recordingDuration = 0L, captureStatus = null) }
        Log.i(TAG, "AV1 recording started (${w}x${h} @ ${bitrateMbps}Mbps, rotation=${av1Rotation}°)")
    }

    private fun qualityToDimensions(quality: Quality): Pair<Int, Int> = when (quality) {
        Quality.SD  -> Pair(640, 480)
        Quality.HD  -> Pair(1280, 720)
        Quality.UHD -> Pair(3840, 2160)
        else        -> Pair(1920, 1080)
    }

    private fun stopAv1Recording(context: Context) {
        timerJob?.cancel()
        timerJob = null
        av1Session?.stop(context)
        av1Session = null
        av1Preview = null
        av1EncoderPreview = null
        updateState { copy(isRecording = false, recordingDuration = 0L) }
        Log.i(TAG, "AV1 recording stopped")
    }

    // ─── CameraX path (H264/HEVC → MP4) ──────────────────────────────────────

    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun startCameraXRecording(context: Context, onEvent: (VideoRecordEvent) -> Unit) {
        val vc = videoCapture ?: run {
            Log.e(TAG, "videoCapture is null — camera not bound in VIDEO mode?")
            return
        }
        val st = _state.value ?: CameraState()

        val prefs = context.getSharedPreferences(
            com.boerocamera.app.ui.SettingsActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val fileName = "VID_${System.currentTimeMillis()}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH,
                    prefs.getString(com.boerocamera.app.ui.SettingsActivity.KEY_STORAGE_PATH + "_video",
                        com.boerocamera.app.ui.SettingsActivity.DEFAULT_VIDEO_PATH)!!)
            }
        }
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        activeRecording = vc.output
            .prepareRecording(context, mediaStoreOutput)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start    -> updateState { copy(isRecording = true, recordingDuration = 0L) }
                    is VideoRecordEvent.Status   -> updateState { copy(recordingDuration = event.recordingStats.recordedDurationNanos / 1_000_000_000L) }
                    is VideoRecordEvent.Finalize -> updateState { copy(isRecording = false, recordingDuration = 0L) }
                    else -> {}
                }
                onEvent(event)
            }
    }

    fun stopRecording(context: Context? = null) {
        if (av1Session != null && context != null) {
            stopAv1Recording(context)
        } else {
            activeRecording?.stop()
            activeRecording = null
        }
    }

    fun pauseRecording() {
        activeRecording?.pause()
        // MediaCodec-based session doesn't support pause; stop/restart would be needed
    }

    fun resumeRecording() {
        activeRecording?.resume()
    }

    // ─── Video Quality Builder (CameraX path only) ───────────────────────────

    fun buildRecorder(context: Context): Recorder {
        val st = _state.value ?: CameraState()
        return Recorder.Builder()
            .setExecutor(cameraExecutor)
            .setQualitySelector(QualitySelector.from(
                st.videoQuality,
                FallbackStrategy.higherQualityOrLowerThan(st.videoQuality)
            ))
            .build()
    }

    /**
     * Returns true if the current config will use the AV1/MediaRecorder path.
     * MainActivity uses this to decide whether to bind VideoCapture or not.
     */
    fun isAv1Mode(): Boolean {
        val st = _state.value ?: return false
        return st.useAv1 && st.av1Available && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    override fun onCleared() {
        super.onCleared()
        cameraExecutor.shutdown()
    }
}
