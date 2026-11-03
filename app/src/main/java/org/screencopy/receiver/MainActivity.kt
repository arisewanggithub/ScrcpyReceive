package org.screencopy.receiver

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import org.screencopy.receiver.lifecycle.ReceiverWork
import org.screencopy.receiver.lifecycle.ResourceSlot
import org.screencopy.receiver.media.VideoPlayer
import org.screencopy.receiver.media.SrtPlayback
import org.screencopy.receiver.media.SrtPlayer
import org.screencopy.receiver.media.SrtNative
import org.screencopy.receiver.media.startsWithAnyFixtureScheme
import org.screencopy.receiver.net.ReceiverServer
import org.screencopy.receiver.ui.ReceiverLayout
import org.screencopy.receiver.ui.ReceiverScreen
import org.screencopy.receiver.ui.ReceiverScreenEvent
import org.screencopy.receiver.ui.ReceiverScreenView
import org.screencopy.receiver.protocol.ReceiverOrientation
import org.screencopy.receiver.ui.SessionOrientation
import org.screencopy.receiver.ui.ReceiverOrientationException
import org.screencopy.receiver.ui.FailureReason
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MainActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var updates: dev.scrcpy.update.UpdateUi
    internal var updateUiFactoryForTests: ((Activity, () -> Boolean) -> dev.scrcpy.update.UpdateUi)? = null
        set(value) {
            check(BuildConfig.DEBUG)
            field = value
        }
    private val worker = ReceiverWork.executor
    private val main = Handler(Looper.getMainLooper())
    private lateinit var screenView: ReceiverScreenView
    private lateinit var root: FrameLayout
    private lateinit var videoBox: FrameLayout
    private lateinit var surface: SurfaceView
    private var screen = ReceiverScreen()
    @Volatile private var server: ReceiverServer? = null
    internal val receiverReadyForTests: Boolean get() = server != null
    @Volatile private var videoOutput: ResourceSlot<VideoPlayer>? = null
    @Volatile private var srtOutput: ResourceSlot<SrtPlayback>? = null
    @Volatile private var srtPlayerForTests: SrtPlayer? = null
    @Volatile internal var srtFixtureUriForTests: String? = null
        set(value) {
            check(BuildConfig.DEBUG && (value == null || value.startsWithAnyFixtureScheme()))
            field = value
        }
    internal fun srtDiagnosticsForTests(): String = srtPlayerForTests?.poll().orEmpty()
    @Volatile private var active = false
    @Volatile private var exiting = false
    private var surfaceReady = false
    private var aspect = 16.0 / 9
    private val sessionOrientation = SessionOrientation({ requestedOrientation }, { requestedOrientation = it })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.containsKey("receiverPreviousOrientation") == true) {
            val previous = savedInstanceState.getInt("receiverPreviousOrientation")
            if (requestedOrientation != previous) requestedOrientation = previous
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT in 30..34) {
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
        }
        else if (android.os.Build.VERSION.SDK_INT < 30) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        setContentView(R.layout.activity_receiver)
        root = findViewById(R.id.receiver_root)
        videoBox = findViewById(R.id.video_box)
        surface = findViewById(R.id.video_surface)
        screenView = ReceiverScreenView(root, ::exitReceiver)
        val castActive = {
            server?.busyForUpdate == true || screen.phase == org.screencopy.receiver.ui.ScreenPhase.CONNECTING ||
                screen.phase == org.screencopy.receiver.ui.ScreenPhase.PLAYING
        }
        updates = updateUiFactoryForTests?.invoke(this, castActive) ?: dev.scrcpy.update.UpdateUi(this,
            dev.scrcpy.update.UpdateController.get(this, "ScrcpyRecv", "ScrcpyReceive.apk"),
            castActive)
        updates.restoreState(savedInstanceState?.getBundle("app_update_ui"))
        bindHome()
        surface.holder.addCallback(this)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitWaitingScreen() }
        videoBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
        render()
        root.requestApplyInsets()
    }

    override fun onStart() {
        super.onStart()
        if (exiting || isFinishing) return
        active = true
        android.util.Log.i("ScreenCopyReceiver", "event=RECEIVER_ACTIVITY_STARTED surfaceReady=$surfaceReady")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startReceiver()
        updates.start()
    }

    override fun onStop() {
        updates.stop()
        active = false
        android.util.Log.i("ScreenCopyReceiver", "event=RECEIVER_ACTIVITY_STOPPED")
        restoreOrientation()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dispatch(ReceiverScreenEvent.ListenerStopped)
        retireVideoOutput()
        worker.execute { stopReceiver() }
        super.onStop()
    }

    override fun onDestroy() {
        surface.holder.removeCallback(this)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("app_update_ui", updates.saveState())
        sessionOrientation.previousPolicy?.let { outState.putInt("receiverPreviousOrientation", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        updates.resume()
        refreshVersion()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (updates.onActivityResult(requestCode, resultCode, data)) { refreshVersion(); return }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::root.isInitialized) return
        val parent = root
        val waiting = findViewById<View>(R.id.waiting_screen)
        val index = parent.indexOfChild(waiting)
        parent.removeView(waiting)
        parent.addView(layoutInflater.inflate(R.layout.receiver_waiting, parent, false), index)
        screenView = ReceiverScreenView(root, ::exitReceiver)
        bindHome()
        fitWaitingScreen()
        fitVideo()
        render()
        root.requestApplyInsets()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        android.util.Log.i("ScreenCopyReceiver", "event=RECEIVER_SURFACE_CREATED active=$active")
        startReceiver()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        android.util.Log.i("ScreenCopyReceiver", "event=RECEIVER_SURFACE_DESTROYED active=$active")
        dispatch(ReceiverScreenEvent.ListenerStopped)
        retireVideoOutput()
        worker.execute { stopReceiver() }
    }

    private fun startReceiver() {
        if (!active || exiting || isFinishing || !surfaceReady) return
        val output = videoOutput ?: ResourceSlot<VideoPlayer>().also { videoOutput = it }
        val trialOutput = srtOutput ?: ResourceSlot<SrtPlayback>().also { srtOutput = it }
        worker.execute {
            if (!active || exiting || output.isClosed || !surface.holder.surface.isValid || server != null) return@execute
            try {
                server = ReceiverServer(
                    ReceiverServer.DEFAULT_NAME, surface.holder.surface,
                    onStatus = { _, _ -> }, onListener = {},
                    onVideoSize = { width, height ->
                        ui(output) { aspect = width.toDouble() / height; fitVideo() }
                    },
                    videoOutput = output,
                    srtOutput = trialOutput,
                    srtFactory = { settings ->
                        SrtPlayer(applicationContext, surface.holder.surface, settings, srtFixtureUriForTests)
                            .also { srtPlayerForTests = it }
                    },
                    validateSrt = {
                        try {
                            org.screencopy.receiver.protocol.protocolRequire(BuildConfig.SRT_TRIAL,
                                "SRT trial unavailable", "UNSUPPORTED_CONFIG")
                            SrtNative.version()
                        } catch (_: LinkageError) {
                            throw org.screencopy.receiver.protocol.ProtocolException("UNSUPPORTED_CONFIG", "SRT native transport unavailable for this ABI")
                        }
                    },
                    onScreenEvent = { event -> ui(output) { dispatch(event) } },
                    onPresentationStart = { mode -> beginPresentation(mode, output) }
                ).also { it.start() }
            } catch (e: Exception) {
                android.util.Log.e("ScreenCopyReceiver", "event=RECEIVER_START_FAILED exception=${e.javaClass.simpleName}")
                stopReceiver()
                ui(output) {
                    dispatch(ReceiverScreenEvent.ListenerStopped)
                    dispatch(ReceiverScreenEvent.Problem("LISTENER"))
                }
            }
        }
    }

    private fun stopReceiver() {
        val current = server
        server = null
        try {
            current?.close()
        } catch (_: Exception) {
            main.post {
                if (active && !exiting && !isDestroyed) {
                    dispatch(ReceiverScreenEvent.ListenerStopped)
                    dispatch(ReceiverScreenEvent.Problem("CLEANUP"))
                }
            }
        }
    }

    private fun exitReceiver() {
        if (exiting) return
        android.util.Log.i("ScreenCopyReceiver", "event=RECEIVER_EXIT_REQUESTED source=EXIT_BUTTON")
        exiting = true
        active = false
        restoreOrientation()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dispatch(ReceiverScreenEvent.Exit)
        retireVideoOutput()
        finish()
    }

    internal fun beginPresentation(mode: ReceiverOrientation, expected: ResourceSlot<VideoPlayer>? = videoOutput): AutoCloseable {
        val owner = Any()
        val cancelled = AtomicBoolean(false)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<ReceiverOrientationException?>()
        val apply = Runnable {
            try {
                if (cancelled.get() || !active || exiting || isDestroyed || isFinishing ||
                    expected == null || videoOutput !== expected || expected.isClosed || !surfaceReady || !surface.holder.surface.isValid)
                    throw ReceiverOrientationException()
                sessionOrientation.begin(owner, mode)
                if (cancelled.get()) restoreOrientation(owner)
            } catch (e: RuntimeException) {
                restoreOrientation(owner)
                failure.set(if (e is ReceiverOrientationException) e else ReceiverOrientationException(e))
            } finally { finished.countDown() }
        }
        if (Looper.myLooper() == main.looper) apply.run() else if (!main.post(apply)) throw ReceiverOrientationException()
        val completed = try { finished.await(3, TimeUnit.SECONDS) } catch (e: InterruptedException) {
            cancelled.set(true)
            main.post { restoreOrientation(owner) }
            Thread.currentThread().interrupt()
            throw ReceiverOrientationException(e)
        }
        if (!completed) {
            cancelled.set(true)
            main.post { restoreOrientation(owner) }
            throw ReceiverOrientationException()
        }
        failure.get()?.let { throw it }
        return AutoCloseable { main.post { restoreOrientation(owner) } }
    }

    private fun restoreOrientation(owner: Any? = null) {
        try {
            if (owner == null) sessionOrientation.restore() else sessionOrientation.restore(owner)
        } catch (e: RuntimeException) {
            android.util.Log.e("ScreenCopyReceiver", "event=RECEIVER_ORIENTATION_ERROR operation=RESTORE exception=${e.javaClass.simpleName}")
            dispatch(ReceiverScreenEvent.Problem("CLEANUP", FailureReason.RECEIVER_ORIENTATION))
        }
    }

    private fun retireVideoOutput() {
        val output = videoOutput
        videoOutput = null
        val trialOutput = srtOutput
        srtOutput = null
        try {
            try { output?.close() } finally { trialOutput?.close() }
        } catch (_: Exception) {
            dispatch(ReceiverScreenEvent.Problem("CLEANUP"))
        }
    }

    private fun dispatch(event: ReceiverScreenEvent) {
        screen = screen.reduce(event)
        render()
    }

    private fun render() {
        screenView.render(screen)
        updates.refresh()
        refreshVersion()
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (screen.showWaiting) it.show(WindowInsets.Type.systemBars()) else it.hide(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or if (screen.showWaiting) 0 else
                (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    private fun bindHome() {
        updates.returnFocusTo(R.id.waiting_content)
        refreshVersion()
    }

    private fun refreshVersion() {
        findViewById<android.widget.TextView>(R.id.home_installed_version).text =
            getString(R.string.installed_version, updates.installedVersion)
    }

    private fun fitWaitingScreen() {
        val density = resources.displayMetrics.density
        val width = (root.width - root.paddingLeft - root.paddingRight).coerceAtLeast(0)
        val height = (root.height - root.paddingTop - root.paddingBottom).coerceAtLeast(0)
        val margins = ReceiverLayout.margins((width / density).toInt(), (height / density).toInt())
        val content = findViewById<View>(R.id.waiting_content)
        val horizontal = (margins.horizontal * density).toInt()
        val vertical = (margins.vertical * density).toInt()
        if (content.paddingLeft != horizontal || content.paddingTop != vertical)
            content.setPadding(horizontal, vertical, horizontal, vertical)
    }

    private fun fitVideo() {
        val width = videoBox.width
        val height = videoBox.height
        if (width <= 0 || height <= 0) return
        val targetWidth = minOf(width, (height * aspect).toInt())
        val targetHeight = minOf(height, (width / aspect).toInt())
        if (surface.layoutParams.width != targetWidth || surface.layoutParams.height != targetHeight)
            surface.layoutParams = FrameLayout.LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
    }

    private fun ui(output: ResourceSlot<VideoPlayer>, action: () -> Unit) {
        main.post {
            if (active && !exiting && !isDestroyed && videoOutput === output && !output.isClosed) action()
        }
    }
}
