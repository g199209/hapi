package app.hapi.companion.feature.home

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.hapi.companion.R
import app.hapi.companion.di.HubOpeningState
import app.hapi.protocol.wire.SUPPORTED_PROTOCOL_VERSION

/** Warm screens stay mounted to retain drafts, viewers and navigation. Their
 * semantics and input are hidden until the new foreground probe succeeds.
 * On cold failure no HubGraph exists, so protected routes have no stores. */
@Composable
internal fun HubOpeningBoundary(
    hubUrl: String?,
    state: HubOpeningState,
    enabled: Boolean = true,
    pairedHubs: List<String> = emptyList(),
    onRetry: () -> Unit = {},
    onSwitchHub: (String) -> Unit = {},
    onPairAnother: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val blocked = enabled && hubUrl != null && !state.allows(hubUrl)
    val focus = LocalFocusManager.current
    LaunchedEffect(blocked) { if (blocked) focus.clearFocus(force = true) }
    Box(Modifier.fillMaxSize()) {
        Box(if (blocked) Modifier.fillMaxSize().focusProperties { canFocus = false }.clearAndSetSemantics {}
            else Modifier.fillMaxSize()) { content() }
        if (blocked) {
            HubOpeningScreen(
                hubUrl = hubUrl!!,
                state = if (state.hubUrl == hubUrl) state else HubOpeningState(hubUrl),
                pairedHubs = pairedHubs,
                onRetry = onRetry,
                onSwitchHub = onSwitchHub,
                onPairAnother = onPairAnother,
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    // Own blank-space hits too. Leave presses available to
                    // scrolling children; consume releases after their controls.
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Final).changes
                            .filter { !it.pressed }.forEach { it.consume() }
                    }
                },
            )
        }
    }
}

@Composable
private fun HubOpeningScreen(
    hubUrl: String,
    state: HubOpeningState,
    pairedHubs: List<String>,
    onRetry: () -> Unit,
    onSwitchHub: (String) -> Unit,
    onPairAnother: () -> Unit,
    modifier: Modifier,
) {
    val checking = state.phase == HubOpeningState.Phase.Checking
    var hubsOpen by remember(hubUrl) { mutableStateOf(false) }
    Surface(modifier.testTag("hub-opening-gate")) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (checking) CircularProgressIndicator(Modifier.size(48.dp))
                else Icon(Icons.Default.Warning, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(if (checking) R.string.hub_opening_connecting else R.string.hub_opening_failed),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(hubUrl, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!checking) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (state.phase == HubOpeningState.Phase.Incompatible)
                            stringResource(if ((state.protocolVersion ?: 0) > SUPPORTED_PROTOCOL_VERSION)
                                R.string.pairing_error_protocol_update_app else R.string.pairing_error_protocol_update_hub,
                                state.protocolVersion ?: 0, SUPPORTED_PROTOCOL_VERSION)
                        else stringResource(R.string.hub_opening_failed_hint),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_retry)) }
                }
                Spacer(Modifier.height(12.dp))
                Box {
                    TextButton(onClick = { hubsOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.home_switch_hub))
                    }
                    DropdownMenu(expanded = hubsOpen, onDismissRequest = { hubsOpen = false }) {
                        pairedHubs.forEach { hub ->
                            DropdownMenuItem(
                                text = { Text(Uri.parse(hub).authority ?: hub) },
                                trailingIcon = { if (hub == hubUrl) Icon(Icons.Default.Check, null) },
                                onClick = { hubsOpen = false; if (hub != hubUrl) onSwitchHub(hub) },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.home_pair_another)) },
                            onClick = { hubsOpen = false; onPairAnother() })
                    }
                }
            }
        }
    }
}
