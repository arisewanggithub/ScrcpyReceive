package dev.scrcpy.update

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class UpdateUi internal constructor(
    private val activity: Activity,
    private val controller: UpdateController,
    private val casting: () -> Boolean,
    private val installs: InstallActions
) {
    companion object { const val INSTALL_REQUEST = 7312 }
    internal interface InstallActions {
        fun allowed(): Boolean
        fun openSettings()
        fun install(intent: Intent, requestCode: Int)
    }
    constructor(activity: Activity, controller: UpdateController, casting: () -> Boolean) :
        this(activity, controller, casting, object : InstallActions {
            override fun allowed(): Boolean = Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()
            override fun openSettings() {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            }
            @Suppress("DEPRECATION")
            override fun install(intent: Intent, requestCode: Int) { activity.startActivityForResult(intent, requestCode) }
        })

    private var check: Button? = null
    private var update: Button? = null
    private var action: Button? = null
    private var cancel: Button? = null
    private var status: TextView? = null
    private var details: TextView? = null
    private var dialog: AlertDialog? = null
    private var visible = false
    private var awaitingPermission = false
    private var installing = false
    private var reopen = false
    private var localNotice: String? = null
    private var previousFocusId = View.NO_ID
    private var launchCheckStarted = false
    private var automaticPromptPending = false
    private var automaticDialog = false
    val installedVersion: String get() = controller.installedName

    fun mount(parent: LinearLayout, checkId: Int = View.NO_ID, updateId: Int = View.NO_ID,
        previousFocusId: Int = View.NO_ID) {
        this.previousFocusId = previousFocusId
        check = button(parent, "Check for updates", ::checkForUpdates).apply {
            id = if (checkId == View.NO_ID) View.generateViewId() else checkId
        }
        status = text(parent, controller.status).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.DKGRAY)
        }
        update = button(parent, "Update available — details") { show() }.apply {
            id = if (updateId == View.NO_ID) View.generateViewId() else updateId
            visibility = if (controller.available()) View.VISIBLE else View.GONE
        }
        wireHomeFocus()
    }
    private fun wireHomeFocus() {
        if (previousFocusId == View.NO_ID) return
        val checkButton = check ?: return
        val updateButton = update ?: return
        checkButton.nextFocusUpId = previousFocusId
        checkButton.nextFocusLeftId = previousFocusId
        val next = if (updateButton.visibility == View.VISIBLE) updateButton.id else View.NO_ID
        checkButton.nextFocusDownId = next
        checkButton.nextFocusRightId = next
        updateButton.nextFocusUpId = checkButton.id
        updateButton.nextFocusLeftId = checkButton.id
    }
    fun start() {
        visible = true
        installing = controller.installPending()
        if (!launchCheckStarted) controller.finishUpdateRequested = false
        controller.attach { render() }
        if (!launchCheckStarted) {
            launchCheckStarted = true
            automaticPromptPending = true
            controller.foregroundCheck()
            render()
        }
        if (awaitingPermission) {
            awaitingPermission = false
            val continueInstall = controller.finishUpdateRequested && allowed() && !casting()
            controller.finishUpdateRequested = false
            if (!casting()) show() else automaticPromptPending = true
            localNotice = if (allowed()) "Install permission enabled. Choose Install update."
                else "Install permission is still disabled. Choose Allow installs and enable this app, then return."
            if (continueInstall) act()
            render()
        }
    }
    fun saveState(): Bundle = Bundle().apply {
        putBoolean("awaiting_permission", awaitingPermission)
        putBoolean("reopen", dialog != null && !automaticDialog || controller.installPending())
        putBoolean("launch_check_started", launchCheckStarted)
        putBoolean("automatic_prompt_pending", automaticPromptPending || automaticDialog)
    }
    fun restoreState(state: Bundle?) {
        if (state == null) return
        awaitingPermission = state.getBoolean("awaiting_permission")
        reopen = state.getBoolean("reopen")
        launchCheckStarted = state.getBoolean("launch_check_started")
        automaticPromptPending = state.getBoolean("automatic_prompt_pending")
        installing = controller.installPending()
    }
    fun resume() {
        val returning = controller.installPending()
        if (returning) controller.reconcileInstall(null)
        installing = controller.installPending()
        val actionable = controller.available() || controller.installNeedsRetry()
        if ((returning || reopen) && visible && !casting() && actionable) {
            reopen = false
            show()
        }
        if (!actionable) { reopen = false; if (returning) close() }
        render()
    }
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != INSTALL_REQUEST) return false
        if (controller.installTargetVersion() > 0) {
            controller.reconcileInstall(resultCode)
            installing = false
            reopen = true
            if (visible && !casting() && (controller.available() || controller.installNeedsRetry())) { reopen = false; show() }
            else if (!controller.available() && !controller.installNeedsRetry()) { reopen = false; close() }
            render()
        }
        return true
    }
    fun stop() {
        visible = false
        val continuePermission = awaitingPermission && controller.finishUpdateRequested && !activity.isFinishing
        if (!activity.isChangingConfigurations) controller.finishUpdateRequested = false
        if (activity.isChangingConfigurations) controller.detachForRecreation() else controller.detach()
        if (continuePermission) controller.finishUpdateRequested = true
        if (automaticDialog) automaticPromptPending = true
        dialog?.dismiss()
        dialog = null
        automaticDialog = false
    }
    fun returnFocusTo(viewId: Int) { previousFocusId = viewId }
    fun focusCheck() {
        val entry = check?.takeIf { it.isShown } ?:
            if (previousFocusId != View.NO_ID) activity.findViewById<View>(previousFocusId) else null
        entry?.requestFocus()
    }
    fun checkForUpdates() {
        if (!visible || activity.isFinishing || activity.isDestroyed) return
        show()
        controller.check()
    }
    fun refresh() { render() }
    private fun allowed(): Boolean = installs.allowed()
    private fun show(automatic: Boolean = false) {
        if (!visible || activity.isFinishing || activity.isDestroyed || dialog != null) return
        automaticPromptPending = false
        automaticDialog = automatic
        localNotice = null
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        details = text(body, "")
        val actionButton = button(body, "Download and install update", ::act).also { action = it }
        val cancelButton = button(body, "Later", ::later).also { cancel = it }
        val scroll = ScrollView(activity).apply { addView(body) }
        val current = AlertDialog.Builder(activity).setTitle("App updates").setView(scroll).create()
        dialog = current
        current.setOnCancelListener {
            if (dialog === current) later()
        }
        current.setOnDismissListener { if (dialog === current) dialog = null }
        current.show()
        render()
        (if (!controller.busy && controller.available()) actionButton else cancelButton).requestFocus()
    }
    private fun later() {
        controller.finishUpdateRequested = false
        controller.cancel()
        close()
    }
    private fun close() {
        dialog?.dismiss()
        dialog = null
        automaticDialog = false
        val updateButton = update
        if (updateButton?.visibility == View.VISIBLE) updateButton.requestFocus() else focusCheck()
    }
    internal fun dialogViewForTests(): View? = dialog?.window?.decorView
    private fun render() {
        if (!visible) return
        val message = controller.displayStatus()
        status?.text = message
        update?.visibility = if (controller.available()) View.VISIBLE else View.GONE
        wireHomeFocus()
        if (automaticDialog && casting()) {
            automaticPromptPending = true
            automaticDialog = false
            dialog?.dismiss()
            dialog = null
        }
        if (dialog == null && automaticPromptPending && controller.checkSucceeded &&
            controller.available() && !controller.busy && !controller.installPending() &&
            !controller.installNeedsRetry() && !casting()) show(automatic = true)
        if (controller.finishUpdateRequested && !controller.busy && !awaitingPermission) {
            controller.finishUpdateRequested = false
            if (controller.downloadReady()) act()
        }
        if (dialog == null) return
        var versions = "Current: ${controller.installedName} (${controller.installedCode})\n"
        controller.release?.let { versions += "Latest: ${it.versionName} (${it.versionCode})\n" }
        val progress = if (controller.busy && controller.downloaded > 0)
            "\nDownloaded ${controller.downloaded / 1024} KiB" +
                (if (controller.total > 0) " of ${controller.total / 1024} KiB" else "")
            else ""
        details?.text = versions + (localNotice ?: message) + progress +
            "\nStable GitHub release · protocol 3 · APK checksum and installed signing key verified before install." +
            "\nYour projector's Android installer may require its own confirmation. No browser or notification is needed."
        action?.text = if (controller.downloadReady()) {
            if (allowed()) {
                if (controller.installNeedsRetry()) "Retry installation" else "Install update"
            } else "Allow installs"
        } else "Download and install update"
        action?.isEnabled = controller.available() && !controller.busy && !installing
        if (controller.busy && cancel?.hasFocus() == false) cancel?.requestFocus()
    }
    private fun act() {
        localNotice = null
        if (installing || controller.installPending()) return
        if (!controller.downloadReady()) {
            controller.finishUpdateRequested = true
            controller.download()
            if (!controller.busy) controller.finishUpdateRequested = false
            return
        }
        if (casting()) {
            localNotice = "Stop casting before installing. This updater will not stop an active cast."
            render()
            return
        }
        controller.verifyForInstall {
            if (!visible) return@verifyForInstall
            if (casting()) {
                localNotice = "Stop casting before installing. The verified download is retained."
                render()
                return@verifyForInstall
            }
            if (!allowed()) {
                try {
                    awaitingPermission = true
                    controller.finishUpdateRequested = true
                    installs.openSettings()
                } catch (_: ActivityNotFoundException) {
                    missingSettings()
                } catch (_: SecurityException) {
                    missingSettings()
                }
                return@verifyForInstall
            }
            val uri = Uri.parse("content://${activity.packageName}.updates/verified.apk")
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true)
            intent.clipData = ClipData.newRawUri("Verified app update", uri)
            try {
                controller.beginInstall()
                if (casting()) {
                    controller.installerUnavailable()
                    localNotice = "Stop casting before installing. The verified download is retained."
                    render()
                    return@verifyForInstall
                }
                installing = true
                installs.install(intent, INSTALL_REQUEST)
                close()
            } catch (_: ActivityNotFoundException) {
                missingInstaller()
            } catch (_: SecurityException) {
                missingInstaller()
            } catch (_: IllegalStateException) {
                installing = false
                localNotice = "Cannot save the installation attempt. Retry when app storage is available."
                render()
            }
        }
    }
    private fun missingSettings() {
        awaitingPermission = false
        controller.finishUpdateRequested = false
        localNotice = "This device has no app-scoped install settings. Open Android Settings, find this app's Install unknown apps permission, then return."
        render()
    }
    private fun missingInstaller() {
        installing = false
        controller.installerUnavailable()
        localNotice = "No Android package installer is available. Ask your device administrator to enable an installer; the verified download is retained."
        render()
    }
    private fun text(parent: LinearLayout, value: String): TextView = TextView(activity).apply {
        text = value
        setTextColor(Color.DKGRAY)
        textSize = 16f
        parent.addView(this)
    }
    private fun button(parent: LinearLayout, label: String, click: () -> Unit): Button = Button(activity).apply {
        text = label
        setTextColor(Color.BLACK)
        isFocusable = true
        // Touch taps click immediately; D-pad input still uses normal focus.
        isFocusableInTouchMode = false
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(Color.rgb(255, 221, 112), Color.BLACK, 4))
            addState(intArrayOf(android.R.attr.state_pressed), shape(Color.rgb(255, 221, 112), Color.BLACK, 4))
            addState(intArrayOf(), shape(Color.LTGRAY, Color.DKGRAY, 1))
        }
        parent.addView(this, LinearLayout.LayoutParams(-1, (52 * activity.resources.displayMetrics.density).toInt()))
        setOnClickListener { click() }
    }
    private fun shape(fill: Int, stroke: Int, width: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        setStroke(width, stroke)
        cornerRadius = 6f
    }
}
