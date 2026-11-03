package dev.scrcpy.update

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout

/** Non-exported debug host: instrumentation supplies in-memory backends, never URL/intent overrides. */
class UpdateTestActivity : Activity() {
    lateinit var content: LinearLayout
    var updatesForTests: UpdateUi? = null
    var restoredUpdateStateForTests: Bundle? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(content)
        restoredUpdateStateForTests = state?.getBundle("app_update_ui")
    }
    override fun onSaveInstanceState(state: Bundle) {
        updatesForTests?.let { state.putBundle("app_update_ui", it.saveState()) }
        super.onSaveInstanceState(state)
    }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (updatesForTests?.onActivityResult(requestCode, resultCode, data) == true) return
        super.onActivityResult(requestCode, resultCode, data)
    }
    @Suppress("DEPRECATION")
    fun dispatchInstallResultForTests(requestCode: Int, resultCode: Int, data: Intent?) {
        onActivityResult(requestCode, resultCode, data)
    }
}
