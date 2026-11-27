// SPDX-FileCopyrightText: Copyright 2026 igawa6
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.duo

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.utils.Log
import org.yuzu.yuzu_emu.utils.NativeConfig

/**
 * Eden Duo: one-time global defaults for the Retroid Pocket Duo (Dragonwing Q-7790, Adreno 722,
 * LPDDR4X). Its GPU is far weaker than the AYN Thor's Adreno 740, so shader-compile stutter is
 * the most visible problem; the profile turns on asynchronous shaders and makes sure reactive
 * flushing stays off. Applied once per install (the user can change both afterwards), never per
 * game, and never on any other device.
 *
 * Retroid has not published the Duo's Build.MODEL, so the device is recognised two ways:
 * the maker (Retroid, or its OEM Moorechip) with "Duo" in the model, or, failing that, the
 * hardware itself: an Adreno 722 together with a built-in second screen. The Duo Lite and
 * Lite Plus (Adreno 610) match neither.
 */
object DuoDeviceProfile {
    private const val KEY_CHECKED = "eden_duo_rp_duo_profile_v1"

    fun applyOnce(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(KEY_CHECKED, false)) {
            return
        }
        try {
            if (isRetroidPocketDuo(context)) {
                NativeConfig.setBoolean(BooleanSetting.RENDERER_ASYNCHRONOUS_SHADERS.key, true)
                NativeConfig.setBoolean(BooleanSetting.RENDERER_REACTIVE_FLUSHING.key, false)
                NativeConfig.saveGlobalConfig()
                Log.info("[DuoDeviceProfile] Retroid Pocket Duo: async shaders on, reactive flushing off")
            }
        } catch (e: Exception) {
            Log.warning("[DuoDeviceProfile] could not apply the device profile: ${e.message}")
        }
        // Checked once either way: the GPU probe creates a Vulkan instance, not worth every start.
        prefs.edit { putBoolean(KEY_CHECKED, true) }
    }

    fun isRetroidPocketDuo(context: Context): Boolean {
        val maker = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        val model = Build.MODEL.lowercase()
        if ((maker.contains("retroid") || maker.contains("moorechip")) && model.contains("duo")) {
            return !model.contains("lite")
        }
        val gpu = try {
            NativeLibrary.getGpuModel()
        } catch (e: Throwable) {
            ""
        }
        val adreno722 = gpu.contains("Adreno", ignoreCase = true) && gpu.contains("722")
        val detected = adreno722 && SecondScreenDetection.hasSecondScreen(context)
        Log.info(
            "[DuoDeviceProfile] maker=\"${Build.MANUFACTURER}/${Build.BRAND}\" " +
                "model=\"${Build.MODEL}\" gpu=\"$gpu\" -> ${if (detected) "Duo" else "not Duo"}"
        )
        return detected
    }
}
