package dev.scrcpy.releasecheck

import android.content.ComponentName
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test

class TargetResourcesCheck {
    @Test fun actualAppResourcesAndLauncherResolveWithoutPlayback() {
        val app = TargetApp()
        val label = app.context.applicationInfo.labelRes
        assertNotEquals(0, label)
        assertEquals("ScrcpyReceive", app.context.getText(label).toString())
        val intent = Intent().setComponent(ComponentName(TargetApp.PACKAGE_NAME, "${TargetApp.PACKAGE_NAME}.MainActivity"))
        assertNotNull(app.harness.packageManager.resolveActivity(intent, 0))
        app.type("${TargetApp.PACKAGE_NAME}.MainActivity")
    }
}
