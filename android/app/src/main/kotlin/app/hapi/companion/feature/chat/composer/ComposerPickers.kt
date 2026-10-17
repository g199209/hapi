package app.hapi.companion.feature.chat.composer

import app.hapi.data.api.HapiApi
import app.hapi.data.store.MachineListStore
import app.hapi.data.store.SessionListStore
import app.hapi.companion.feature.sessions.SessionListViewModel
import app.hapi.companion.feature.sessions.matchesSessionSearch
import app.hapi.protocol.wire.FileSearchItem
import app.hapi.protocol.wire.HapiJson
import app.hapi.protocol.wire.SkillSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.net.URLEncoder

/** Same copy-reference grammar used by WebUI and the Hub's peer citation expansion. */
fun sessionReference(title: String, id: String): String {
    val clean = title.replace(Regex("\\s+"), " ").trim()
    val end = clean.offsetByCodePoints(0, minOf(120, clean.codePointCount(0, clean.length)))
    val label = clean.substring(0, end)
    val path = "/sessions/" + URLEncoder.encode(id, "UTF-8").replace("+", "%20")
    val base = if (label.isEmpty()) "See HAPI session $path for context"
        else "See session ${HapiJson.encodeToString(label)} ($path) for context"
    return "$base. HAPI hub peer - call inspect_peer with that session id; do not Grep/Glob/Read /sessions/ as a local file."
}

data class PickerToken(val prefix: Char, val query: String, val start: Int, val end: Int)

/** UTF-16 offsets match TextFieldState. Only whole tokens at whitespace boundaries qualify. */
fun pickerToken(text: String, caret: Int, selectionEnd: Int = caret, composing: Boolean = false): PickerToken? {
    if (composing || caret != selectionEnd || caret !in 0..text.length) return null
    var start = caret
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    if (start == caret || text[start] !in "@$") return null
    var end = caret
    while (end < text.length && !text[end].isWhitespace()) end++
    return PickerToken(text[start], text.substring(start + 1, caret), start, end)
}

data class ComposerPick(val key: String, val label: String, val description: String?, val text: String)
data class PickerUi(val token: PickerToken? = null, val items: List<ComposerPick> = emptyList(), val loading: Boolean = false, val error: String? = null)

interface ComposerPickerApi {
    suspend fun skills(): List<SkillSummary>
    suspend fun files(query: String): List<FileSearchItem>
}
class HapiComposerPickerApi(private val api: HapiApi, private val sessionId: String) : ComposerPickerApi {
    override suspend fun skills(): List<SkillSummary> {
        val result = api.getSkills(sessionId)
        check(result.success) { result.error ?: "Skills unavailable" }
        return result.skills.orEmpty()
    }
    override suspend fun files(query: String): List<FileSearchItem> {
        val result = api.searchSessionFiles(sessionId, query, 50)
        check(result.success) { result.error ?: "File search unavailable" }
        return result.files.orEmpty()
    }
}

/** Suggestions observe the editing buffer; they never write into it. Selection is an explicit UI edit. */
class ComposerPickers(
    private val sessionId: String,
    private val api: ComposerPickerApi,
    private val sessions: SessionListStore,
    private val machines: MachineListStore,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 180,
) {
    private val mutable = MutableStateFlow(PickerUi())
    val state = mutable.asStateFlow()
    private var token: PickerToken? = null
    private var flavor: String? = null
    private var revision = 0L
    private var job: Job? = null
    private var remote: List<ComposerPick> = emptyList()
    private var skills: List<SkillSummary>? = null
    private val recentSkills = mutableMapOf<String, Long>()
    private var sequence = 0L

    init {
        scope.launch { combine(sessions.sessions, machines.machines) { _, _ -> Unit }.collect {
            if (token?.prefix == '@') publish()
        } }
    }

    fun update(next: PickerToken?, agentFlavor: String?) {
        if (token == next && flavor == agentFlavor) return
        if (next?.prefix == '$' && token?.prefix != '$') skills = null
        token = next; flavor = agentFlavor
        val version = ++revision
        job?.cancel(); remote = emptyList()
        mutable.value = PickerUi(token = next)
        if (next == null) return
        val remoteNeeded = next.prefix == '$' || agentFlavor in listOf("codex", "copilot")
        publish(loading = remoteNeeded)
        if (!remoteNeeded) return
        job = scope.launch {
            delay(debounceMs)
            try {
                val result = if (next.prefix == '$') {
                    val entries = skills ?: api.skills().also { skills = it }
                    entries.filter { it.name.contains(next.query, ignoreCase = true) }
                        .sortedWith(compareBy<SkillSummary> {
                            when { it.name.equals(next.query, true) -> 0; it.name.startsWith(next.query, true) -> 1; else -> 2 }
                        }.thenByDescending { recentSkills[it.name] ?: 0L }.thenBy { it.name.lowercase() })
                        .take(50).map { ComposerPick("skill:${it.name}", "$${it.name}", it.description, "$${it.name}") }
                } else api.files(next.query).distinctBy { it.fullPath }.map {
                    val insert = if (agentFlavor == "copilot") "@${it.fullPath}"
                        else "@\"${it.fullPath.replace("\\", "\\\\").replace("\"", "\\\"")}\""
                    ComposerPick("file:${it.fullPath}", "@${it.fileName}", it.filePath, insert)
                }
                if (version == revision) { remote = result; publish(loading = false, error = null) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (version == revision) publish(loading = false, error = error.message ?: "Suggestions unavailable")
            }
        }
    }

    fun selected(item: ComposerPick) {
        if (item.key.startsWith("skill:")) recentSkills[item.key.removePrefix("skill:")] = ++sequence
        update(null, flavor)
    }

    private fun publish(loading: Boolean = mutable.value.loading, error: String? = mutable.value.error) {
        val current = token ?: return
        val local = if (current.prefix != '@') emptyList() else {
            val labels = machines.machines.value.associate { it.id to (it.metadata?.displayName ?: it.metadata?.host.orEmpty()) }
            sessions.sessions.value.asSequence().filter {
                it.id != sessionId && it.hasConversationContent == true &&
                    (current.query.isNotBlank() || it.metadata?.lifecycleState != "archived") &&
                    matchesSessionSearch(it, current.query, labels[it.metadata?.machineId].orEmpty())
            }.sortedWith(compareByDescending<app.hapi.protocol.wire.SessionSummary> {
                val title = SessionListViewModel.sessionTitle(it)
                when { current.query.isBlank() -> 0; title.equals(current.query, true) -> 4; title.startsWith(current.query, true) -> 3; title.contains(current.query, true) -> 2; else -> 1 }
            }.thenByDescending { it.active }.thenByDescending { it.updatedAt }).take(20).map {
                val title = SessionListViewModel.sessionTitle(it)
                ComposerPick("session:${it.id}", "@$title", listOfNotNull(it.metadata?.path, labels[it.metadata?.machineId]).joinToString(" · "), sessionReference(title, it.id))
            }.toList()
        }
        mutable.value = PickerUi(current, local + remote, loading, error)
    }
}

/** Recheck the live buffer before replacing a candidate: stale network replies cannot edit newer text. */
fun applyComposerPick(editor: androidx.compose.foundation.text.input.TextFieldState, state: PickerUi, item: ComposerPick): Boolean {
    val current = pickerToken(editor.text.toString(), editor.selection.start, editor.selection.end, editor.composition != null)
    if (current == null || current != state.token || item !in state.items) return false
    val separatorExists = editor.text.getOrNull(current.end)?.isWhitespace() == true
    editor.edit {
        replace(current.start, current.end, item.text + if (separatorExists) "" else " ")
        selection = androidx.compose.ui.text.TextRange(current.start + item.text.length + 1)
    }
    return true
}
