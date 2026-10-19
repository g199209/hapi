package app.hapi.companion.feature.sessions

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.hapi.companion.R
import kotlin.math.abs

private enum class PressResult { Tap, Cancel }

/** Hold + physical left drag archives; a stationary long-press opens the menu on release.
 * combinedClickable keeps keyboard/accessibility behavior. The stationary container owns
 * touch so translating the foreground cannot change gesture coordinates. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionArchiveSwipe(
    sessionId: String,
    canArchive: Boolean,
    pending: Boolean,
    failureRevision: Long,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onArchive: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var width by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var committed by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { (width * 0.32f).coerceIn(80.dp.toPx(), 144.dp.toPx()) }
    val available by rememberUpdatedState(canArchive)
    val interactive by rememberUpdatedState(!pending && !committed)
    val latestThreshold by rememberUpdatedState(threshold)
    val latestWidth by rememberUpdatedState(width)
    val click by rememberUpdatedState(onClick)
    val menu by rememberUpdatedState(onMenu)
    val commit by rememberUpdatedState(newValue = {
        if (canArchive && !pending && !committed) {
            committed = true
            offset = -width.toFloat()
            onArchive()
        }
    })
    LaunchedEffect(failureRevision) { committed = false; offset = 0f }
    LaunchedEffect(pending, width) {
        if (pending) { committed = true; offset = -width.toFloat() }
    }
    val rendered by animateFloatAsState(offset, animationSpec = when {
        dragging -> snap()
        committed -> tween(180, easing = FastOutLinearInEasing)
        else -> spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
    }, label = "archive-offset")
    val armed = -offset >= threshold
    val background by animateColorAsState(if (armed) Color(0xFF246A4F) else Color(0xFF4D8D74), label = "archive-background")
    val archiveLabel = stringResource(R.string.sessions_action_archive)
    val releaseLabel = stringResource(R.string.sessions_swipe_release)
    val touch = Modifier.pointerInput(sessionId) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            down.consume()
            if (!interactive) return@awaitEachGesture
            val press = PressInteraction.Press(down.position)
            interactions.tryEmit(press)
            var released = false
            var last = down
            try {
                val initial = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var result: PressResult? = null
                    while (result == null) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.singleOrNull()
                        if (change == null || change.id != down.id || change.isConsumed) result = PressResult.Cancel
                        else {
                            last = change
                            val move = change.position - down.position
                            if (move.getDistance() > viewConfiguration.touchSlop) {
                                // Leave vertical movement for LazyColumn. An early horizontal
                                // swipe cancels the click without archiving or opening a menu.
                                if (abs(move.x) > abs(move.y)) change.consume()
                                result = PressResult.Cancel
                            } else if (!change.pressed) {
                                change.consume(); result = PressResult.Tap
                            }
                        }
                    }
                    result
                }
                if (initial == PressResult.Tap) {
                    interactions.tryEmit(PressInteraction.Release(press)); released = true
                    click()
                } else if (initial == null) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val anchor = last.position
                    var horizontal = false
                    var thresholdFeedback = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.singleOrNull() ?: break
                        if (change.id != down.id || change.isConsumed) break
                        val move = change.position - anchor
                        if (!horizontal && move.getDistance() > viewConfiguration.touchSlop) {
                            if (move.x >= 0 || abs(move.y) >= abs(move.x) || !available) break
                            horizontal = true; dragging = true
                        }
                        if (horizontal) {
                            change.consume()
                            offset = move.x.coerceIn(-latestWidth.toFloat(), 0f)
                            if (-offset >= latestThreshold && !thresholdFeedback) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                thresholdFeedback = true
                            }
                        }
                        if (!change.pressed) {
                            change.consume()
                            if (horizontal && -offset >= latestThreshold) commit()
                            else if (!horizontal) {
                                interactions.tryEmit(PressInteraction.Release(press)); released = true
                                menu()
                            }
                            break
                        }
                    }
                }
            } finally {
                if (!released) interactions.tryEmit(PressInteraction.Cancel(press))
                dragging = false
                if (!committed) offset = 0f
            }
        }
    }
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).onSizeChanged { width = it.width }.then(touch)
        .testTag("session-archive-container-$sessionId")) {
        if (rendered < -0.5f || committed || pending) Box(
            Modifier.matchParentSize().background(background).testTag("session-archive-background-$sessionId"),
            contentAlignment = AbsoluteAlignment.CenterRight,
        ) {
            Column(Modifier.padding(end = 20.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (pending) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                else ArchiveSymbol()
                Text(when {
                    pending -> stringResource(R.string.sessions_swipe_archiving)
                    committed -> stringResource(R.string.sessions_swipe_archived)
                    armed -> releaseLabel
                    else -> archiveLabel
                }, style = MaterialTheme.typography.labelMedium, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 128.dp))
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth().graphicsLayer { translationX = rendered }
                .testTag("session-row-$sessionId")
                .semantics {
                    if (dragging) stateDescription = if (armed) releaseLabel else archiveLabel
                    customActions = if (canArchive && !pending && !committed) listOf(CustomAccessibilityAction(archiveLabel) { commit(); true }) else emptyList()
                }
                .combinedClickable(enabled = !pending && !committed, interactionSource = interactions,
                    indication = LocalIndication.current, onClick = { click() }, onLongClick = { menu() }),
        ) { content() }
    }
}

@Composable
private fun ArchiveSymbol() {
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24
        val stroke = Stroke(1.8f * u)
        drawRoundRect(Color.White, Offset(4 * u, 3 * u), Size(16 * u, 5 * u), CornerRadius(u), style = stroke)
        drawRoundRect(Color.White, Offset(5 * u, 8 * u), Size(14 * u, 13 * u), CornerRadius(u), style = stroke)
        drawLine(Color.White, Offset(12 * u, 11 * u), Offset(12 * u, 17 * u), 1.8f * u, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(9 * u, 14 * u), Offset(12 * u, 17 * u), 1.8f * u, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(15 * u, 14 * u), Offset(12 * u, 17 * u), 1.8f * u, cap = StrokeCap.Round)
    }
}
