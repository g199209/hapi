package app.hapi.companion.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.hapi.companion.R
import kotlin.math.roundToInt

@Stable
internal class ImageZoomState(initial: ImageZoom = ImageZoom()) {
    var zoom by mutableStateOf(initial)
    fun reset() { zoom = ImageZoom() }
}

@Composable
internal fun rememberImageZoomState(imageKey: String): ImageZoomState = rememberSaveable(
    imageKey,
    saver = listSaver(save = { listOf(it.zoom.scale, it.zoom.x, it.zoom.y) },
        restore = { ImageZoomState(ImageZoom(it[0], it[1], it[2])) }),
) { ImageZoomState() }

/** A bounded image viewport owns its gestures; surrounding file chrome keeps normal input. */
@Composable
internal fun ZoomableImage(
    bitmap: ImageBitmap,
    description: String,
    state: ImageZoomState,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val geometry = ImageGeometry(bitmap.width.toFloat(), bitmap.height.toFloat(), size.width.toFloat(), size.height.toFloat())
    val shown = state.zoom.clamped(geometry)
    val level = stringResource(R.string.image_zoom_level, (shown.scale * 100).roundToInt())
    val zoomIn = stringResource(R.string.image_zoom_in)
    val zoomOut = stringResource(R.string.image_zoom_out)
    val fit = stringResource(R.string.image_zoom_fit)
    Box(
        modifier = modifier.clipToBounds().onSizeChanged { size = it }.testTag("image-zoom-viewport")
            .semantics {
                stateDescription = level
                customActions = listOf(
                    CustomAccessibilityAction(zoomIn) {
                        state.zoom = state.zoom.transform(geometry, geometry.width / 2, geometry.height / 2, 0f, 0f, 2f); true
                    },
                    CustomAccessibilityAction(zoomOut) {
                        state.zoom = state.zoom.transform(geometry, geometry.width / 2, geometry.height / 2, 0f, 0f, .5f); true
                    },
                    CustomAccessibilityAction(fit) { state.reset(); true },
                )
            }
            .pointerInput(geometry, state) {
                detectTapGestures(onDoubleTap = { state.zoom = state.zoom.doubleTap(geometry, it.x, it.y) })
            }
            .pointerInput(geometry, state) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    state.zoom = state.zoom.transform(geometry, centroid.x, centroid.y, pan.x, pan.y, zoom)
                }
            },
    ) {
        Image(bitmap, description, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                // The measured layer size is available on the first draw after
                // rotation, before onSizeChanged has triggered recomposition.
                val measured = ImageGeometry(bitmap.width.toFloat(), bitmap.height.toFloat(), this.size.width, this.size.height)
                val positioned = state.zoom.clamped(measured)
                scaleX = positioned.scale
                scaleY = positioned.scale
                translationX = positioned.x * measured.fittedWidth * positioned.scale
                translationY = positioned.y * measured.fittedHeight * positioned.scale
            })
        Surface(
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .92f),
        ) {
            if (shown.scale > 1.01f) {
                TextButton(onClick = state::reset, modifier = Modifier.testTag("image-zoom-fit")) { Text(fit) }
            } else {
                Text(stringResource(R.string.image_zoom_hint), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
}
