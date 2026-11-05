package org.screencopy.receiver.ui

import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout

internal object VideoPresentation {
    fun fit(texture: TextureView, box: View, aspect: Double, rotation: Int) {
        require(aspect.isFinite() && aspect > 0 && rotation in listOf(0, 90, 180, 270))
        val width = box.width
        val height = box.height
        if (width <= 0 || height <= 0) return
        val rotated = rotation % 180 != 0
        val shownAspect = if (rotated) 1.0 / aspect else aspect
        val fittedWidth = minOf(width, (height * shownAspect).toInt())
        val fittedHeight = minOf(height, (width / shownAspect).toInt())
        val targetWidth = if (rotated) fittedHeight else fittedWidth
        val targetHeight = if (rotated) fittedWidth else fittedHeight
        texture.rotation = rotation.toFloat()
        if (texture.layoutParams.width != targetWidth || texture.layoutParams.height != targetHeight)
            texture.layoutParams = FrameLayout.LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
    }
}
