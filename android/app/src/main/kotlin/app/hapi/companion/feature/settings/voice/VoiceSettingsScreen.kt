package app.hapi.companion.feature.settings.voice

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.hapi.companion.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(viewModel: VoiceSettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var selected by rememberSaveable { mutableStateOf(DictationProvider.OpenAI) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.voice_dictation)) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back)) }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.voice_intro), style = MaterialTheme.typography.bodyMedium)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::refresh, enabled = !state.busy) { Text(stringResource(R.string.chat_retry)) }
            }
            val status = state.status
            if (status != null) {
                Text(stringResource(R.string.voice_providers), style = MaterialTheme.typography.titleSmall)
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (!state.busy) expanded = it }) {
                    OutlinedTextField(value = selected.label, onValueChange = {}, readOnly = true, enabled = !state.busy,
                        label = { Text(stringResource(R.string.voice_providers)) },
                        supportingText = { Text(stringResource(if (selected.configured(status)) R.string.voice_configured else R.string.voice_not_configured)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DictationProvider.entries.forEach { provider -> DropdownMenuItem(
                            text = { Column { Text(provider.label); Text(stringResource(if (provider.configured(status)) R.string.voice_configured else R.string.voice_not_configured), style = MaterialTheme.typography.labelSmall) } },
                            onClick = { selected = provider; expanded = false }) }
                    }
                }
                // Secrets stay only in the mounted form, never saved state or preferences.
                key(selected, state.savedRevision, status) {
                    var apiKey by remember { mutableStateOf("") }
                    var baseUrl by remember { mutableStateOf(status.openaiCompatible.baseUrl.orEmpty()) }
                    var model by remember { mutableStateOf(status.openaiCompatible.model.orEmpty()) }
                    var confirmClear by remember { mutableStateOf(false) }
                    val credential = selected.credential(status)
                    val editable = selected.editable(status)
                    val compatible = selected == DictationProvider.Compatible
                    HorizontalDivider()
                    Text(selected.label, style = MaterialTheme.typography.titleMedium)
                    if (compatible) {
                        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, enabled = !state.busy && status.openaiCompatible.baseUrlEditable,
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(model, { model = it }, label = { Text("Model") }, enabled = !state.busy && status.openaiCompatible.modelEditable,
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key") },
                        placeholder = { Text(credential.hint ?: stringResource(R.string.voice_api_key_hint)) },
                        supportingText = { Text(stringResource(R.string.voice_keep_key)) },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !state.busy && credential.editable, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (!credential.editable || (compatible && (!status.openaiCompatible.baseUrlEditable || !status.openaiCompatible.modelEditable))) {
                        Text(stringResource(R.string.voice_env_locked), style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.savedRevision > 0 && state.error == null) Text(stringResource(R.string.voice_saved), color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { viewModel.save(selected, apiKey, baseUrl, model) }, enabled = !state.busy && editable &&
                            (apiKey.isNotBlank() || (compatible && ((status.openaiCompatible.baseUrlEditable && baseUrl.isNotBlank()) || (status.openaiCompatible.modelEditable && model.isNotBlank()))))) {
                            Text(stringResource(R.string.voice_save))
                        }
                        TextButton(onClick = { confirmClear = true }, enabled = !state.busy && editable &&
                            (selected.configured(status) || credential.source == "settings" || (compatible && status.openaiCompatible.source == "settings"))) {
                            Text(stringResource(R.string.voice_clear))
                        }
                    }
                    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
                        title = { Text(stringResource(R.string.voice_clear)) }, text = { Text(stringResource(R.string.voice_clear_confirm, selected.label)) },
                        confirmButton = { TextButton(onClick = { confirmClear = false; viewModel.save(selected, "", "", "", clear = true) }) { Text(stringResource(R.string.voice_clear)) } },
                        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.feature_cancel)) } })
                }
            }
        }
    }
}
