package app.hapi.companion.di

import app.hapi.protocol.wire.HubHealthResponse
import app.hapi.protocol.wire.SUPPORTED_PROTOCOL_VERSION
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class HubOpeningState(
    val hubUrl: String? = null,
    val phase: Phase = Phase.Checking,
    val protocolVersion: Int? = null,
) {
    enum class Phase { Checking, Ready, Failed, Incompatible }
    fun allows(hub: String): Boolean = hubUrl == hub && phase == Phase.Ready
}

/** A cold graph cannot hydrate snapshots until a live health check succeeds.
 * Existing graphs are suspended during a foreground check, keeping drafts and
 * navigation intact. Generations reject late responses after hub/lifecycle changes. */
class HubOpeningGate<G : Any>(
    private val scope: CoroutineScope,
    private val probe: suspend (String) -> HubHealthResponse,
    private val create: (String) -> G,
    private val close: (G) -> Unit,
    private val setGraphForeground: (G, Boolean) -> Unit,
    private val timeoutMs: Long = 8_000,
) {
    private val mutableGraph = MutableStateFlow<G?>(null)
    val graph = mutableGraph.asStateFlow()
    private val mutableState = MutableStateFlow(HubOpeningState())
    val state = mutableState.asStateFlow()
    private var selected: String? = null
    private var foreground = false
    private var generation = 0
    private var pending: Job? = null

    @Synchronized fun selectHub(hubUrl: String?) {
        if (selected == hubUrl) return
        invalidate()
        mutableGraph.value?.let(close)
        mutableGraph.value = null
        selected = hubUrl
        mutableState.value = HubOpeningState(hubUrl)
        if (foreground) check()
    }

    @Synchronized fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (value) check() else {
            invalidate()
            mutableGraph.value?.let { setGraphForeground(it, false) }
            mutableState.value = HubOpeningState(selected)
        }
    }

    @Synchronized fun retry() { if (foreground) check() }

    private fun invalidate() { generation++; pending?.cancel(); pending = null }

    private fun check() {
        val hub = selected ?: return
        if (mutableState.value.phase == HubOpeningState.Phase.Checking && pending?.isActive == true) return
        invalidate()
        mutableGraph.value?.let { setGraphForeground(it, false) }
        mutableState.value = HubOpeningState(hub)
        val attempt = generation
        pending = scope.launch {
            val result = try {
                val health = withTimeout(timeoutMs) { probe(hub) }
                when {
                    health.status != "ok" -> HubOpeningState(hub, HubOpeningState.Phase.Failed)
                    health.protocolVersion != SUPPORTED_PROTOCOL_VERSION -> HubOpeningState(hub, HubOpeningState.Phase.Incompatible, health.protocolVersion)
                    else -> HubOpeningState(hub, HubOpeningState.Phase.Ready)
                }
            } catch (_: TimeoutCancellationException) {
                HubOpeningState(hub, HubOpeningState.Phase.Failed)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                HubOpeningState(hub, HubOpeningState.Phase.Failed)
            }
            synchronized(this@HubOpeningGate) {
                if (attempt != generation || selected != hub || !foreground) return@synchronized
                if (result.phase == HubOpeningState.Phase.Ready) {
                    val connected = mutableGraph.value ?: create(hub)
                    setGraphForeground(connected, true)
                    mutableGraph.value = connected
                }
                mutableState.value = result
            }
        }
    }
}
