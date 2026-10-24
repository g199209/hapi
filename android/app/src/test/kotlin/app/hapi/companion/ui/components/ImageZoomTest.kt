package app.hapi.companion.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageZoomTest {
    private val square = ImageGeometry(1000f, 1000f, 400f, 500f)

    @Test fun `fit uses the original aspect ratio and cannot pan into blank space`() {
        val geometry = ImageGeometry(2080f, 1280f, 500f, 900f)
        assertEquals(500f, geometry.fittedWidth, .001f)
        assertEquals(500f * 1280f / 2080f, geometry.fittedHeight, .001f)
        assertEquals(ImageZoom(), ImageZoom(1f, .4f, -.3f).clamped(geometry))
        assertEquals(0f, ImageZoom(2f, .1f, .4f).clamped(geometry).y, .001f)
    }

    @Test fun `pinch preserves the source point under the moving centroid`() {
        val before = ImageZoom(2f, .05f, -.04f)
        val x = 180f; val y = 210f; val panX = 17f; val panY = -11f
        val after = before.transform(square, x, y, panX, panY, 1.5f)
        fun sourceX(z: ImageZoom, point: Float) = (point - square.width / 2) / (square.fittedWidth * z.scale) - z.x
        fun sourceY(z: ImageZoom, point: Float) = (point - square.height / 2) / (square.fittedHeight * z.scale) - z.y
        assertEquals(sourceX(before, x), sourceX(after, x + panX), .00001f)
        assertEquals(sourceY(before, y), sourceY(after, y + panY), .00001f)
    }

    @Test fun `panning stops at image edges on both axes`() {
        val zoom = ImageZoom(3f).transform(square, 200f, 250f, 10000f, -10000f, 1f)
        val offsetX = zoom.x * square.fittedWidth * zoom.scale
        val offsetY = zoom.y * square.fittedHeight * zoom.scale
        assertEquals((square.fittedWidth * 3f - square.width) / 2, offsetX, .001f)
        assertEquals(-(square.fittedHeight * 3f - square.height) / 2, offsetY, .001f)
    }

    @Test fun `zoom limits use the actual scale ratio and fit clears pan`() {
        val before = ImageZoom(4f)
        val after = before.transform(square, 220f, 260f, 0f, 0f, 100f)
        assertEquals(8f, after.scale)
        assertEquals(20f / (400f * 4f), 20f / (400f * after.scale) - after.x, .00001f)
        assertEquals(ImageZoom(), after.transform(square, 200f, 250f, -1000f, 300f, .001f))
    }

    @Test fun `double tap zooms towards the tap and the next tap fits the image`() {
        val zoom = ImageZoom().doubleTap(square, 150f, 200f)
        assertEquals(3f, zoom.scale)
        assertTrue(zoom.x > 0 && zoom.y > 0)
        assertEquals(ImageZoom(), zoom.doubleTap(square, 100f, 100f))
    }

    @Test fun `normalized focal point survives a viewport rotation`() {
        val zoom = ImageZoom(3f, .12f, -.1f)
        val landscape = ImageGeometry(1000f, 1000f, 600f, 350f)
        assertEquals(zoom, zoom.clamped(square))
        assertEquals(zoom, zoom.clamped(landscape))
    }

    @Test fun `gestures before measurement leave the state alone`() {
        val geometry = ImageGeometry(2080f, 1280f, 0f, 0f)
        assertEquals(ImageZoom(), ImageZoom().transform(geometry, 0f, 0f, 30f, 30f, 2f))
    }
}
