package app.hapi.companion.feature.newsession

import app.hapi.data.api.HapiApi
import app.hapi.protocol.wire.CodexImportResponse
import app.hapi.protocol.wire.CodexLocalSessionSummary
import app.hapi.protocol.wire.CodexLocalSessionsResponse
import app.hapi.protocol.wire.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

interface CodexSessionImportGateway {
    suspend fun list(machineId: String): CodexLocalSessionsResponse
    suspend fun activeSessionId(machineId: String, nativeSessionId: String): String?
    suspend fun importSession(machineId: String, nativeSessionId: String): CodexImportResponse
    suspend fun resume(hapiSessionId: String): String
}

class ApiCodexSessionImportGateway(private val api: HapiApi) : CodexSessionImportGateway {
    override suspend fun list(machineId: String) = api.getCodexSessions(machineId)
    override suspend fun activeSessionId(machineId: String, nativeSessionId: String) =
        findActiveCodexSessionId(api.getSessions().sessions, machineId, nativeSessionId)
    override suspend fun importSession(machineId: String, nativeSessionId: String) =
        api.importCodexSession(machineId, nativeSessionId)
    override suspend fun resume(hapiSessionId: String) = api.resumeSession(hapiSessionId).sessionId
}

internal fun findActiveCodexSessionId(sessions: List<SessionSummary>, machineId: String, nativeSessionId: String): String? =
    sessions.filter { session ->
        val metadata = session.metadata
        session.active && metadata != null && metadata.lifecycleState != "archived" && metadata.flavor == "codex" &&
            metadata.machineId == machineId && metadata.agentSessionId == nativeSessionId
    }.maxByOrNull { it.updatedAt }?.id

data class CodexSessionImportState(
    val open: Boolean = false,
    val machineId: String = "",
    val machineLabel: String = "",
    val loading: Boolean = false,
    val importing: Boolean = false,
    val sessions: List<CodexLocalSessionSummary> = emptyList(),
    val query: String = "",
    /** null means all directories. */
    val directory: String? = null,
    val selectedId: String? = null,
    val error: String? = null,
) {
    val directories: List<String> get() = sessions.mapNotNull { it.cwd?.trim()?.takeIf(String::isNotEmpty) }.distinct().sorted()
    val filteredSessions: List<CodexLocalSessionSummary> get() {
        val search = query.trim()
        return sessions.filter { session ->
            (directory == null || session.cwd?.trim() == directory) &&
                (search.isEmpty() || listOf(session.title, session.lastUserMessage, session.cwd, session.id, session.originator)
                    .any { it?.contains(search, ignoreCase = true) == true })
        }
    }
    val canImport: Boolean get() = !loading && !importing && sessions.any { it.id == selectedId }
}

/** Keeps the machine/thread pair fixed for the entire import and resume. */
class CodexSessionImportController(
    private val gateway: CodexSessionImportGateway,
    private val scope: CoroutineScope,
    private val loadFailed: String,
    private val importFailed: String,
    private val onImported: suspend (machineId: String, directory: String?, sessionId: String) -> Unit,
) {
    private val mutableState = MutableStateFlow(CodexSessionImportState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    // A failed resume retries the existing imported record, never imports again.
    private var imported: Pair<String, String>? = null

    fun open(machineId: String, machineLabel: String, directory: String?) {
        if (state.value.importing) return
        job?.cancel()
        generation++
        imported = null
        mutableState.value = CodexSessionImportState(open = true, machineId = machineId, machineLabel = machineLabel)
        load(directory)
    }

    fun close() {
        if (state.value.importing) return
        generation++
        job?.cancel()
        imported = null
        mutableState.value = CodexSessionImportState()
    }

    fun refresh() {
        if (!state.value.open || state.value.importing) return
        load(state.value.directory)
    }

    private fun load(preferredDirectory: String?) {
        job?.cancel()
        val token = ++generation
        val machineId = state.value.machineId
        mutableState.update { it.copy(loading = true, error = null) }
        job = scope.launch {
            try {
                val response = gateway.list(machineId)
                if (token != generation) return@launch
                check(response.success) { response.error ?: loadFailed }
                check(response.machineId == null || response.machineId == machineId) { loadFailed }
                val sessions = response.sessions.distinctBy { it.id }.sortedByDescending { it.modifiedAt }
                mutableState.update { current ->
                    current.copy(
                        loading = false, sessions = sessions,
                        directory = preferredDirectory?.trim()?.takeIf { path -> sessions.any { it.cwd?.trim() == path } },
                        selectedId = current.selectedId?.takeIf { id -> sessions.any { it.id == id } },
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (token == generation) mutableState.update { it.copy(loading = false, error = error.message ?: loadFailed) }
            }
        }
    }

    fun setQuery(query: String) {
        if (!state.value.importing) mutableState.update { it.copy(query = query, selectedId = null) }
    }

    fun setDirectory(directory: String?) {
        if (!state.value.importing) mutableState.update { it.copy(directory = directory, selectedId = null) }
    }

    fun select(id: String) {
        if (state.value.importing || state.value.loading || state.value.filteredSessions.none { it.id == id }) return
        mutableState.update { it.copy(selectedId = id, error = null) }
    }

    fun confirm() {
        val current = state.value
        if (!current.open || !current.canImport) return
        val selected = current.sessions.first { it.id == current.selectedId }
        mutableState.update { it.copy(importing = true, error = null) }
        job = scope.launch {
            try {
                val hapiId = imported?.takeIf { it.first == selected.id }?.second
                    ?: gateway.activeSessionId(current.machineId, selected.id)
                    ?: run {
                    val response = gateway.importSession(current.machineId, selected.id)
                    if (!response.success) {
                        // Another client may have attached between lookup and import.
                        val active = gateway.activeSessionId(current.machineId, selected.id)
                        if (active != null) return@run active
                        error(response.error ?: response.message ?: importFailed)
                    }
                    val id = response.hapiSessionIds.firstOrNull()?.takeIf { it.isNotBlank() }
                        ?: error(importFailed)
                    imported = selected.id to id
                    id
                }
                imported = selected.id to hapiId
                val resumedId = gateway.resume(hapiId)
                check(resumedId.isNotBlank()) { importFailed }
                onImported(current.machineId, selected.cwd, resumedId)
                mutableState.value = CodexSessionImportState()
                imported = null
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                mutableState.update { it.copy(importing = false, error = error.message ?: importFailed) }
            }
        }
    }
}
