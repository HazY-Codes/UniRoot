package com.uniroot.app

import com.uniroot.app.engine.BootRootState
import com.uniroot.app.engine.DeviceInfoPanel
import com.uniroot.app.engine.KsuManagerDetector
import com.uniroot.app.newmethod.DfKsudUpdater
import com.uniroot.app.newmethod.KsudNextProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UniRootFixesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun recognizesGalaxyZFold7AndFold6Models() {
        assertEquals("Galaxy Z Fold 7 (SM-F966U)", DeviceInfoPanel.marketingNameFor("SM-F966U"))
        assertEquals("Galaxy Z Fold 6 (SM-F956U)", DeviceInfoPanel.marketingNameFor("SM-F956U"))
        assertEquals("Galaxy Z Fold 8 (SM-F976B)", DeviceInfoPanel.marketingNameFor("SM-F976B"))
        assertEquals("SM-X999", DeviceInfoPanel.marketingNameFor("SM-X999"))
    }

    @Test
    fun parsesKmiFromStandardAndroidKernelReleaseStrings() {
        assertEquals(
            "android15-6.6",
            DeviceInfoPanel.kmiFromRelease("6.6.127-android15-8-peeee058-abogkiF966USQUCCZIH-4k"),
        )
        assertEquals(
            "android16-6.12",
            DeviceInfoPanel.kmiFromRelease("6.12.58-android16-5-abogkiS948BXXU1"),
        )
        assertEquals("android15-6.6", DeviceInfoPanel.kmiFromRelease("android15-6.6"))
        assertNull(DeviceInfoPanel.kmiFromRelease("5.4.210-custom-kernel"))
    }

    @Test
    fun assessesDirtyFragRootabilityClearly() {
        val supported = DeviceInfoPanel.assessDirtyFragRootability(true, "android15-6.6")
        assertTrue(supported.rootable)
        assertTrue(supported.summary.startsWith("Yes"))
        assertTrue(supported.detail.contains("android15-6.6"))

        val unsupportedKmi = DeviceInfoPanel.assessDirtyFragRootability(true, "android11-5.4")
        assertFalse(unsupportedKmi.rootable)
        assertTrue(unsupportedKmi.summary.startsWith("No"))

        val not64Bit = DeviceInfoPanel.assessDirtyFragRootability(false, "android15-6.6")
        assertFalse(not64Bit.rootable)

        val nonGki = DeviceInfoPanel.assessDirtyFragRootability(true, null)
        assertFalse(nonGki.rootable)
    }

    @Test
    fun bootRootStatePersistsAcrossSoftRebootsAndResetsOnFullReboot() {
        val bootIdFile = temporaryFolder.newFile("boot_id")
        bootIdFile.writeText("d9729297-384f-4d00-b748-08480315b79b\n")
        val boot1 = BootRootState.currentBootId(bootIdFile = bootIdFile)
        assertEquals("d9729297-384f-4d00-b748-08480315b79b", boot1)

        // Same kernel boot_id after zygote/surfaceflinger soft reboot -> stays rooted.
        assertTrue(BootRootState.matchesBoot(boot1, "d9729297-384f-4d00-b748-08480315b79b"))

        // Fresh UUID after full hardware reboot -> resets rooted state.
        assertFalse(BootRootState.matchesBoot(boot1, "11111111-2222-3333-4444-555555555555"))
        assertFalse(BootRootState.matchesBoot(null, boot1))
        assertFalse(BootRootState.matchesBoot("", boot1))
    }

    @Test
    fun detectsSpoofedKernelSuNextByCertificateAndFallback() {
        assertTrue(
            KsuManagerDetector.matchesCertificate(
                listOf(KsuManagerDetector.NEXT_CERT_SHA256),
                next = true,
            ),
        )
        assertFalse(
            KsuManagerDetector.matchesCertificate(
                listOf(KsuManagerDetector.NEXT_CERT_SHA256),
                next = false,
            ),
        )
        assertTrue(
            KsuManagerDetector.matchesCertificate(
                listOf(KsuManagerDetector.CLASSIC_CERT_SHA256.uppercase()),
                next = false,
            ),
        )
        assertTrue(
            KsuManagerDetector.matchesSpoofedFallback(
                next = true,
                versionName = "v3.4.2-spoofed",
                appLabel = "KernelSU-Next",
            ),
        )
        assertFalse(
            KsuManagerDetector.matchesSpoofedFallback(
                next = true,
                versionName = "v3.4.2",
                appLabel = "Unrelated App",
            ),
        )
    }

    @Test
    fun namesAndParsesDownloadedNextProfileToMatchBundledFormat() {
        assertEquals("3.4.2-kdp-samsung", DfKsudUpdater.nextProfileId("3.4.2"))
        assertEquals("3.4.2-kdp-samsung", DfKsudUpdater.nextProfileId("v3.4.2"))

        val parsed342 = KsudNextProfiles.parseProfileId("3.4.2-kdp-samsung")
        assertEquals("3.4.2", parsed342.version)
        assertEquals("kdp-samsung", parsed342.variant)
        assertEquals("3.4.2 · kdp-samsung", parsed342.label)

        val parsed340 = KsudNextProfiles.parseProfileId("3.4.0-kdp-samsung")
        assertEquals("3.4.0", parsed340.version)
        assertEquals("kdp-samsung", parsed340.variant)
        assertEquals("3.4.0 · kdp-samsung", parsed340.label)
    }
}
