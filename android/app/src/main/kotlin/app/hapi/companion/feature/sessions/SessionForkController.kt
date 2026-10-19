package app.hapi.companion.feature.sessions

import app.hapi.companion.feature.chat.history.*
import app.hapi.protocol.wire.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface SessionForkGateway {
    suspend fun load(sessionId: String): Session
    suspend fun fork(sessionId: String): String
}

data class SessionForkUi(
    val sessionId: String? = null,
    val title: String = "",
    val loading: Boolean = false,
    val supported: Boolean = false,
    val ready: Boolean = false,
    val lastMessageAt: Long? = null,
    val dialog: HistoryUi = HistoryUi(),
) {
    val showAction: Boolean get() = loading || supported
}

/** List-owned current-history fork. Confirmation and navigation survive rotation. */
class SessionForkController(
    private val gateway: SessionForkGateway?,
    private val scope: CoroutineScope,
    private val onForked: () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val onLoadError: (String, String?) -> Unit,
) {
    private val mutable = MutableStateFlow(SessionForkUi())
    val state = mutable.asStateFlow()
    private var loadJob: Job? = null
    private var generation = 0L

    fun select(sessionId: String, title: String): Boolean {
        if (gateway == null || mutable.value.dialog.pending || mutable.value.dialog.request != null || mutable.value.dialog.forkedSessionId != null) return false
        loadJob?.cancel()
        val selected = ++generation
        mutable.value = SessionForkUi(sessionId, title, loading = true)
        loadJob = scope.launch(dispatcher) {
            try {
                val session = gateway.load(sessionId)
                check(session.id == sessionId)
                if (generation == selected) mutable.value = mutable.value.copy(
                    loading = false, supported = supports(session), ready = ready(session), lastMessageAt = session.lastMessageAt,
                )
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == selected) {
                    mutable.value = mutable.value.copy(loading = false)
                    onLoadError(sessionId, error.message)
                }
            }
        }
        return true
    }

    fun request() {
        val current = mutable.value
        if (!current.ready || current.loading || current.dialog.pending || current.dialog.forkedSessionId != null) return
        mutable.value = current.copy(dialog = HistoryUi(request = HistoryRequest(HistoryKind.ForkCurrent, null, current.title, null, current.lastMessageAt ?: 0)))
    }

    fun dismiss() {
        val current = mutable.value
        if (current.dialog.pending || current.dialog.forkedSessionId != null) return
        generation++
        loadJob?.cancel()
        mutable.value = SessionForkUi()
    }

    fun confirm() {
        val current = mutable.value
        val sessionId = current.sessionId ?: return
        if (gateway == null || current.dialog.request == null || current.dialog.pending || current.dialog.error?.kind == HistoryErrorKind.Stale) return
        mutable.value = current.copy(dialog = current.dialog.copy(pending = true, error = null))
        scope.launch(dispatcher) {
            try {
                val fresh = gateway.load(sessionId)
                if (fresh.id != sessionId || !ready(fresh) || fresh.lastMessageAt != current.lastMessageAt) {
                    mutable.value = mutable.value.copy(dialog = current.dialog.copy(error = HistoryFailure(HistoryErrorKind.Stale)))
                    return@launch
                }
                // Hub checks queued inputs and native readiness again before mutation.
                val child = gateway.fork(sessionId)
                check(child.isNotBlank() && child != sessionId) { "Fork did not return an independent session" }
                onForked()
                mutable.value = current.copy(dialog = HistoryUi(forkedSessionId = child))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.value = current.copy(dialog = current.dialog.copy(error = HistoryFailure(HistoryErrorKind.RequestFailed, error.message)))
            }
        }
    }

    fun acknowledgeFork(childId: String) {
        if (mutable.value.dialog.forkedSessionId == childId) mutable.value = SessionForkUi()
    }

    private fun supports(session: Session) = session.metadata?.capabilities?.conversationHistory?.forkCurrent == true
    private fun ready(session: Session): Boolean = supports(session) && session.active && !session.thinking &&
        session.metadata?.conversationHistoryDiverged != true &&
        (session.agentState?.controlledByUser != true || session.metadata?.capabilities?.concurrentClients == true)
}
