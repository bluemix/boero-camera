package com.boerocamera.app.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.boerocamera.app.R
import com.boerocamera.app.databinding.ActivityMainBinding
import com.boerocamera.app.viewmodel.CameraMode
import com.boerocamera.app.viewmodel.CameraState
import com.boerocamera.app.viewmodel.CameraViewModel
import com.boerocamera.app.viewmodel.FlashMode
import com.boerocamera.app.viewmodel.FocusMode
import com.boerocamera.app.viewmodel.WhiteBalance
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: CameraViewModel by viewModels()

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null

    // Track last-bound values to detect when a rebind is needed
    private var lastBoundMode: CameraMode? = null
    private var lastBoundFrontCamera: Boolean? = null
    private var lastBoundAv1Mode: Boolean? = null

    private val requiredPermissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.all { it.value }) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permissions are required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFullscreen()
        loadPrefsIntoViewModel()
        setupObservers()
        setupClickListeners()
        setupGestureDetectors()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-read prefs so any changes made in SettingsActivity take effect
        loadPrefsIntoViewModel()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.cameraExecutor.shutdown()
    }

    private fun loadPrefsIntoViewModel() {
        val p = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        viewModel.setWebpQuality(p.getInt(SettingsActivity.KEY_WEBP_QUALITY, 90))
        viewModel.setLosslessWebP(p.getBoolean(SettingsActivity.KEY_LOSSLESS_WEBP, false))
        viewModel.setHdrEnabled(p.getBoolean(SettingsActivity.KEY_HDR, false))
        viewModel.setNightMode(p.getBoolean(SettingsActivity.KEY_NIGHT_MODE, false))
        viewModel.setExposureCompensation(p.getInt(SettingsActivity.KEY_EXPOSURE_COMP, 0))
        val iso = p.getInt(SettingsActivity.KEY_ISO, -1)
        viewModel.setIso(if (iso == -1) null else iso)
        val shutter = p.getLong(SettingsActivity.KEY_SHUTTER, -1L)
        viewModel.setShutterSpeed(if (shutter == -1L) null else shutter)
        // Use true as default so if pref was never set, AV1 enables when hardware is found.
        // av1Available is set async by checkAv1Support(); isAv1Mode() gates on both flags.
        viewModel.setUseAv1(p.getBoolean(SettingsActivity.KEY_USE_AV1, true))
        viewModel.setFrameRate(p.getInt(SettingsActivity.KEY_FRAME_RATE, 30))
        val quality = when (p.getString(SettingsActivity.KEY_VIDEO_QUALITY, "FHD")) {
            "SD"  -> androidx.camera.video.Quality.SD
            "HD"  -> androidx.camera.video.Quality.HD
            "UHD" -> androidx.camera.video.Quality.UHD
            else  -> androidx.camera.video.Quality.FHD
        }
        viewModel.setVideoQuality(quality)
        val ar = p.getInt(SettingsActivity.KEY_ASPECT_RATIO, androidx.camera.core.AspectRatio.RATIO_4_3)
        viewModel.setAspectRatio(ar)
        val focus = when (p.getString(SettingsActivity.KEY_FOCUS_MODE, "CONTINUOUS")) {
            "AUTO"   -> FocusMode.AUTO
            "MANUAL" -> FocusMode.MANUAL
            "MACRO"  -> FocusMode.MACRO
            else     -> FocusMode.CONTINUOUS
        }
        viewModel.setFocusMode(focus)
    }

    private fun showSaveIndicator(message: String, isError: Boolean = false, durationMs: Long = 2500) {
        binding.tvSaveStatus.text = message
        binding.tvSaveStatus.setTextColor(
            if (isError) getColor(android.R.color.holo_red_light)
            else getColor(R.color.white)
        )
        binding.saveSpinner.visibility = if (isError) android.view.View.GONE else android.view.View.VISIBLE
        binding.saveIndicator.alpha = 0f
        binding.saveIndicator.visibility = android.view.View.VISIBLE
        binding.saveIndicator.animate().alpha(1f).setDuration(150).withEndAction {
            binding.saveIndicator.postDelayed({
                binding.saveIndicator.animate().alpha(0f).setDuration(300).withEndAction {
                    binding.saveIndicator.visibility = android.view.View.GONE
                }.start()
            }, durationMs)
        }.start()
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setupObservers() {
        viewModel.state.observe(this) { state ->
            updateUiForState(state)

            // Rebind camera only when mode or lens direction actually changes,
            // and only after the provider is ready. This fixes the race condition
            // where rebindCamera() was called before postValue() committed the
            // new state, causing flip and video mode to silently fail.
            val modeChanged  = lastBoundMode != null && lastBoundMode != state.mode
            val lensChanged  = lastBoundFrontCamera != null && lastBoundFrontCamera != state.isFrontCamera
            // Rebind when av1Available flips: codec probe is async and completes
            // after the first bindCamera call, so we need a second bind.
            val currentAv1   = state.useAv1 && state.av1Available
            val av1Changed   = lastBoundAv1Mode != null && lastBoundAv1Mode != currentAv1
            if ((modeChanged || lensChanged || av1Changed) && cameraProvider != null) {
                bindCamera(state)
            }
            lastBoundMode       = state.mode
            lastBoundFrontCamera = state.isFrontCamera
            lastBoundAv1Mode    = currentAv1
        }
    }

    private fun updateUiForState(state: CameraState) {
        binding.btnPhotoMode.isSelected = state.mode == CameraMode.PHOTO
        binding.btnVideoMode.isSelected = state.mode == CameraMode.VIDEO

        // Make bottom controls translucent while recording so preview is visible
        binding.bottomControls.animate()
            .alpha(if (state.isRecording) 0.25f else 1.0f)
            .setDuration(300)
            .start()

        binding.btnFlash.setImageResource(when (state.flashMode) {
            FlashMode.OFF   -> R.drawable.ic_flash_off
            FlashMode.ON    -> R.drawable.ic_flash_on
            FlashMode.AUTO  -> R.drawable.ic_flash_auto
            FlashMode.TORCH -> R.drawable.ic_torch
        })

        if (state.mode == CameraMode.VIDEO) {
            binding.btnCapture.setImageResource(
                if (state.isRecording) R.drawable.ic_stop else R.drawable.ic_record
            )
            if (state.isRecording) {
                val duration = state.recordingDuration
                val mins = TimeUnit.SECONDS.toMinutes(duration)
                val secs = duration - TimeUnit.MINUTES.toSeconds(mins)
                binding.tvRecordingTime.text = String.format("%02d:%02d", mins, secs)
                binding.tvRecordingTime.visibility = View.VISIBLE
                binding.recordingIndicator.visibility = View.VISIBLE
                // Fade controls to 30% so the viewfinder is visible while recording
                binding.bottomControls.animate().alpha(0.30f).setDuration(300).start()
            } else {
                binding.tvRecordingTime.visibility = View.GONE
                binding.recordingIndicator.visibility = View.GONE
                // Restore full opacity when not recording
                binding.bottomControls.animate().alpha(1.0f).setDuration(300).start()
            }
        } else {
            binding.btnCapture.setImageResource(R.drawable.ic_capture_photo)
            binding.tvRecordingTime.visibility = View.GONE
            binding.recordingIndicator.visibility = View.GONE
        }

        binding.badgeHdr.visibility = if (state.hdrEnabled) View.VISIBLE else View.GONE
        binding.tvZoom.text = String.format("%.1fx", state.zoom)
        val expSign = if (state.exposureCompensation >= 0) "+" else ""
        binding.tvExposure.text = "${expSign}${state.exposureCompensation}"
        binding.badgeAv1.visibility =
            if (state.mode == CameraMode.VIDEO && state.useAv1 && state.av1Available)
                View.VISIBLE else View.GONE
    }

    private fun setupClickListeners() {
        // State observer handles the rebind after these update state
        binding.btnPhotoMode.setOnClickListener {
            if (viewModel.state.value?.mode != CameraMode.PHOTO)
                viewModel.setMode(CameraMode.PHOTO)
        }
        binding.btnVideoMode.setOnClickListener {
            if (viewModel.state.value?.mode != CameraMode.VIDEO)
                viewModel.setMode(CameraMode.VIDEO)
        }

        binding.btnCapture.setOnClickListener { onCapturePressed() }

        // flipCamera() posts new state; observer detects the lens change and rebinds
        binding.btnFlipCamera.setOnClickListener {
            viewModel.flipCamera()
            animateFlipButton()
        }

        binding.btnFlash.setOnClickListener {
            val next = when (viewModel.state.value?.flashMode) {
                FlashMode.AUTO  -> FlashMode.ON
                FlashMode.ON    -> FlashMode.OFF
                FlashMode.OFF   -> FlashMode.TORCH
                FlashMode.TORCH -> FlashMode.AUTO
                else            -> FlashMode.AUTO
            }
            viewModel.setFlashMode(next)
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.sliderExposure.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.setExposureCompensation(value.toInt())
        }

        binding.btnZoom1x.setOnClickListener { viewModel.setZoom(1.0f) }
        binding.btnZoom2x.setOnClickListener { viewModel.setZoom(2.0f) }
        binding.btnZoomUw.setOnClickListener { viewModel.setZoom(0.6f) }

        binding.btnWbAuto.setOnClickListener     { viewModel.setWhiteBalance(WhiteBalance.AUTO) }
        binding.btnWbDaylight.setOnClickListener { viewModel.setWhiteBalance(WhiteBalance.DAYLIGHT) }
        binding.btnWbCloudy.setOnClickListener   { viewModel.setWhiteBalance(WhiteBalance.CLOUDY) }
        binding.btnWbTungsten.setOnClickListener { viewModel.setWhiteBalance(WhiteBalance.TUNGSTEN) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestureDetectors() {
        val scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val currentZoom = viewModel.state.value?.zoom ?: 1f
                    viewModel.setZoom(currentZoom * detector.scaleFactor)
                    return true
                }
            })

        val gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val point = binding.viewFinder.meteringPointFactory.createPoint(e.x, e.y)
                    viewModel.tapToFocus(point)
                    showFocusRing(e.x, e.y)
                    return true
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val zoom = viewModel.state.value?.zoom ?: 1f
                    viewModel.setZoom(if (zoom < 1.5f) 2.0f else 1.0f)
                    return true
                }
            })

        binding.viewFinder.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun showFocusRing(x: Float, y: Float) {
        binding.focusRing.apply {
            translationX = x - width / 2f
            translationY = y - height / 2f
            visibility = View.VISIBLE
            alpha = 1f
            animate().alpha(0f).setDuration(800).withEndAction {
                visibility = View.GONE
            }.start()
        }
    }

    private fun onCapturePressed() {
        val state = viewModel.state.value ?: return
        when (state.mode) {
            CameraMode.PHOTO -> {
                animateCaptureShutter()
                showSaveIndicator("Saving…", durationMs = 6000)
                viewModel.takePhoto(this) { success, name ->
                    if (success) showSaveIndicator("WebP saved")
                    else showSaveIndicator("Capture failed", isError = true)
                }
            }
            CameraMode.VIDEO -> {
                if (state.isRecording) {
                    showSaveIndicator("Saving…", durationMs = 8000)
                    viewModel.stopRecording(this)
                    // Rebind so viewfinder SurfaceProvider is restored after AV1 recording
                    binding.root.post {
                        cameraProvider?.let { bindCamera(viewModel.state.value ?: return@post) }
                    }
                } else {
                    viewModel.startRecording(this) { event ->
                        if (event is VideoRecordEvent.Finalize) {
                            if (event.hasError())
                                showSaveIndicator("Recording error: ${event.error}", isError = true)
                            else
                                showSaveIndicator("AV1 video saved")
                        }
                    }
                }
            }
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            val extFuture = ExtensionsManager.getInstanceAsync(this, cameraProvider!!)
            extFuture.addListener({
                extensionsManager = extFuture.get()
                viewModel.state.value?.let { bindCamera(it) }
            }, ContextCompat.getMainExecutor(this))
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(state: CameraState) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val lensFacing = if (state.isFrontCamera)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        var cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        val extManager = extensionsManager
        if (extManager != null) {
            val extMode = when {
                state.hdrEnabled      -> ExtensionMode.HDR
                state.nightModeEnabled -> ExtensionMode.NIGHT
                else                  -> ExtensionMode.NONE
            }
            if (extMode != ExtensionMode.NONE && extManager.isExtensionAvailable(cameraSelector, extMode)) {
                cameraSelector = extManager.getExtensionEnabledCameraSelector(cameraSelector, extMode)
            }
        }

        // Apply aspect ratio and FPS to preview
        val targetFps = state.frameRate
        val fpsRange = android.util.Range(targetFps, targetFps)
        val aspectRatioStrategy = AspectRatioStrategy(
            state.aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO)
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspectRatioStrategy)
            .build()
        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetFrameRate(fpsRange)
            .build()

        val useCases = mutableListOf<UseCase>(preview)

        when (state.mode) {
            CameraMode.PHOTO -> {
                val photoPrefs = getSharedPreferences(
                    com.boerocamera.app.ui.SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                val savedRes = photoPrefs.getString(
                    com.boerocamera.app.ui.SettingsActivity.KEY_PHOTO_RES, "") ?: ""
                val icBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                if (savedRes.isNotEmpty()) {
                    val parts = savedRes.split("x")
                    val w = parts.getOrNull(0)?.toIntOrNull()
                    val h = parts.getOrNull(1)?.toIntOrNull()
                    if (w != null && h != null) {
                        val strategy = androidx.camera.core.resolutionselector.ResolutionStrategy(
                            android.util.Size(w, h),
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        icBuilder.setResolutionSelector(
                            ResolutionSelector.Builder().setResolutionStrategy(strategy).build())
                    } else {
                        icBuilder.setResolutionSelector(resolutionSelector)
                    }
                } else {
                    icBuilder.setResolutionSelector(resolutionSelector)
                }
                val imageCapture = icBuilder
                    .setFlashMode(when (state.flashMode) {
                        FlashMode.OFF            -> ImageCapture.FLASH_MODE_OFF
                        FlashMode.ON             -> ImageCapture.FLASH_MODE_ON
                        FlashMode.AUTO, FlashMode.TORCH -> ImageCapture.FLASH_MODE_AUTO
                    })
                    .build()
                viewModel.imageCapture = imageCapture
                viewModel.videoCapture = null
                useCases.add(imageCapture)
                // Preview → viewfinder only
                preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            CameraMode.VIDEO -> {
                val av1Mode = viewModel.isAv1Mode()
                Log.i("Boero Camera", "VIDEO bind: isAv1Mode=$av1Mode useAv1=${viewModel.state.value?.useAv1} av1Available=${viewModel.state.value?.av1Available}")
                if (av1Mode) {
                    // AV1 path: bind TWO Preview use cases.
                    //   preview            → viewfinder (keeps running during recording)
                    //   av1EncoderPreview  → codec input surface (set when recording starts)
                    val av1EncoderPreview = androidx.camera.core.Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setTargetFrameRate(fpsRange)
                        .build()
                    viewModel.videoCapture = null
                    viewModel.imageCapture = null
                    viewModel.av1Preview = preview
                    viewModel.av1EncoderPreview = av1EncoderPreview
                    // av1Rotation is set after bind completes using camera.cameraInfo.getSensorRotationDegrees()
                    preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                    useCases.add(av1EncoderPreview)
                    Log.i("Boero Camera", "AV1 mode: dual preview bound, rotation=${viewModel.av1Rotation}°")
                } else {
                    try {
                        val recorder = viewModel.buildRecorder(this)
                        val videoCapture = VideoCapture.withOutput(recorder)
                        viewModel.videoCapture = videoCapture
                        viewModel.imageCapture = null
                        useCases.add(videoCapture)
                        preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                    } catch (e: Exception) {
                        Log.e("Boero Camera", "VideoCapture setup failed", e)
                        Toast.makeText(this, "Video unavailable: ${e.message}", Toast.LENGTH_LONG).show()
                        viewModel.setMode(CameraMode.PHOTO)
                        return
                    }
                }
            }
        }

        try {
            val camera = provider.bindToLifecycle(this, cameraSelector, *useCases.toTypedArray())
            viewModel.onCameraInitialized(camera)
            camera.cameraInfo.zoomState.observe(this) { zs ->
                viewModel.updateState { copy(zoom = zs.zoomRatio, minZoom = zs.minZoomRatio, maxZoom = zs.maxZoomRatio) }
            }
            if (viewModel.isAv1Mode()) {
                // Compute the orientation hint for the muxer:
                // We need the clockwise rotation that, when applied to the encoded frames,
                // makes the video display correctly in the player.
                // Formula: (sensorOrientation - displayRotationDegrees + 360) % 360
                @Suppress("DEPRECATION")
                val displayRotDeg = when (windowManager.defaultDisplay.rotation) {
                    android.view.Surface.ROTATION_90  -> 90
                    android.view.Surface.ROTATION_180 -> 180
                    android.view.Surface.ROTATION_270 -> 270
                    else -> 0
                }
                val sensorDeg = camera.cameraInfo.sensorRotationDegrees
                viewModel.av1Rotation = (sensorDeg - displayRotDeg + 360) % 360
                Log.i("Boero Camera", "AV1 orientation hint: sensor=${sensorDeg}° display=${displayRotDeg}° → hint=${viewModel.av1Rotation}°")
            }
        } catch (e: Exception) {
            Log.e("Boero Camera", "Camera bind failed", e)
            Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun animateCaptureShutter() {
        binding.shutterFlash.visibility = View.VISIBLE
        binding.shutterFlash.animate().alpha(0f).setDuration(200).withEndAction {
            binding.shutterFlash.visibility = View.GONE
            binding.shutterFlash.alpha = 0.7f
        }.start()
    }

    private fun animateFlipButton() {
        ObjectAnimator.ofFloat(binding.btnFlipCamera, "rotationY", 0f, 360f).apply {
            duration = 400
            start()
        }
    }

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
}
