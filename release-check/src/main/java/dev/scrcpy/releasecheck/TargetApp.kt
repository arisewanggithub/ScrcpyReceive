package dev.scrcpy.releasecheck

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

internal class TargetApp(val harness: Context = InstrumentationRegistry.getInstrumentation().targetContext) {
    val context: Context = harness.createPackageContext(PACKAGE_NAME,
        Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
    val loader: ClassLoader = context.classLoader

    init {
        check(loader !== harness.classLoader) { "App and harness must have independent classloaders" }
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            "Target app must remain non-debuggable"
        }
    }

    fun type(name: String): Class<*> = loader.loadClass(name).also {
        check(it.classLoader === loader) { "Target implementation came from a different classloader" }
    }

    companion object {
        const val PACKAGE_NAME = "org.screencopy.receiver"
    }
}

internal fun Method.invokeTarget(receiver: Any?, vararg arguments: Any?): Any? = try {
    invoke(receiver, *arguments)
} catch (error: InvocationTargetException) {
    throw (error.cause ?: error)
}
