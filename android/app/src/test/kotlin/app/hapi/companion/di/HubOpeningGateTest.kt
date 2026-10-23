@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package app.hapi.companion.di

import app.hapi.protocol.wire.HubHealthResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*
import java.io.IOException

class HubOpeningGateTest {
    private val hub = "https://home.example"
    private fun healthy(version: Int = 1) = HubHealthResponse("ok", version)
    private data class Graph(val hub: String)
    private class Fixture(scope: CoroutineScope, probe: suspend (String) -> HubHealthResponse) {
        val created = mutableListOf<Graph>()
        val closed = mutableListOf<Graph>()
        val foreground = mutableListOf<Pair<Graph, Boolean>>()
        val gate = HubOpeningGate(scope, probe, { Graph(it).also(created::add) }, closed::add,
            { graph, visible -> foreground += graph to visible })
    }

    @Test fun coldOpenCannotConstructSnapshotStoresBeforeSuccessfulHealth() = runTest {
        val response = CompletableDeferred<HubHealthResponse>()
        var calls = 0
        val f = Fixture(this) { calls++; response.await() }
        f.gate.selectHub(hub); runCurrent()
        assertEquals(0, calls); assertTrue(f.created.isEmpty())
        f.gate.setForeground(true); runCurrent()
        assertEquals(1, calls); assertNull(f.gate.graph.value)
        assertEquals(HubOpeningState.Phase.Checking, f.gate.state.value.phase)
        response.complete(healthy()); runCurrent()
        assertTrue(f.gate.state.value.allows(hub)); assertEquals(1, f.created.size)
    }
    @Test fun failureDoesNotHydrateCachesAndRetryBuildsGraphOnlyAfterRecovery() = runTest {
        var online = false
        val f = Fixture(this) { if (!online) throw IOException("offline") else healthy() }
        f.gate.selectHub(hub); f.gate.setForeground(true); runCurrent()
        assertEquals(HubOpeningState.Phase.Failed, f.gate.state.value.phase)
        assertTrue(f.created.isEmpty()); assertNull(f.gate.graph.value)
        online = true; f.gate.retry(); runCurrent()
        assertTrue(f.gate.state.value.allows(hub)); assertEquals(1, f.created.size)
    }
    @Test fun stalledHubHasBoundedWaitAndNoOfflineFallback() = runTest {
        val f = Fixture(this) { awaitCancellation() }
        f.gate.selectHub(hub); f.gate.setForeground(true); runCurrent()
        advanceTimeBy(8_000); runCurrent()
        assertEquals(HubOpeningState.Phase.Failed, f.gate.state.value.phase)
        assertNull(f.gate.graph.value); assertTrue(f.created.isEmpty())
    }
    @Test fun everyForegroundRechecksAndRetainsTheExistingGraphForDrafts() = runTest {
        var online = true
        var calls = 0
        val f = Fixture(this) { calls++; if (online) healthy() else throw IOException() }
        f.gate.selectHub(hub); f.gate.setForeground(true); runCurrent()
        val graph = f.gate.graph.value
        f.gate.setForeground(false)
        assertFalse(f.gate.state.value.allows(hub)); assertEquals(graph to false, f.foreground.last())
        online = false; f.gate.setForeground(true); runCurrent()
        assertEquals(2, calls); assertEquals(HubOpeningState.Phase.Failed, f.gate.state.value.phase)
        assertSame(graph, f.gate.graph.value)
        online = true; f.gate.retry(); runCurrent()
        assertSame(graph, f.gate.graph.value); assertEquals(1, f.created.size)
        assertEquals(graph to true, f.foreground.last())
    }
    @Test fun staleHubProbeCannotExposeThePreviousHubsRecords() = runTest {
        val delayed = CompletableDeferred<HubHealthResponse>()
        val other = "https://other.example"
        val f = Fixture(this) { if (it == hub) withContext(NonCancellable) { delayed.await() } else healthy() }
        f.gate.selectHub(hub); f.gate.setForeground(true); runCurrent()
        f.gate.selectHub(other); runCurrent()
        delayed.complete(healthy()); runCurrent()
        assertEquals(listOf(Graph(other)), f.created)
        assertTrue(f.gate.state.value.allows(other)); assertFalse(f.gate.state.value.allows(hub))
    }
    @Test fun signOutClosesTheGraphAndStopsRetryingTheRemovedHub() = runTest {
        var calls = 0
        val f = Fixture(this) { calls++; healthy() }
        f.gate.selectHub(hub); f.gate.setForeground(true); runCurrent()
        val graph = f.gate.graph.value
        f.gate.selectHub(null); f.gate.retry(); runCurrent()
        assertEquals(listOf(graph), f.closed); assertNull(f.gate.graph.value); assertEquals(1, calls)
    }
    @Test fun incompatibleOrUnhealthyServerNeverCreatesCacheStores() = runTest {
        val f = Fixture(this) { healthy(2) }
        f.gate.selectHub(hub); f.gate.setForeground(true); runCurrent()
        assertEquals(HubOpeningState.Phase.Incompatible, f.gate.state.value.phase)
        assertEquals(2, f.gate.state.value.protocolVersion); assertTrue(f.created.isEmpty())
        val g = Fixture(this) { HubHealthResponse("error", 1) }
        g.gate.selectHub(hub); g.gate.setForeground(true); runCurrent()
        assertEquals(HubOpeningState.Phase.Failed, g.gate.state.value.phase); assertTrue(g.created.isEmpty())
    }
    @Test fun sameForegroundAndRepeatedRetryDoNotDuplicateAnInFlightCheck() = runTest {
        val response = CompletableDeferred<HubHealthResponse>()
        var calls = 0
        val f = Fixture(this) { calls++; response.await() }
        f.gate.selectHub(hub); f.gate.setForeground(true); runCurrent()
        repeat(3) { f.gate.setForeground(true); f.gate.selectHub(hub); f.gate.retry() }
        runCurrent(); assertEquals(1, calls)
        response.complete(healthy()); runCurrent()
        f.gate.setForeground(true); runCurrent(); assertEquals(1, calls)
    }
    @Test fun backgroundingRejectsALateSuccessAndReopeningChecksAgain() = runTest {
        val response = CompletableDeferred<HubHealthResponse>()
        val f = Fixture(this) { withContext(NonCancellable) { response.await() } }
        f.gate.selectHub(hub); f.gate.setForeground(true); runCurrent()
        f.gate.setForeground(false); response.complete(healthy()); runCurrent()
        assertTrue(f.created.isEmpty()); assertFalse(f.gate.state.value.allows(hub))
        f.gate.setForeground(true); runCurrent()
        assertTrue(f.gate.state.value.allows(hub)); assertEquals(1, f.created.size)
    }
}
