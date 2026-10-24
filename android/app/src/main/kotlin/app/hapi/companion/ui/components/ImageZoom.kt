package app.hapi.companion.ui.components

import kotlin.math.max
import kotlin.math.min

internal data class ImageGeometry(val imageWidth: Float, val imageHeight: Float, val width: Float, val height: Float) {
    val ready: Boolean get() = imageWidth > 0 && imageHeight > 0 && width > 0 && height > 0
    private val fit: Float get() = if (ready) min(width / imageWidth, height / imageHeight) else 0f
    val fittedWidth: Float get() = imageWidth * fit
    val fittedHeight: Float get() = imageHeight * fit
}

/** Pan is a fraction of the scaled image, so the same focal point survives viewport changes. */
internal data class ImageZoom(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    fun clamped(geometry: ImageGeometry): ImageZoom {
        if (!geometry.ready) return this
        val nextScale = scale.coerceIn(1f, MAX_SCALE)
        if (nextScale == 1f) return ImageZoom()
        val imageWidth = geometry.fittedWidth * nextScale
        val imageHeight = geometry.fittedHeight * nextScale
        val limitX = max(0f, (imageWidth - geometry.width) / 2f) / imageWidth
        val limitY = max(0f, (imageHeight - geometry.height) / 2f) / imageHeight
        return ImageZoom(nextScale, x.coerceIn(-limitX, limitX), y.coerceIn(-limitY, limitY))
    }

    fun transform(geometry: ImageGeometry, centroidX: Float, centroidY: Float, panX: Float, panY: Float, factor: Float): ImageZoom {
        if (!geometry.ready) return this
        val before = clamped(geometry)
        val nextScale = (before.scale * factor).coerceIn(1f, MAX_SCALE)
        val ratio = nextScale / before.scale
        val offsetX = before.x * geometry.fittedWidth * before.scale
        val offsetY = before.y * geometry.fittedHeight * before.scale
        return ImageZoom(
            nextScale,
            (offsetX * ratio + (centroidX - geometry.width / 2f) * (1f - ratio) + panX) / (geometry.fittedWidth * nextScale),
            (offsetY * ratio + (centroidY - geometry.height / 2f) * (1f - ratio) + panY) / (geometry.fittedHeight * nextScale),
        ).clamped(geometry)
    }

    fun doubleTap(geometry: ImageGeometry, x: Float, y: Float): ImageZoom =
        if (scale > 1.01f) ImageZoom() else transform(geometry, x, y, 0f, 0f, 3f)

    companion object { const val MAX_SCALE = 8f }
}
