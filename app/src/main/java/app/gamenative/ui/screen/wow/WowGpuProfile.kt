package app.gamenative.ui.screen.wow

import java.util.Locale

/** GPU routing is based on the renderer, not the phone's brand or foldable model name. */
internal data class WowGpuProfile(
    val driver: String,
    val tuDebug: String,
    val screenSize: String,
    val label: String,
    val edition: String,
) {
    val usesSystemDriver: Boolean get() = driver == SYSTEM_DRIVER

    val graphicsDriver: String get() = if (usesSystemDriver) "Wrapper-gamenative" else "Wrapper"

    val graphicsDriverConfig: String
        get() = if (usesSystemDriver) {
            // Native BC texture support remains preferred. Software decoding is only the fallback
            // selected by bcnEmulation=auto; the compute decoder corrupted textures on Mali G1.
            // Hide GPL and cap reported memory using the community-tested Mali wrapper settings.
            "version=$driver,adrenotoolsTurnip=0,vulkanVersion=1.3,presentMode=mailbox,disablePresentWait=0," +
                "syncFrame=0,resourceType=auto,bcnEmulation=auto,bcnEmulationType=software,bcnEmulationCache=0," +
                "maxDeviceMemory=4096,blacklistedExtensions=VK_EXT_graphics_pipeline_library,quality=high"
        } else {
            "version=$driver,adrenotoolsTurnip=1,resourceType=buffer,bcnEmulation=auto,quality=high"
        }

    fun dxvkConfig(version: String): String =
        "version=$version" + if (usesSystemDriver) ",maxDeviceMemory=4096" else ""

    fun environmentVariables(manufacturer: String, computerName: String): String {
        val common = "WRAPPER_MAX_IMAGE_COUNT=0 ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact,deck_emu " +
            "MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=0 " +
            "MESA_VK_WSI_PRESENT_MODE=mailbox VKD3D_SHADER_MODEL=6_0 PULSE_LATENCY_MSEC=144 " +
            "${WowMachineIdentity.HOSTNAME_ENV}=$computerName"
        val turnip = if (tuDebug.isNotEmpty()) " TU_DEBUG=$tuDebug" else ""
        // Avoid the wrapper's first-shader crash reported on Mali; retain Turnip behavior.
        // https://github.com/andrehafner/Wow#non-adreno-gpus
        val systemWrapper = if (usesSystemDriver) " WRAPPER_NO_PATCH_OPCONSTCOMP=1" else ""
        val samsung = if (!usesSystemDriver && manufacturer.equals("samsung", ignoreCase = true)) {
            " FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=1"
        } else {
            ""
        }
        return common + turnip + systemWrapper + samsung
    }

    companion object {
        const val ADRENO_740_DRIVER = "Turnip-WoW-scheduler-test"
        const val ADRENO_8XX_DRIVER = "Turnip-V32-RP6sched"
        const val SYSTEM_DRIVER = "System"

        fun detect(renderer: String?, socModel: String = "", hardware: String = ""): WowGpuProfile {
            val normalized = renderer.orEmpty().lowercase(Locale.ROOT)
            val isAdreno = "adreno" in normalized
            val hasRenderer = normalized.isNotBlank() && "unknown" !in normalized
            // SoC fallbacks are useful when querying the renderer fails, but must never override
            // a real non-Adreno renderer (for example XRING O3's Mali-G2-Ultra-NX).
            val isGen8Renderer = isAdreno && Regex("\\b8[0-9]{2}\\b").containsMatchIn(normalized)
            val isGen8Soc = listOf("SM8735", "SM8750", "SM8845", "SM8850").any { socModel.contains(it, ignoreCase = true) } ||
                hardware.equals("sun", ignoreCase = true)
            val isAdreno8xx = isGen8Renderer || ((!hasRenderer || isAdreno) && isGen8Soc)
            return when {
                isAdreno8xx -> WowGpuProfile(
                    ADRENO_8XX_DRIVER, "noconform,sysmem", "1280x720", "Adreno 8xx", "Snapdragon 8 Elite / Adreno 8xx",
                )
                isAdreno -> WowGpuProfile(
                    ADRENO_740_DRIVER, "noconform", "1920x1080", "Adreno 740", "Snapdragon 8 Series / Adreno 740",
                )
                else -> WowGpuProfile(
                    SYSTEM_DRIVER, "", "1280x720", "System Vulkan",
                    if ("mali" in normalized || "immortalis" in normalized) "Mali / Immortalis · system Vulkan" else "System Vulkan · experimental",
                )
            }
        }
    }
}
