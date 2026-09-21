package com.boerocamera.app.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.Surface
import android.view.WindowManager
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.*
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
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
import java.util.concurrent.TimeUnit

// Focus is explicitly applied before recording so the encoder does not receive
// the initial frames while the camera is still driving the lens to focus.
enum class CameraMode { PHOTO, VIDEO }
enum class FlashMode { OFF, ON, AUTO, TORCH }
enum class FocusMode { AUTO, MANUAL, CONTINUOUS, MACRO }
enum class WhiteBalance { AUTO, DAYLIGHT, CLOUDY, SHADE, TUNGSTEN, FLUORESCENT }

data class CameraState(
    val mode: CameraMode = CameraMode.VIDEO,
    val isRecording: Boolean = false,
    val isFrontCamera: Boolean = false,
    val flashMode: FlashMode = FlashMode.AUTO,
    val focusMode: FocusMode = FocusMode.CONTINUOUS,
    val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
    val zoom: Float = 1.0f,
    val minZoom: Float = 1.0f,
    val maxZoom: Float = 1.0f,
    val iso: Int? = null,
    val shutterSpeed: Long? = null,
    val exposureCompensation: Int = 0,
    val exposureCompensationRange: Range<Int> = Range(0, 0),
    val hdrEnabled: Boolean = false,
    val nightModeEnabled: Boolean = false,
    val aspectRatio: Int = AspectRatio.RATIO_4_3,
    val videoQuality: Quality = Quality.FHD,
    val av1Available: Boolean = false,
    val useAv1: Boolean = false,
    val frameRate: Int = 30,
    val webpQuality: Int = 90,
    val losslessWebP: Boolean = false,
    val recordingDuration: Long = 0L,
    val captureStatus: String? = null,
    val fullscreenBrightness: Boolean = true
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    companion object { private const val TAG = "CameraViewModel" }

    private val _state = MutableLiveData(CameraState())
    val state: LiveData<CameraState> = _state
    var imageCapture: ImageCapture? = null
    var videoCapture: VideoCapture<Recorder>? = null
    var camera: Camera? = null
    private var activeRecording: Recording? = null
    private var av1Session: Av1VideoHelper.RecordingSession? = null
    val av1Surface: Surface? get() = av1Session?.inputSurface
    var av1Preview: Preview? = null
    var av1EncoderPreview: Preview? = null
    var av1Rotation: Int = 0
    private var timerJob: kotlinx.coroutines.Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var windowManager: WindowManager? = null
    private var originalBrightness: Float = -1f
    val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var _onCameraReady: (() -> Unit)? = null

    init { checkAv1Support() }

    fun loadSettings(context: Context) {
        val s = com.boerocamera.app.utils.CameraPreferences.load(context)
        updateState { copy(flashMode=s.flashMode, focusMode=s.focusMode, whiteBalance=s.whiteBalance,
            hdrEnabled=s.hdrEnabled, nightModeEnabled=s.nightModeEnabled, aspectRatio=s.aspectRatio,
            videoQuality=s.videoQuality, useAv1=s.useAv1, webpQuality=s.webpQuality,
            losslessWebP=s.losslessWebP, exposureCompensation=s.exposureComp) }
    }
    fun saveSettings(context: Context) { _state.value?.let { com.boerocamera.app.utils.CameraPreferences.save(context, it) } }

    private fun checkAv1Support() { viewModelScope.launch(Dispatchers.IO) {
        val available = isAv1HardwareEncoderAvailable()
        updateState { copy(av1Available=available) }
        Log.i(TAG, "AV1 hardware encoder available: $available")
    } }
    private fun isAv1HardwareEncoderAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { codec ->
            codec.isEncoder && !codec.isSoftwareOnly && codec.supportedTypes.any {
                it.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase=true)
            }
        }
    }

    fun updateState(update: CameraState.() -> CameraState) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) _state.value = (_state.value ?: CameraState()).update()
        else mainHandler.post { _state.value = (_state.value ?: CameraState()).update() }
    }
    fun setMode(mode: CameraMode) = updateState { copy(mode=mode) }
    fun flipCamera() = updateState { copy(isFrontCamera=!isFrontCamera) }
    fun setFlashMode(flash: FlashMode) { updateState { copy(flashMode=flash) }; applyFlashToCapture(flash) }
    fun setFocusMode(mode: FocusMode) { updateState { copy(focusMode=mode) }; applyFocusMode(mode) }
    fun setWhiteBalance(wb: WhiteBalance) { updateState { copy(whiteBalance=wb) }; applyWhiteBalance(wb) }
    fun setZoom(zoom: Float) { val st=_state.value ?: return; val z=zoom.coerceIn(st.minZoom, st.maxZoom); camera?.cameraControl?.setZoomRatio(z); updateState { copy(zoom=z) } }
    fun setIso(iso: Int?) = updateState { copy(iso=iso) }
    fun setShutterSpeed(ns: Long?) = updateState { copy(shutterSpeed=ns) }
    fun setExposureCompensation(steps: Int) { camera?.cameraControl?.setExposureCompensationIndex(steps); updateState { copy(exposureCompensation=steps) } }
    fun setHdrEnabled(enabled: Boolean) = updateState { copy(hdrEnabled=enabled) }
    fun setNightMode(enabled: Boolean) = updateState { copy(nightModeEnabled=enabled) }
    fun setAspectRatio(ratio: Int) = updateState { copy(aspectRatio=ratio) }
    fun setVideoQuality(quality: Quality) = updateState { copy(videoQuality=quality) }
    fun setUseAv1(use: Boolean) = updateState { copy(useAv1=use) }
    fun setFrameRate(fps: Int) = updateState { copy(frameRate=fps) }
    fun setWebpQuality(q: Int) = updateState { copy(webpQuality=q) }
    fun setLosslessWebP(lossless: Boolean) = updateState { copy(losslessWebP=lossless) }
    fun setFullscreenBrightness(enabled: Boolean) = updateState { copy(fullscreenBrightness=enabled) }

    fun onCameraInitialized(cam: Camera) { camera=cam; val z=cam.cameraInfo.zoomState.value; updateState { copy(minZoom=z?.minZoomRatio ?: 1f, maxZoom=z?.maxZoomRatio ?: 1f, zoom=z?.zoomRatio ?: 1f, exposureCompensationRange=cam.cameraInfo.exposureState.exposureCompensationRange) }; applyFocusMode(_state.value?.focusMode ?: FocusMode.CONTINUOUS); _onCameraReady?.invoke() }

    private fun applyFocusMode(mode: FocusMode) {
        val cam=camera ?: return
        try {
            val af = when (mode) {
                FocusMode.MANUAL -> android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_OFF
                FocusMode.AUTO, FocusMode.MACRO -> android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_AUTO
                FocusMode.CONTINUOUS -> android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            }
            Camera2CameraControl.from(cam.cameraControl).captureRequestOptions = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE, af).build()
        } catch (e: Exception) { Log.w(TAG, "Could not set focus mode: ${e.message}") }
    }

    /** Focus the center before opening the encoder surface. */
    private fun preFocusThen(start: () -> Unit) {
        val cam=camera
        val mode=_state.value?.focusMode ?: FocusMode.CONTINUOUS
        if (cam == null || mode == FocusMode.MANUAL) { start(); return }
        val point=SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(.5f, .5f)
        val action=FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        try {
            val future=cam.cameraControl.startFocusAndMetering(action)
            future.addListener({
                try { future.get() } catch (_: Exception) { Log.w(TAG, "Pre-focus did not complete") }
                start()
            }, ContextCompat.getMainExecutor(getApplication()))
        } catch (e: Exception) { Log.w(TAG, "Could not pre-focus: ${e.message}"); start() }
    }

    private fun applyFlashToCapture(flash: FlashMode) { imageCapture?.flashMode=when(flash) { FlashMode.OFF->ImageCapture.FLASH_MODE_OFF; FlashMode.ON->ImageCapture.FLASH_MODE_ON; FlashMode.AUTO,FlashMode.TORCH->ImageCapture.FLASH_MODE_AUTO }; if(flash==FlashMode.TORCH) camera?.cameraControl?.enableTorch(true) else camera?.cameraControl?.enableTorch(false) }
    private fun applyWhiteBalance(wb: WhiteBalance) { val cam=camera ?: return; try { val m=when(wb) { WhiteBalance.AUTO->1; WhiteBalance.DAYLIGHT->5; WhiteBalance.CLOUDY->6; WhiteBalance.SHADE->8; WhiteBalance.TUNGSTEN->2; WhiteBalance.FLUORESCENT->3 }; Camera2CameraControl.from(cam.cameraControl).captureRequestOptions=CaptureRequestOptions.Builder().setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE,m).build() } catch(e:Exception) { Log.w(TAG,"Could not set white balance: ${e.message}") } }

    fun tapToFocus(meteringPoint: MeteringPoint) { camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(meteringPoint).addPoint(meteringPoint, FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB).build()) }
    fun cancelFocus() { camera?.cameraControl?.cancelFocusAndMetering() }

    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun startRecording(context: Context, onEvent: (VideoRecordEvent) -> Unit) {
        val st=_state.value ?: CameraState()
        if(wakeLock==null) { val pm=context.getSystemService<PowerManager>(); wakeLock=pm?.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,"BoeroCamera::recording")?.apply { acquire() } }
        if(st.fullscreenBrightness) setFullBrightness(context)
        // Do not connect the encoder until AF has completed. Otherwise the first
        // encoded frames are the same out-of-focus frames seen in the preview.
        preFocusThen { if(st.useAv1 && st.av1Available && Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q) startAv1Recording(context) else startCameraXRecording(context,onEvent) }
    }

    private fun setFullBrightness(context: Context) { try { if(windowManager==null) windowManager=context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager; val a=context as? androidx.appcompat.app.AppCompatActivity ?: return; val p=a.window.attributes; originalBrightness=p.screenBrightness; p.screenBrightness=WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL; a.window.attributes=p } catch(e:Exception) { Log.w(TAG,"Could not set full brightness: ${e.message}") } }
    private fun restoreBrightness(context: Context) { try { val a=context as? androidx.appcompat.app.AppCompatActivity ?: return; val p=a.window.attributes; p.screenBrightness=if(originalBrightness>=0f) originalBrightness else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE; a.window.attributes=p; originalBrightness=-1f } catch(e:Exception) { Log.w(TAG,"Could not restore brightness: ${e.message}") } }

    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun startAv1Recording(context: Context) { val st=_state.value ?: return; val (w,h)=qualityToDimensions(st.videoQuality); val prefs=context.getSharedPreferences(com.boerocamera.app.ui.SettingsActivity.PREFS_NAME,Context.MODE_PRIVATE); val bitrate=prefs.getInt(com.boerocamera.app.ui.SettingsActivity.KEY_AV1_BITRATE,5)*1_000_000; val path=prefs.getString(com.boerocamera.app.ui.SettingsActivity.KEY_STORAGE_PATH+"_video",com.boerocamera.app.ui.SettingsActivity.DEFAULT_VIDEO_PATH)!!; val session=Av1VideoHelper.startSession(context,w,h,bitrate,st.frameRate,av1Rotation,path) ?: run { updateState { copy(captureStatus="AV1 init failed") }; return }; av1Session=session; av1EncoderPreview?.setSurfaceProvider { request -> request.provideSurface(session.inputSurface,ContextCompat.getMainExecutor(context)) { Log.i(TAG,"AV1 codec surface result: ${it.resultCode}") } }; timerJob?.cancel(); timerJob=viewModelScope.launch { var s=0L; while(true) { kotlinx.coroutines.delay(1000); updateState { copy(recordingDuration=++s) } } }; updateState { copy(isRecording=true,recordingDuration=0L,captureStatus=null) } }
    private fun qualityToDimensions(q: Quality)=when(q) { Quality.SD->Pair(640,480); Quality.HD->Pair(1280,720); Quality.UHD->Pair(3840,2160); else->Pair(1920,1080) }
    private fun stopAv1Recording(context: Context) { timerJob?.cancel(); timerJob=null; av1Session?.stop(context); av1Session=null; av1Preview=null; av1EncoderPreview=null; updateState { copy(isRecording=false,recordingDuration=0L) }; restoreBrightness(context) }

    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun startCameraXRecording(context: Context,onEvent:(VideoRecordEvent)->Unit) { val vc=videoCapture ?: return; val prefs=context.getSharedPreferences(com.boerocamera.app.ui.SettingsActivity.PREFS_NAME,Context.MODE_PRIVATE); val values=ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME,"VID_${System.currentTimeMillis()}"); put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4"); if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q) put(MediaStore.Video.Media.RELATIVE_PATH,prefs.getString(com.boerocamera.app.ui.SettingsActivity.KEY_STORAGE_PATH+"_video",com.boerocamera.app.ui.SettingsActivity.DEFAULT_VIDEO_PATH)!!) }; val output=MediaStoreOutputOptions.Builder(context.contentResolver,MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build(); activeRecording=vc.output.prepareRecording(context,output).withAudioEnabled().start(ContextCompat.getMainExecutor(context)) { event -> when(event) { is VideoRecordEvent.Start->updateState { copy(isRecording=true,recordingDuration=0L) }; is VideoRecordEvent.Status->updateState { copy(recordingDuration=event.recordingStats.recordedDurationNanos/1_000_000_000L) }; is VideoRecordEvent.Finalize->{ updateState { copy(isRecording=false,recordingDuration=0L) }; restoreBrightness(context) }; else->{ } }; onEvent(event) } }
    fun stopRecording(context: Context?=null) { wakeLock?.release(); wakeLock=null; if(av1Session!=null && context!=null) stopAv1Recording(context) else { activeRecording?.stop(); activeRecording=null; if(context!=null) restoreBrightness(context) } }
    fun pauseRecording() { activeRecording?.pause() }
    fun resumeRecording() { activeRecording?.resume() }
    fun buildRecorder(context: Context): Recorder { val st=_state.value ?: CameraState(); return Recorder.Builder().setExecutor(cameraExecutor).setQualitySelector(QualitySelector.from(st.videoQuality,FallbackStrategy.higherQualityOrLowerThan(st.videoQuality))).build() }
    fun isAv1Mode(): Boolean { val st=_state.value ?: return false; return st.useAv1&&st.av1Available&&Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q }
    override fun onCleared() { super.onCleared(); cameraExecutor.shutdown(); wakeLock?.release(); wakeLock=null }
}
