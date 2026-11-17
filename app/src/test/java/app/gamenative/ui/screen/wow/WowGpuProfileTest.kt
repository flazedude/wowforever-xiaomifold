package app.gamenative.ui.screen.wow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WowGpuProfileTest {
    @Test
    fun xringO3Mali_usesSystemWrapperWithNativeBcAndSafeFallbacks() {
        val gpu = WowGpuProfile.detect("Mali-G2-Ultra-NX MC16", "XRING O3", "xring")
        val config = fields(gpu.graphicsDriverConfig)

        assertEquals("System", gpu.driver)
        assertEquals("Wrapper-gamenative", gpu.graphicsDriver)
        assertEquals("1280x720", gpu.screenSize)
        assertEquals("0", config["adrenotoolsTurnip"])
        assertEquals("1.3", config["vulkanVersion"])
        assertEquals("auto", config["resourceType"])
        // Auto retains the Fold's native BC support. Software applies only to unsupported formats.
        assertEquals("auto", config["bcnEmulation"])
        assertEquals("software", config["bcnEmulationType"])
        assertEquals("VK_EXT_graphics_pipeline_library", config["blacklistedExtensions"])
        assertEquals("4096", config["maxDeviceMemory"])
        assertEquals("4096", fields(gpu.dxvkConfig("wow-arm64"))["maxDeviceMemory"])

        val env = gpu.environmentVariables("Xiaomi", "DESKTOP-TEST")
        assertFalse(env.contains("TU_DEBUG"))
        assertFalse(env.contains("FD_DEV_FEATURES"))
        assertTrue(env.contains("GAMENATIVE_HOSTNAME=DESKTOP-TEST"))
    }

    @Test
    fun adreno740_keepsPatchedDriverAnd1080pBudget() {
        val gpu = WowGpuProfile.detect("Adreno (TM) 740", "SM8550", "qcom")

        assertEquals("Turnip-WoW-scheduler-test", gpu.driver)
        assertEquals("Wrapper", gpu.graphicsDriver)
        assertEquals("1920x1080", gpu.screenSize)
        assertEquals("1", fields(gpu.graphicsDriverConfig)["adrenotoolsTurnip"])
        assertEquals("buffer", fields(gpu.graphicsDriverConfig)["resourceType"])
        assertEquals(mapOf("version" to "wow-arm64"), fields(gpu.dxvkConfig("wow-arm64")))
        assertTrue(gpu.environmentVariables("AYN", "DESKTOP-TEST").contains("TU_DEBUG=noconform"))
    }

    @Test
    fun adreno8xxModels_keepGen8TurnipAnd720pBudget() {
        listOf("Adreno (TM) 825", "Adreno (TM) 830", "Adreno (TM) 840").forEach { renderer ->
            val gpu = WowGpuProfile.detect(renderer)
            assertEquals(renderer, "Turnip-V32-RP6sched", gpu.driver)
            assertEquals(renderer, "1280x720", gpu.screenSize)
            assertTrue(gpu.environmentVariables("OnePlus", "DESKTOP-TEST").contains("TU_DEBUG=noconform,sysmem"))
        }
    }

    @Test
    fun realMaliRenderer_takesPriorityOverChipsetFallback() {
        val gpu = WowGpuProfile.detect("Mali-G2-Ultra-NX MC16", "SM8850", "sun")
        assertTrue(gpu.usesSystemDriver)
    }

    @Test
    fun failedRendererQuery_usesKnownQualcommFallbackOrSystem() {
        assertEquals("Turnip-V32-RP6sched", WowGpuProfile.detect("unknown", "SM8750").driver)
        assertEquals("System", WowGpuProfile.detect("unknown", "XRING O3").driver)
        assertEquals("System", WowGpuProfile.detect(null, "XRING O3").driver)
    }

    @Test
    fun samsungUbwcHint_onlyAppliesToAdreno() {
        assertTrue(WowGpuProfile.detect("Adreno (TM) 830").environmentVariables("Samsung", "PC").contains("FD_DEV_FEATURES="))
        assertFalse(WowGpuProfile.detect("Xclipse 940").environmentVariables("Samsung", "PC").contains("FD_DEV_FEATURES="))
    }

    @Test
    fun firstShaderWorkaround_appliesOnlyToSystemWrapper() {
        val mali = WowGpuProfile.detect("Mali-G2-Ultra-NX MC16", "XRING O3")
        assertTrue(mali.environmentVariables("Xiaomi", "PC").contains("WRAPPER_NO_PATCH_OPCONSTCOMP=1"))

        listOf("Adreno (TM) 740", "Adreno (TM) 830").forEach { renderer ->
            assertFalse(WowGpuProfile.detect(renderer).environmentVariables("Samsung", "PC").contains("WRAPPER_NO_PATCH_OPCONSTCOMP"))
        }
    }

    private fun fields(config: String): Map<String, String> = config.split(',').associate {
        it.substringBefore('=') to it.substringAfter('=')
    }
}
