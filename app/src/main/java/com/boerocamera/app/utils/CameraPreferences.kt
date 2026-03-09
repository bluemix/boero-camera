package com.boerocamera.app.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.camera.video.Quality
import com.boerocamera.app.viewmodel.*

/**
 * Persists camera settings to SharedPreferences so they survive
 * Activity restarts and SettingsActivity back-navigation.
 */
object CameraPreferences {

    private const val PREFS_NAME = "boerocamera_prefs"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(context: Context, state: CameraState) {
        prefs(context).edit().apply {
            putString("flash_mode", state.flashMode.name)
            putString("focus_mode", state.focusMode.name)
            putString("white_balance", state.whiteBalance.name)
            putBoolean("hdr_enabled", state.hdrEnabled)
            putBoolean("night_mode", state.nightModeEnabled)
            putInt("aspect_ratio", state.aspectRatio)
            putString("video_quality", qualityToString(state.videoQuality))
            putBoolean("use_av1", state.useAv1)
            putInt("webp_quality", state.webpQuality)
            putBoolean("lossless_webp", state.losslessWebP)
            putInt("exposure_comp", state.exposureCompensation)
            apply()
        }
    }

    fun load(context: Context): SavedSettings {
        val p = prefs(context)
        return SavedSettings(
            flashMode        = enumValueOrDefault(p.getString("flash_mode", null), FlashMode.AUTO),
            focusMode        = enumValueOrDefault(p.getString("focus_mode", null), FocusMode.CONTINUOUS),
            whiteBalance     = enumValueOrDefault(p.getString("white_balance", null), WhiteBalance.AUTO),
            hdrEnabled       = p.getBoolean("hdr_enabled", false),
            nightModeEnabled = p.getBoolean("night_mode", false),
            aspectRatio      = p.getInt("aspect_ratio", androidx.camera.core.AspectRatio.RATIO_4_3),
            videoQuality     = stringToQuality(p.getString("video_quality", null)),
            useAv1           = p.getBoolean("use_av1", true),
            webpQuality      = p.getInt("webp_quality", 90),
            losslessWebP     = p.getBoolean("lossless_webp", false),
            exposureComp     = p.getInt("exposure_comp", 0)
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
        if (name == null) default
        else try { enumValueOf<T>(name) } catch (e: IllegalArgumentException) { default }

    private fun qualityToString(q: Quality): String = when (q) {
        Quality.SD  -> "SD"
        Quality.HD  -> "HD"
        Quality.FHD -> "FHD"
        Quality.UHD -> "UHD"
        else        -> "FHD"
    }

    private fun stringToQuality(s: String?): Quality = when (s) {
        "SD"  -> Quality.SD
        "HD"  -> Quality.HD
        "UHD" -> Quality.UHD
        else  -> Quality.FHD
    }

    data class SavedSettings(
        val flashMode: FlashMode,
        val focusMode: FocusMode,
        val whiteBalance: WhiteBalance,
        val hdrEnabled: Boolean,
        val nightModeEnabled: Boolean,
        val aspectRatio: Int,
        val videoQuality: Quality,
        val useAv1: Boolean,
        val webpQuality: Int,
        val losslessWebP: Boolean,
        val exposureComp: Int
    )
}
