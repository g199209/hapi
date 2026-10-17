package app.hapi.companion.feature.chat.history

import app.hapi.data.api.ApiError
import app.hapi.data.api.ConversationHistoryApi
import app.hapi.protocol.window.MessageWindowState
import app.hapi.protocol.window.MessageViewMode
import app.hapi.protocol.wire.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Current fork belongs in the session menu, separate from historical message boundaries. */
enum class HistoryKind { ForkCurrent, ForkBefore, RewindBefore }

data class HistoryAvailability(
    val forkCurrent: Boolean = false,
    val forkAtMessage: Boolean = false,
    val rewindToMessage: Boolean = false,
    val nativePoints: Set<String> = emptySet(),
    val ready: Boolean = false,
) {
    fun supports(kind: HistoryKind, localId: String?): Boolean = when (kind) {
        HistoryKind.ForkCurrent -> forkCurrent
        HistoryKind.ForkBefore -> forkAtMessage && localId != null && localId in nativePoints
        HistoryKind.RewindBefore -> rewindToMessage && localId != null && localId in nativePoints
    }
}

data class HistoryContext(
    val availability: HistoryAvailability = HistoryAvailability(),
    val epoch: Long? = null,
    val tailRevision: Long = 0,
    val nativePointsCatalog: Set<String> = availability.nativePoints,
) {
    /** A confirmed point may scroll out of the retained window while its dialog remains open. */
    fun stillSupports(kind: HistoryKind, localId: String?): Boolean =
        availability.copy(nativePoints = nativePointsCatalog).supports(kind, localId)
}

fun historyContext(session: Session?, window: MessageWindowState?, blocked: Boolean = false): HistoryContext {
    val metadata = session?.metadata
    val caps = metadata?.capabilities
    val history = caps?.conversationHistory
    val points = metadata?.conversationHistoryPoints.orEmpty()
    val invoked = window?.messages.orEmpty().asSequence().filter { it.invokedAtOrNull != null }
        .mapNotNull { it.localId }.filter { points[it] == true }.toSet()
    val available = session?.active == true && !session.thinking && metadata?.conversationHistoryDiverged != true &&
        (session.agentState?.controlledByUser != true || caps?.concurrentClients == true) &&
        !blocked && window != null && window.syncGeneration > 0 && window.epoch != null && (!window.requiresLatestReset || window.viewMode == MessageViewMode.History) &&
        window.messages.none { it.isQueuedForInvocation }
    return HistoryContext(HistoryAvailability(history?.forkCurrent == true, history?.forkAtMessage == true,
        history?.rewindToMessage == true, invoked, available), window?.epoch, window?.tailRevision ?: 0, points.filterValues { it }.keys)
}

data class HistoryRequest(
    val kind: HistoryKind,
    val localId: String?,
    val preview: String,
    val epoch: Long?,
    val tailRevision: Long,
)

enum class HistoryErrorKind { Stale, RequestFailed, RefreshFailed }
data class HistoryFailure(val kind: HistoryErrorKind, val detail: String? = null)
data class HistoryUi(
    val request: HistoryRequest? = null,
    val pending: Boolean = false,
    val error: HistoryFailure? = null,
    val offerFork: Boolean = false,
    /** Once a rewind succeeded (or its outcome is unknown), retry only reconciliation. */
    val refreshOnly: Boolean = false,
    /** Navigation is acknowledged by the host, so rotation cannot lose a completed fork. */
    val forkedSessionId: String? = null,
)

/** Holder/UI-scoped state outlives rows and rotation. No automatic mutation retries. */
class ConversationHistoryController(
    private val sessionId: String,
    private val api: ConversationHistoryApi?,
    private val context: () -> HistoryContext,
    private val refresh: suspend () -> Unit,
    private val onForked: (String) -> Unit,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(HistoryUi())
    val state = mutable.asStateFlow()
    private var recoveryRequest: HistoryRequest? = null

    fun request(kind: HistoryKind, localId: String? = null, preview: String = "") {
        if (mutable.value.pending || mutable.value.forkedSessionId != null || api == null) return
        recoveryRequest?.let { mutable.value = HistoryUi(request = it, refreshOnly = true); return }
        val current = context()
        if (!current.availability.ready || !current.availability.supports(kind, localId)) return
        mutable.value = HistoryUi(HistoryRequest(kind, localId, preview.take(240), current.epoch, current.tailRevision))
    }

    fun dismiss() {
        if (!mutable.value.pending && mutable.value.forkedSessionId == null) {
            if (mutable.value.refreshOnly) recoveryRequest = mutable.value.request
            mutable.value = HistoryUi()
        }
    }

    fun acknowledgeFork(childId: String) {
        if (mutable.value.forkedSessionId == childId && !mutable.value.pending) mutable.value = HistoryUi()
    }

    fun confirm() {
        val currentState = mutable.value
        val request = currentState.request ?: return
        if (currentState.pending || api == null || currentState.offerFork) return
        if (!currentState.refreshOnly) {
            val current = context()
            if (!current.availability.ready || !current.stillSupports(request.kind, request.localId) ||
                current.epoch != request.epoch ||
                (request.kind == HistoryKind.ForkCurrent && current.tailRevision != request.tailRevision)) {
                mutable.value = currentState.copy(error = HistoryFailure(HistoryErrorKind.Stale))
                return
            }
        }
        mutable.value = currentState.copy(pending = true, error = null)
        scope.launch {
            var childToOpen: String? = null
            try {
                when {
                    currentState.refreshOnly -> refresh()
                    request.kind == HistoryKind.RewindBefore -> {
                        api.rewindConversation(sessionId, requireNotNull(request.localId))
                        // Record the completed mutation before refresh can fail.
                        recoveryRequest = request
                        mutable.value = mutable.value.copy(refreshOnly = true)
                        refresh()
                    }
                    else -> {
                        val child = api.forkConversation(sessionId, request.localId).sessionId
                        check(child.isNotBlank() && child != sessionId) { "Fork did not return an independent session" }
                        onForked(child)
                        childToOpen = child
                    }
                }
                recoveryRequest = null
                mutable.value = HistoryUi(forkedSessionId = childToOpen)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val rewind = request.kind == HistoryKind.RewindBefore
                val safeFallback = !mutable.value.refreshOnly && rewind && error is ApiError &&
                    error.code == "ambiguous_native_boundary_fork_safe" && context().stillSupports(HistoryKind.ForkBefore, request.localId)
                val unknownRewind = rewind && !mutable.value.refreshOnly && !safeFallback &&
                    (error !is ApiError || hydrateFailed(error))
                if (unknownRewind) {
                    recoveryRequest = request
                    mutable.value = mutable.value.copy(refreshOnly = true)
                    try { refresh() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* explicit refresh remains available */ }
                }
                mutable.value = mutable.value.copy(pending = false, offerFork = safeFallback,
                    error = HistoryFailure(if (mutable.value.refreshOnly) HistoryErrorKind.RefreshFailed else HistoryErrorKind.RequestFailed, error.message))
            } finally {
                if (mutable.value.pending) mutable.value = mutable.value.copy(pending = false)
            }
        }
    }

    /** An ambiguous native rewind offers an explicit fork decision, never an automatic fork. */
    fun confirmFallback() {
        val current = mutable.value
        val request = current.request ?: return
        if (!current.offerFork || current.pending) return
        mutable.value = current.copy(request = request.copy(kind = HistoryKind.ForkBefore), offerFork = false, error = null)
        confirm()
    }

    private fun hydrateFailed(error: ApiError): Boolean = runCatching {
        Json.parseToJsonElement(error.body.orEmpty()).jsonObject["hydrateFailed"]?.jsonPrimitive?.booleanOrNull == true
    }.getOrDefault(false)
}
