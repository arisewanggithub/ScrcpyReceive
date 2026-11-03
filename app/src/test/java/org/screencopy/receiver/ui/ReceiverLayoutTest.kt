package org.screencopy.receiver.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class ReceiverLayoutTest {
    private val source = File("src/main").let { if (it.isDirectory) it else File("app/src/main") }
    private val android = "http://schemas.android.com/apk/res/android"
    private fun xml(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(source, path))

    @Test fun surfaceStaysFullSizeUnderAnIndependentOpaqueWaitingOverlay() {
        val elements = xml("res/layout/activity_receiver.xml").getElementsByTagName("*")
        val surface = (0 until elements.length).map { elements.item(it) as Element }.single { it.tagName == "SurfaceView" }
        assertEquals("match_parent", surface.getAttributeNS(android, "layout_width"))
        assertEquals("match_parent", surface.getAttributeNS(android, "layout_height"))
        assertFalse(surface.hasAttributeNS(android, "visibility"))
        assertEquals("false", surface.getAttributeNS(android, "focusable"))
        assertEquals(1, xml("res/layout/activity_receiver.xml").getElementsByTagName("include").length)
    }

    @Test fun portraitAndLandscapeUseTheSameAddressHeroAndStatusButDifferentComposition() {
        for ((path, direction) in listOf("res/layout/receiver_waiting.xml" to "vertical", "res/layout-land/receiver_waiting.xml" to "horizontal")) {
            val doc = xml(path)
            assertEquals("true", doc.documentElement.getAttributeNS(android, "fillViewport"))
            assertTrue(doc.documentElement.getAttributeNS(android, "background").isNotEmpty())
            val content = doc.getElementsByTagName("LinearLayout").item(0) as Element
            assertEquals(direction, content.getAttributeNS(android, "orientation"))
            val includes = doc.getElementsByTagName("include")
            assertEquals(setOf("@layout/receiver_address", "@layout/receiver_details"),
                (0 until includes.length).map { (includes.item(it) as Element).getAttribute("layout") }.toSet())
        }
    }

    @Test fun appLabelAndHomeBrandUseScrcpyReceive() {
        val strings = xml("res/values/strings.xml").getElementsByTagName("string")
        val texts = (0 until strings.length).map { strings.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
        assertEquals("ScrcpyReceive", texts["app_name"])
        assertEquals("SCRCPYRECEIVE", texts["brand"])
        assertEquals("RECEIVER IP ADDRESS:PORT", texts["receiver_ip"])
        val application = xml("AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("@string/app_name", application.getAttributeNS(android, "label"))
    }

    @Test fun exitIsTheOnlyHomeActionAndNormalTextOmitsRemovedControls() {
        val layout = xml("res/layout/receiver_details.xml")
        val buttons = layout.getElementsByTagName("Button")
        assertEquals(1, buttons.length)
        val actions = (0 until buttons.length).map { buttons.item(it) as Element }
            .associateBy { it.getAttributeNS(android, "id") }
        assertEquals(setOf("@+id/exit_receiver"), actions.keys)
        actions.values.forEach { action ->
            assertEquals("true", action.getAttributeNS(android, "focusable"))
            assertEquals("56dp", action.getAttributeNS(android, "layout_height"))
            assertEquals("@drawable/exit_background", action.getAttributeNS(android, "background"))
        }
        assertEquals("@id/waiting_content", actions.getValue("@+id/exit_receiver").getAttributeNS(android, "nextFocusUp"))
        val strings = xml("res/values/strings.xml").getElementsByTagName("string")
        val texts = (0 until strings.length).map { strings.item(it).textContent }
        listOf("Disconnect", "Licenses", "TCP port", "receiver name").forEach { removed ->
            assertFalse(texts.any { it.equals(removed, ignoreCase = true) })
        }
        assertFalse(texts.any { it.contains("27184") })
        assertFalse(texts.any { it.contains("session code", ignoreCase = true) || it.contains("fingerprint", ignoreCase = true) })
        val hero = xml("res/layout/receiver_address.xml").getElementsByTagName("TextView")
        val address = (0 until hero.length).map { hero.item(it) as Element }
            .single { it.getAttributeNS(android, "id") == "@+id/receiver_addresses" }
        assertEquals("uniform", address.getAttributeNS(android, "autoSizeTextType"))
        assertEquals("72sp", address.getAttributeNS(android, "autoSizeMaxTextSize"))
        assertFalse(File(source, "res/layout/receiver_code.xml").exists())
        assertTrue(xml("res/drawable/exit_background.xml").getElementsByTagName("item").length >= 3)
    }

    @Test fun manifestAllowsResizingAndTouchlessUseWithoutClaimingTvLauncherSupport() {
        val manifest = xml("AndroidManifest.xml")
        assertEquals("true", (manifest.getElementsByTagName("activity").item(0) as Element).getAttributeNS(android, "resizeableActivity"))
        val feature = manifest.getElementsByTagName("uses-feature").item(0) as Element
        assertEquals("android.hardware.touchscreen", feature.getAttributeNS(android, "name"))
        assertEquals("false", feature.getAttributeNS(android, "required"))
        assertFalse(File(source, "AndroidManifest.xml").readText().contains("LEANBACK_LAUNCHER"))
    }

    @Test fun orientationAndWindowSizeAreHandledWithoutRecreatingTheActivity() {
        val activity = xml("AndroidManifest.xml").getElementsByTagName("activity").item(0) as Element
        val handled = activity.getAttributeNS(android, "configChanges").split('|').toSet()
        assertTrue(handled.containsAll(setOf("orientation", "screenSize", "smallestScreenSize", "screenLayout")))
        assertFalse(activity.hasAttributeNS(android, "screenOrientation"))
    }
}
