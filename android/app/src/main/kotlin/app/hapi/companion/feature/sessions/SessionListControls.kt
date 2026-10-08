package app.hapi.companion.feature.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.hapi.companion.R
import java.time.ZoneOffset

/** Expanded in the existing toolbar title slot; no extra list row. */
@Composable
internal fun SessionSearchInput(query: String, onQueryChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().focusRequester(requester),
        placeholder = { Text(stringResource(R.string.sessions_search_open), maxLines = 1) },
        singleLine = true,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
    )
}

/** Only an applied date filter consumes space below the tabs. */
@Composable
internal fun SessionDateFilterSummary(dates: SessionDateRange?, onClear: () -> Unit) {
    dates ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        val range = if (dates.start == dates.endInclusive) dates.start.toString()
            else "${dates.start} – ${dates.endInclusive}"
        Text(stringResource(R.string.sessions_date_selected, range),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        IconButton(onClick = onClear) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.sessions_date_clear), modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionDateFilterDialog(
    current: SessionDateRange?,
    onDismiss: () -> Unit,
    onApply: (SessionDateRange?) -> Unit,
) {
    val picker = rememberDateRangePickerState(
        initialSelectedStartDateMillis = current?.start?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        initialSelectedEndDateMillis = current?.endInclusive?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
    )
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Text(stringResource(R.string.sessions_date_filter), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp, end = 24.dp))
                Text(stringResource(R.string.sessions_date_hint), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                DateRangePicker(state = picker, modifier = Modifier.weight(1f), title = null, showModeToggle = true)
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onApply(null) }) { Text(stringResource(R.string.sessions_clear_filter)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.sessions_filter_cancel)) }
                    TextButton(enabled = picker.selectedStartDateMillis != null, onClick = {
                        picker.selectedStartDateMillis?.let { onApply(SessionDateRange.fromPicker(it, picker.selectedEndDateMillis)) }
                    }) { Text(stringResource(R.string.sessions_filter_apply)) }
                }
            }
        }
    }
}
