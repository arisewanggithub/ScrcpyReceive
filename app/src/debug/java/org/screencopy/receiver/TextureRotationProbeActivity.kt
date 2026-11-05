package org.screencopy.receiver

import android.app.Activity
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicInteger
import org.screencopy.receiver.ui.VideoPresentation

/** Isolated synthetic pixels only: no receiver listener, network media or protected-window access. */
class TextureRotationProbeActivity : Activity(), TextureView.SurfaceTextureListener {
    lateinit var texture: TextureView
        private set
    lateinit var box: View
        private set
    val updates = AtomicInteger()
    private var output: Surface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_receiver)
        findViewById<View>(R.id.waiting_screen).visibility = View.GONE
        box = findViewById(R.id.video_box)
        texture = findViewById(R.id.video_surface)
        texture.surfaceTextureListener = this
    }

    fun rotate(degrees: Int) = VideoPresentation.fit(texture, box, 0.6, degrees)

    fun drawSquares() {
        val target = checkNotNull(output)
        val canvas = target.lockCanvas(null)
        try {
            val paint = Paint()
            val width = canvas.width / 2f
            val height = canvas.height / 2f
            for ((index, color) in listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).withIndex()) {
                paint.color = color
                val x = index % 2 * width
                val y = index / 2 * height
                canvas.drawRect(x, y, x + width, y + height, paint)
            }
        } finally { target.unlockCanvasAndPost(canvas) }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        output = Surface(surface)
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) { updates.incrementAndGet() }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        output?.release(); output = null
        return true
    }
    override fun onDestroy() {
        texture.surfaceTextureListener = null
        output?.release(); output = null
        super.onDestroy()
    }
}
