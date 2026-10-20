package app.hapi.companion.feature.newsession

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hapi.companion.R
import java.text.DateFormat
import java.util.Date

@Composable
internal fun OutlinedCodexImportButton(enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.codex_import_title))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodexSessionImportSheet(
    state: CodexSessionImportState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onQueryChange: (String) -> Unit,
    onDirectoryChange: (String?) -> Unit,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    CodexImportDialog(
        dismissEnabled = !state.importing, onDismiss = onDismiss,
        header = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.codex_import_title), style = MaterialTheme.typography.titleMedium)
                    Text(state.machineLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onRefresh, enabled = !state.loading && !state.importing) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.codex_import_refresh))
                }
            }
        },
    ) {
        Text(stringResource(R.string.codex_import_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = state.query, onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().testTag("codex-import-search"), singleLine = true, enabled = !state.importing,
            label = { Text(stringResource(R.string.codex_import_search)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        )
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (!state.importing) expanded = it }) {
            OutlinedTextField(
                value = state.directory ?: stringResource(R.string.codex_import_all_directories),
                onValueChange = {}, readOnly = true, singleLine = true, enabled = !state.importing,
                label = { Text(stringResource(R.string.new_session_directory)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().testTag("codex-import-directory").menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.codex_import_all_directories)) },
                    onClick = { expanded = false; onDirectoryChange(null) },
                    modifier = Modifier.testTag("codex-import-all-directories"),
                )
                state.directories.forEach { path ->
                    DropdownMenuItem(
                        text = { Text(path) }, onClick = { expanded = false; onDirectoryChange(path) },
                        modifier = Modifier.testTag("codex-import-path-$path"),
                    )
                }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val sessions = state.filteredSessions
        key(state.machineId, state.directory, state.query) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("codex-import-list"), state = rememberLazyListState()) {
                if (state.loading) {
                    item { CircularProgressIndicator(Modifier.padding(20.dp).size(24.dp)) }
                } else if (sessions.isEmpty()) {
                    item {
                        Text(
                            stringResource(if (state.sessions.isEmpty()) R.string.codex_import_empty else R.string.codex_import_no_matches),
                            Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(sessions, key = { it.id }) { session ->
                        Row(
                            modifier = Modifier.fillMaxWidth().testTag("codex-import-${session.id}").selectable(
                                selected = state.selectedId == session.id, enabled = !state.importing,
                                role = Role.RadioButton, onClick = { onSelect(session.id) },
                            ).padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = state.selectedId == session.id, onClick = null, enabled = !state.importing)
                            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(session.title.ifBlank { session.id }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                session.lastUserMessage?.takeIf { it.isNotBlank() && it != session.title }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                session.cwd?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.modifiedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss, enabled = !state.importing) { Text(stringResource(R.string.codex_import_cancel)) }
            Button(onClick = onConfirm, enabled = state.canImport, modifier = Modifier.testTag("codex-import-confirm")) {
                if (state.importing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(if (state.importing) R.string.codex_import_importing else R.string.codex_import_confirm), Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}
