package app.hapi.companion.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.hapi.companion.R
import app.hapi.data.store.MachineListStore
import app.hapi.protocol.wire.Machine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Store owns SSE updates; API success requests refresh even if the page closes. */
class MachinesSettingsViewModel(
    private val store: MachineListStore,
    private val rename: suspend (String, String) -> Unit,
    private val scope: CoroutineScope,
) {
    val machines = store.machines
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableRenamed = MutableStateFlow(0L)
    val renamed = mutableRenamed.asStateFlow()
    init { refresh() }
    fun refresh() {
        if (!mutableBusy.compareAndSet(false, true)) return
        mutableError.value = null
        scope.launch {
            try { store.refresh() } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { mutableError.value = error.message ?: "Machines unavailable"
            } finally { mutableBusy.value = false }
        }
    }
    fun renameMachine(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.length > 64 || !mutableBusy.compareAndSet(false, true)) return
        mutableError.value = null
        scope.launch {
            try {
                rename(id, trimmed)
                store.scheduleRefresh()
                mutableRenamed.value++
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { mutableError.value = error.message ?: "Rename failed"
            } finally { mutableBusy.value = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachinesSettingsScreen(viewModel: MachinesSettingsViewModel, onBack: () -> Unit) {
    val machines by viewModel.machines.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    val renamed by viewModel.renamed.collectAsState()
    var editing by remember { mutableStateOf<Machine?>(null) }
    LaunchedEffect(renamed) { if (renamed > 0) editing = null }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_machines)) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back)) }
    }, actions = { TextButton(onClick = viewModel::refresh, enabled = !busy) { Text(stringResource(R.string.machines_refresh)) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.machines_intro), style = MaterialTheme.typography.bodyMedium)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                if (editing == null) error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (machines.isEmpty() && !busy) Text(stringResource(R.string.machines_empty))
            }
            items(machines, key = { it.id }) { machine ->
                Surface(onClick = { editing = machine }, enabled = !busy, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    ListItem(headlineContent = { Text(machine.metadata?.displayName?.takeIf { it.isNotBlank() } ?: machine.metadata?.host ?: machine.id.take(8)) },
                        supportingContent = { Text(listOfNotNull(machine.metadata?.host, machine.metadata?.platform, machine.id.take(8)).joinToString(" · ")) },
                        trailingContent = { Text(stringResource(R.string.sessions_action_rename), style = MaterialTheme.typography.labelMedium) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
    }
    editing?.let { machine -> key(machine.id) {
        var name by remember {
            val text = machine.metadata?.displayName.orEmpty()
            mutableStateOf(TextFieldValue(text, selection = TextRange(text.length)))
        }
        AlertDialog(onDismissRequest = { if (!busy) editing = null }, title = { Text(stringResource(R.string.machines_rename)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.machines_name)) },
                    placeholder = { Text(machine.metadata?.host.orEmpty()) }, singleLine = true, enabled = !busy,
                    isError = name.text.trim().length > 64, supportingText = { Text(stringResource(R.string.machines_name_hint)) })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(onClick = { viewModel.renameMachine(machine.id, name.text) }, enabled = !busy && name.text.trim().length <= 64) { Text(stringResource(R.string.voice_save)) } },
            dismissButton = { TextButton(onClick = { editing = null }, enabled = !busy) { Text(stringResource(R.string.feature_cancel)) } })
    } }
}
