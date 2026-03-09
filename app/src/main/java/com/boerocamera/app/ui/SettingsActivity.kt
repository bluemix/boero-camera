package com.boerocamera.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Size
import android.view.MenuItem
import android.view.View
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import com.boerocamera.app.R
import com.boerocamera.app.databinding.ActivitySettingsBinding
import com.boerocamera.app.utils.Av1VideoHelper

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var prefsSnapshot: Map<String, *>

    companion object {
        const val PREFS_NAME        = "boerocamera_settings"
        const val KEY_WEBP_QUALITY  = "webp_quality"
        const val KEY_LOSSLESS_WEBP = "lossless_webp"
        const val KEY_USE_AV1       = "use_av1"
        const val KEY_VIDEO_QUALITY = "video_quality"
        const val KEY_AV1_BITRATE   = "av1_bitrate_mbps"  // stored as Int Mbps
        const val KEY_FRAME_RATE     = "frame_rate"          // stored as Int fps
        const val KEY_STORAGE_PATH   = "storage_path"        // "DCIM/Camera" or "Pictures/Boero Camera"
        const val DEFAULT_PHOTO_PATH = "DCIM/Camera"
        const val DEFAULT_VIDEO_PATH = "DCIM/Camera"
        const val KEY_ASPECT_RATIO  = "aspect_ratio"
        const val KEY_FOCUS_MODE    = "focus_mode"
        const val KEY_HDR           = "hdr"
        const val KEY_NIGHT_MODE    = "night_mode"
        const val KEY_ISO           = "iso"
        const val KEY_SHUTTER       = "shutter"
        const val KEY_EXPOSURE_COMP = "exposure_comp"
        const val KEY_PHOTO_RES     = "photo_resolution"  // e.g. "4080x3060"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefsSnapshot = prefs.all.toMap()  // snapshot for Cancel

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Camera Settings"
        }

        setupAv1Section()
        setupPhotoResolution()
        setupWebPSection()
        setupVideoQuality()
        setupAspectRatio()
        setupStoragePath()
        setupFps()
        setupFocusMode()
        setupExposure()
        setupResetButton()
        setupOkCancel()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) { finish(); true }
        else super.onOptionsItemSelected(item)
    }

    // Each control writes to prefs immediately on change.
    // MainActivity.onResume() re-reads prefs so changes take effect on return.

    private fun setupAv1Section() {
        val info = Av1VideoHelper.probeAv1Encoder()
        binding.tvAv1Status.text = Av1VideoHelper.getStatusDescription(this)
        binding.switchAv1.isChecked = prefs.getBoolean(KEY_USE_AV1, info.available)
        binding.switchAv1.isEnabled = info.available
        binding.switchAv1.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_USE_AV1, checked).apply()
            binding.layoutAv1Bitrate.visibility = if (checked) android.view.View.VISIBLE else android.view.View.GONE
        }
        // Always show bitrate if AV1 is on (including on capable devices at startup)
        binding.layoutAv1Bitrate.visibility =
            if (binding.switchAv1.isChecked) android.view.View.VISIBLE else android.view.View.GONE

        // Bitrate slider: steps map to [2,3,4,5,6,8,10,15,20,40] Mbps
        val bitrateSteps = listOf(2,3,4,5,6,8,10,15,20,40)
        val savedMbps = prefs.getInt(KEY_AV1_BITRATE, 5)
        val savedIdx = bitrateSteps.indexOfFirst { it >= savedMbps }.coerceAtLeast(0)
        binding.seekAv1Bitrate.progress = savedIdx
        binding.tvAv1BitrateValue.text = "${bitrateSteps[savedIdx]} Mbps"
        binding.layoutAv1Bitrate.visibility =
            if (binding.switchAv1.isChecked) android.view.View.VISIBLE else android.view.View.GONE

        binding.seekAv1Bitrate.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val mbps = bitrateSteps[progress.coerceIn(0, bitrateSteps.lastIndex)]
                binding.tvAv1BitrateValue.text = "$mbps Mbps"
                prefs.edit().putInt(KEY_AV1_BITRATE, mbps).apply()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
        })
        binding.tvAv1Note.text = if (info.available)
            "Hardware AV1 encoder detected. Videos will be ~30-50% smaller vs H.264."
        else
            "AV1 hardware encoding requires a Pixel 6+ or device with a hardware AV1 encoder."
    }

    private fun setupWebPSection() {
        val quality  = prefs.getInt(KEY_WEBP_QUALITY, 82)
        val lossless = prefs.getBoolean(KEY_LOSSLESS_WEBP, false)
        binding.sliderWebpQuality.value      = quality.toFloat()
        binding.switchLosslessWebp.isChecked = lossless
        binding.sliderWebpQuality.isEnabled  = !lossless
        updateWebpLabel(quality, lossless)

        binding.sliderWebpQuality.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                prefs.edit().putInt(KEY_WEBP_QUALITY, value.toInt()).apply()
                updateWebpLabel(value.toInt(), false)
            }
        }
        binding.switchLosslessWebp.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_LOSSLESS_WEBP, checked).apply()
            binding.sliderWebpQuality.isEnabled = !checked
            updateWebpLabel(binding.sliderWebpQuality.value.toInt(), checked)
        }
    }

    private fun updateWebpLabel(q: Int, lossless: Boolean) {
        binding.tvWebpQualityLabel.text = if (lossless) "Lossless (perfect quality, large files)"
        else when {
            q >= 95 -> "Maximum ($q) - near-lossless"
            q >= 85 -> "High ($q) - recommended"
            q >= 70 -> "Medium ($q) - balanced"
            else    -> "Low ($q) - small files"
        }
    }

    private fun setupPhotoResolution() {
        // Query supported sizes from CameraX on a background thread
        val saved = prefs.getString(KEY_PHOTO_RES, "")!!
        binding.spinnerPhotoResolution.isEnabled = false

        Thread {
            val resolutions = mutableListOf("Default (auto)" to "")
            try {
                val provider = ProcessCameraProvider.getInstance(this).get()
                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                val camera = provider.availableCameraInfos.firstOrNull { info ->
                    CameraSelector.DEFAULT_BACK_CAMERA.filter(listOf(info)).isNotEmpty()
                }
                if (camera != null) {
                    // Build a throwaway ImageCapture to probe supported resolutions
                    val ic = ImageCapture.Builder().build()
                    val supported = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .build()
                    // Use ImageCapture.getSupportedResolutions via camera info
                    val sizes: List<Size> = try {
                        @Suppress("DEPRECATION")
                        ic.getResolutionInfo()?.resolution?.let { listOf(it) }
                            ?: emptyList()
                    } catch (_: Exception) { emptyList() }

                    // Fall back to a comprehensive well-known list sorted by megapixels desc
                    val fallback = listOf(
                        Size(4080, 3060), Size(4080, 2296), Size(4032, 3024),
                        Size(4032, 2268), Size(3264, 2448), Size(3264, 1836),
                        Size(2560, 1920), Size(2560, 1440), Size(1920, 1440),
                        Size(1920, 1080), Size(1280, 960),  Size(1280, 720),
                        Size(640,  480)
                    )
                    val toList = if (sizes.isEmpty()) fallback else sizes
                    toList.sortedByDescending { it.width * it.height }.forEach { sz ->
                        val mp   = (sz.width * sz.height) / 1_000_000f
                        val gcd  = gcd(sz.width, sz.height)
                        val ar   = "${sz.width/gcd}:${sz.height/gcd}"
                        val mpStr = if (mp >= 1f) "%.0fMP".format(mp) else "%.1fMP".format(mp)
                        resolutions.add("$mpStr  ${sz.width}×${sz.height} ($ar)" to "${sz.width}x${sz.height}")
                    }
                }
            } catch (_: Exception) {
                // If camera probe fails, populate with the well-known list
                listOf(
                    Size(4080,3060), Size(4080,2296), Size(3264,2448), Size(3264,1836),
                    Size(2560,1920), Size(1920,1440), Size(1920,1080),
                    Size(1280,960),  Size(1280,720),  Size(640,480)
                ).sortedByDescending { it.width * it.height }.forEach { sz ->
                    val mp  = (sz.width * sz.height) / 1_000_000f
                    val gcd = gcd(sz.width, sz.height)
                    val ar  = "${sz.width/gcd}:${sz.height/gcd}"
                    val mpStr = if (mp >= 1f) "%.0fMP".format(mp) else "%.1fMP".format(mp)
                    resolutions.add("$mpStr  ${sz.width}×${sz.height} ($ar)" to "${sz.width}x${sz.height}")
                }
            }

            runOnUiThread {
                val adapter = android.widget.ArrayAdapter(
                    this, android.R.layout.simple_spinner_item, resolutions.map { it.first }
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                binding.spinnerPhotoResolution.adapter = adapter
                binding.spinnerPhotoResolution.setSelection(
                    resolutions.indexOfFirst { it.second == saved }.coerceAtLeast(0))
                binding.spinnerPhotoResolution.isEnabled = true
                binding.spinnerPhotoResolution.onItemSelectedListener =
                    object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(p: android.widget.AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                            prefs.edit().putString(KEY_PHOTO_RES, resolutions[pos].second).apply()
                        }
                        override fun onNothingSelected(p: android.widget.AdapterView<*>) {}
                    }
            }
        }.start()
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private fun setupVideoQuality() {
        val saved = prefs.getString(KEY_VIDEO_QUALITY, "FHD")
        val qualities = listOf("SD (480p)" to "SD", "HD (720p)" to "HD",
            "FHD (1080p)" to "FHD", "UHD (4K)" to "UHD")
        binding.radioGroupVideoQuality.removeAllViews()
        qualities.forEach { (label, key) ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                isChecked = key == saved
                setTextColor(getColor(R.color.text_primary))
            }
            binding.radioGroupVideoQuality.addView(rb)
            rb.setOnCheckedChangeListener { _, checked ->
                if (checked) prefs.edit().putString(KEY_VIDEO_QUALITY, key).apply()
            }
        }
    }

    private fun setupAspectRatio() {
        // Material Button ignores isSelected for background; use backgroundTintList instead
        fun highlight(selected43: Boolean) {
            val on  = android.content.res.ColorStateList.valueOf(getColor(R.color.accent_blue))
            val off = android.content.res.ColorStateList.valueOf(getColor(R.color.surface))
            binding.btnAspect43.backgroundTintList  = if (selected43) on else off
            binding.btnAspect169.backgroundTintList = if (selected43) off else on
        }

        val saved = prefs.getInt(KEY_ASPECT_RATIO, AspectRatio.RATIO_4_3)
        highlight(saved == AspectRatio.RATIO_4_3)

        binding.btnAspect43.setOnClickListener {
            prefs.edit().putInt(KEY_ASPECT_RATIO, AspectRatio.RATIO_4_3).apply()
            highlight(true)
        }
        binding.btnAspect169.setOnClickListener {
            prefs.edit().putInt(KEY_ASPECT_RATIO, AspectRatio.RATIO_16_9).apply()
            highlight(false)
        }
    }

    private fun setupFocusMode() {
        val modes = listOf("Continuous AF" to "CONTINUOUS", "Tap to Focus" to "AUTO",
            "Manual" to "MANUAL", "Macro" to "MACRO")
        val saved = prefs.getString(KEY_FOCUS_MODE, "CONTINUOUS")
        binding.spinnerFocusMode.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_item, modes.map { it.first }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerFocusMode.setSelection(modes.indexOfFirst { it.second == saved }.coerceAtLeast(0))
        binding.spinnerFocusMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long) {
                prefs.edit().putString(KEY_FOCUS_MODE, modes[pos].second).apply()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>) {}
        }
    }

    private fun setupExposure() {
        binding.switchHdr.isChecked       = prefs.getBoolean(KEY_HDR, false)
        binding.switchNightMode.isChecked = prefs.getBoolean(KEY_NIGHT_MODE, false)
        binding.switchHdr.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_HDR, checked).apply()
            if (checked) { binding.switchNightMode.isChecked = false; prefs.edit().putBoolean(KEY_NIGHT_MODE, false).apply() }
        }
        binding.switchNightMode.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_NIGHT_MODE, checked).apply()
            if (checked) { binding.switchHdr.isChecked = false; prefs.edit().putBoolean(KEY_HDR, false).apply() }
        }

        val savedEv = prefs.getInt(KEY_EXPOSURE_COMP, 0)
        binding.sliderExposureComp.value = savedEv.toFloat().coerceIn(
            binding.sliderExposureComp.valueFrom, binding.sliderExposureComp.valueTo)
        binding.tvExposureCompLabel.text = evLabel(savedEv)
        binding.sliderExposureComp.addOnChangeListener { _, value, fromUser ->
            if (fromUser) { prefs.edit().putInt(KEY_EXPOSURE_COMP, value.toInt()).apply(); binding.tvExposureCompLabel.text = evLabel(value.toInt()) }
        }

        val isoValues = listOf("Auto","100","200","400","800","1600","3200","6400")
        binding.spinnerIso.adapter = android.widget.ArrayAdapter(this,
            android.R.layout.simple_spinner_item, isoValues)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val savedIso = prefs.getInt(KEY_ISO, -1)
        binding.spinnerIso.setSelection(if (savedIso == -1) 0 else isoValues.indexOf(savedIso.toString()).coerceAtLeast(0))
        binding.spinnerIso.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long) {
                prefs.edit().putInt(KEY_ISO, if (pos == 0) -1 else isoValues[pos].toInt()).apply()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>) {}
        }

        val shutterKeys   = listOf("Auto","1/4000","1/2000","1/1000","1/500","1/250","1/125","1/60","1/30","1/15","1/8","1/4","1/2","1\"")
        val shutterValues = listOf(-1L,250_000L,500_000L,1_000_000L,2_000_000L,4_000_000L,8_000_000L,16_666_666L,33_333_333L,66_666_666L,125_000_000L,250_000_000L,500_000_000L,1_000_000_000L)
        binding.spinnerShutter.adapter = android.widget.ArrayAdapter(this,
            android.R.layout.simple_spinner_item, shutterKeys)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val savedShutter = prefs.getLong(KEY_SHUTTER, -1L)
        binding.spinnerShutter.setSelection(shutterValues.indexOf(savedShutter).coerceAtLeast(0))
        binding.spinnerShutter.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long) {
                prefs.edit().putLong(KEY_SHUTTER, shutterValues[pos]).apply()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>) {}
        }
    }

    private fun evLabel(ev: Int) = "EV: ${if (ev >= 0) "+$ev" else "$ev"}"

    private fun setupStoragePath() {
        val photoPath = prefs.getString(KEY_STORAGE_PATH + "_photo", DEFAULT_PHOTO_PATH)!!
        val videoPath = prefs.getString(KEY_STORAGE_PATH + "_video", DEFAULT_VIDEO_PATH)!!
        binding.etPhotoPath.setText(photoPath)
        binding.etVideoPath.setText(videoPath)
        binding.etPhotoPath.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) prefs.edit().putString(KEY_STORAGE_PATH + "_photo", binding.etPhotoPath.text.toString().trim()).apply()
        }
        binding.etVideoPath.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) prefs.edit().putString(KEY_STORAGE_PATH + "_video", binding.etVideoPath.text.toString().trim()).apply()
        }
    }

    private fun setupFps() {
        val saved = prefs.getInt(KEY_FRAME_RATE, 30)
        val options = listOf(24, 30, 60)
        binding.radioGroupFps.removeAllViews()
        options.forEach { fps ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = "${fps} fps"
                isChecked = fps == saved
                setTextColor(getColor(R.color.text_primary))
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            binding.radioGroupFps.addView(rb)
            rb.setOnCheckedChangeListener { _, checked ->
                if (checked) prefs.edit().putInt(KEY_FRAME_RATE, fps).apply()
            }
        }
    }

    private fun setupOkCancel() {
        binding.btnSettingsOk.setOnClickListener {
            // Flush any pending EditText changes
            binding.etPhotoPath.clearFocus()
            binding.etVideoPath.clearFocus()
            finish()
        }
        binding.btnSettingsCancel.setOnClickListener {
            // Restore all prefs to the snapshot taken on open
            val editor = prefs.edit()
            editor.clear()
            @Suppress("UNCHECKED_CAST")
            for ((k, v) in prefsSnapshot) {
                when (v) {
                    is Boolean -> editor.putBoolean(k, v)
                    is Int     -> editor.putInt(k, v)
                    is Long    -> editor.putLong(k, v)
                    is Float   -> editor.putFloat(k, v)
                    is String  -> editor.putString(k, v)
                }
            }
            editor.apply()
            finish()
        }
    }

    private fun setupResetButton() {
        binding.btnResetSettings.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("Reset All Settings")
                .setMessage("This will wipe all Boero Camera settings and restart with defaults. Continue?")
                .setPositiveButton("Reset") { _, _ ->
                    prefs.edit().clear().apply()
                    android.widget.Toast.makeText(this, "Settings reset — restart app to apply", android.widget.Toast.LENGTH_LONG).show()
                    // Re-initialise all UI to defaults
                    recreate()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
