package app.hapi.companion.feature.newsession

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import app.hapi.companion.R
import kotlin.math.roundToInt

/** Only the handle owns dismissal drags. The list has no draggable ancestor or
 * sheet nested-scroll connection that can capture an interrupted fling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodexImportDialog(
    dismissEnabled: Boolean,
    onDismiss: () -> Unit,
    header: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val enabled by rememberUpdatedState(dismissEnabled)
    val dismiss by rememberUpdatedState(onDismiss)
    var dragging by remember { mutableStateOf(false) }
    var offset by remember { mutableFloatStateOf(0f) }
    val animatedOffset by animateFloatAsState(offset, if (dragging) snap() else spring(), label = "import-handle-drag")
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    val flingThreshold = with(LocalDensity.current) { 800.dp.toPx() }
    val dragState = rememberDraggableState { delta -> offset = (offset + delta).coerceAtLeast(0f) }

    Dialog(
        onDismissRequest = { if (enabled) dismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            dismissOnBackPress = dismissEnabled, dismissOnClickOutside = false,
        ),
    ) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        val cancel = stringResource(R.string.codex_import_cancel)
        val lightSystemBars = MaterialTheme.colorScheme.surfaceContainerLow.luminance() > 0.5f
        // The in-window scrim covers the full edge-to-edge bounds.
        LaunchedEffect(window, lightSystemBars) {
            window.setDimAmount(0f)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = lightSystemBars
                isAppearanceLightNavigationBars = lightSystemBars
            }
        }
        Box(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .testTag("codex-import-scrim"))
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.91f)
                    .offset { IntOffset(0, animatedOffset.roundToInt()) }.testTag("codex-import-panel"),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.navigationBarsPadding()) {
                    Column(Modifier.fillMaxWidth()) {
                        Box(Modifier.align(Alignment.CenterHorizontally).size(width = 64.dp, height = 48.dp)
                            .draggable(
                                state = dragState, orientation = Orientation.Vertical, enabled = dismissEnabled,
                                onDragStarted = { offset = animatedOffset; dragging = true },
                                onDragStopped = { velocity ->
                                    dragging = false
                                    if (enabled && (offset > threshold || (offset > 0f && velocity > flingThreshold))) dismiss()
                                    else offset = 0f
                                },
                            ).clearAndSetSemantics {
                                testTag = "codex-import-drag-handle"
                                contentDescription = cancel
                                role = Role.Button
                                if (!dismissEnabled) disabled()
                                // Keep an accessibility close action without making a physical tap dismiss.
                                onClick(label = cancel) {
                                    if (enabled) { dismiss(); true } else false
                                }
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            BottomSheetDefaults.DragHandle()
                        }
                        Box(Modifier.padding(horizontal = 16.dp)) { header() }
                    }
                    Column(
                        Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp).padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
                    )
                }
            }
        }
    }
}
