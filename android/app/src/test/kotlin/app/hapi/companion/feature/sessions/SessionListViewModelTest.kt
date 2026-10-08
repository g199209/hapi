package app.hapi.companion.feature.sessions

import app.hapi.data.store.LastSeenStore
import app.hapi.data.store.MachineListStore
import app.hapi.data.store.SessionListStore
import app.hapi.protocol.wire.Machine
import app.hapi.protocol.wire.MachineMetadata
import app.hapi.protocol.wire.SessionSummary
import app.hapi.protocol.wire.SessionSummaryMetadata
import app.hapi.protocol.wire.SummaryText
import app.hapi.protocol.wire.SyncEvent
import app.hapi.protocol.wire.WorktreeMetadata
import app.hapi.protocol.wire.sortSessionSummaries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent

// ------------------------------------------------------------------ fakes --

private class FakeSessionStore : SessionListStore {
    val backing = MutableStateFlow<List<SessionSummary>>(emptyList())
    override val sessions: StateFlow<List<SessionSummary>> = backing
    val calls = MutableStateFlow<List<String>>(emptyList())
    var failRefresh = false

    fun set(vararg rows: SessionSummary) {
        backing.value = sortSessionSummaries(rows.toList())
    }

    private fun record(call: String) {
        calls.value = calls.value + call
    }

    override suspend fun refresh() {
        record("refresh")
        if (failRefresh) throw RuntimeException("offline")
    }

    override fun scheduleRefresh() = record("scheduleRefresh")

    override suspend fun fullResync() = record("fullResync")

    override fun applySessionEvent(scope: app.hapi.data.sse.SseSubscriptionKey, event: SyncEvent) {
        record("event:${event::class.simpleName}")
    }

    override suspend fun setPinMode(sessionId: String, mode: String) {
        record("pin:$sessionId:$mode")
        if (mode == "global") throw RuntimeException("pin exploded")
    }

    override suspend fun archiveSession(sessionId: String) = record("archive:$sessionId")

    var renameFailure: Exception? = null
    override suspend fun renameSession(sessionId: String, name: String) {
        record("rename:$sessionId:$name")
        renameFailure?.let { throw it }
    }

    var deleteFailure: Exception? = null
    override suspend fun deleteSession(sessionId: String) {
        record("delete:$sessionId")
        deleteFailure?.let { throw it }
    }

    var reopenResult: app.hapi.protocol.wire.ReopenSessionResponse? = null
    var reopenFailure: Exception? = null
    override suspend fun reopenSession(sessionId: String): app.hapi.protocol.wire.ReopenSessionResponse {
        record("reopen:$sessionId")
        reopenFailure?.let { throw it }
        return reopenResult
            ?: app.hapi.protocol.wire.ReopenSessionResponse(sessionId = sessionId, resumed = true)
    }
}

private class FakeMachineStore : MachineListStore {
    val backing = MutableStateFlow<List<Machine>>(emptyList())
    override val machines: StateFlow<List<Machine>> = backing
    var refreshes = 0

    override suspend fun refresh() {
        refreshes++
    }

    override fun scheduleRefresh() {}
    override fun applyMachineEvent(event: SyncEvent.MachineUpdated) {}
}

private fun summary(
    id: String,
    updatedAt: Long = 0,
    active: Boolean = false,
    machineId: String? = null,
    name: String? = null,
    summaryText: String? = null,
    path: String = "/repo/app",
    worktree: WorktreeMetadata? = null,
): SessionSummary = SessionSummary(
    id = id,
    active = active,
    thinking = false,
    activeAt = 0,
    updatedAt = updatedAt,
    metadata = SessionSummaryMetadata(
        name = name,
        path = path,
        machineId = machineId,
        summary = summaryText?.let { SummaryText(it) },
        flavor = "claude",
        worktree = worktree,
    ),
    metadataVersion = 0,
    agentStateVersion = 0,
    todosUpdatedAt = 0,
    todoProgress = null,
    pendingRequestsCount = 0,
    pendingRequestKinds = emptyList(),
    pendingRequests = emptyList(),
    backgroundTaskCount = 0,
    futureScheduledMessageCount = 0,
    nextScheduledAt = null,
    model = null,
    modelReasoningEffort = null,
    effort = null,
)

private fun machine(id: String, host: String, displayName: String? = null): Machine = Machine(
    id = id,
    namespace = "default",
    seq = 1,
    createdAt = 1,
    updatedAt = 1,
    active = true,
    activeAt = 1,
    metadata = MachineMetadata(
        host = host,
        platform = "linux",
        happyCliVersion = "1.0.0",
        displayName = displayName,
    ),
    metadataVersion = 1,
    runnerState = null,
    runnerStateVersion = 1,
)

private fun TestScope.buildViewModel(
    sessions: FakeSessionStore = FakeSessionStore(),
    machines: FakeMachineStore = FakeMachineStore(),
): Triple<SessionListViewModel, FakeSessionStore, FakeMachineStore> {
    val viewModel = SessionListViewModel(
        sessionStore = sessions,
        machineStore = machines,
        lastSeenStore = LastSeenStore(backgroundScope),
        scope = backgroundScope,
        hubKey = "hub-test",
    )
    return Triple(viewModel, sessions, machines)
}

// ------------------------------------------------------------------ tests --

class SessionListViewModelTest {
    @Test
    fun `directory grouping preserves pin and pending-request priority ahead of recency`() = runTest {
        val (vm, store, _) = buildViewModel()
        store.set(
            summary("recent", active = true, updatedAt = 1000),
            summary("waiting", active = true, updatedAt = 10).copy(pendingRequestsCount = 1),
            summary("inactive", updatedAt = 2000),
            summary("pinned", updatedAt = 1).copy(pinned = true),
        )
        val state = vm.uiState.first { it.rows.size == 4 }
        assertEquals(listOf("pinned", "waiting", "recent", "inactive"), state.groups.single().rows.map { it.id })
    }

    @Test
    fun `search matches across fields and combines with machine and archive filters`() = runTest {
        val (vm, store, machines) = buildViewModel()
        machines.backing.value = listOf(machine("m1", "Workstation"), machine("m2", "Laptop"))
        val a = summary("a", name = "修复 Codex", path = "/home/Project", machineId = "m1")
            .copy(lastMessagePreview = "Latest answer")
        val b = summary("b", name = "Codex", path = "/home/Project", machineId = "m2")
        val old = a.copy(id = "old", metadata = a.metadata?.copy(lifecycleState = "archived"))
        store.set(a, b, old)
        vm.setSearchQuery("  CODEX project   workstation answer ")
        assertEquals(listOf("a"), vm.uiState.first { it.listFilter.query.contains("answer") }.rows.map { it.id })
        vm.setShowArchived(true)
        assertEquals(listOf("old"), vm.uiState.first { it.showArchived }.rows.map { it.id })
        vm.setShowArchived(false); vm.setSearchQuery("codex project")
        vm.setMachineFilter("m2")
        assertEquals(listOf("b"), vm.uiState.first { it.activeMachineFilter == "m2" }.rows.map { it.id })
        vm.setSearchQuery("no match")
        assertTrue(vm.uiState.first { it.listFilter.query == "no match" }.rows.isEmpty())
        vm.clearListFilters()
        assertEquals(listOf("b"), vm.uiState.first { !it.listFilter.active }.rows.map { it.id })
    }

    @Test
    fun `date filtering combines with search and follows live last update`() = runTest {
        val (vm, store, _) = buildViewModel()
        val day = java.time.LocalDate.of(2026, 10, 7)
        val midnight = day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val next = day.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        store.set(summary("before", updatedAt = midnight - 1), summary("inside", updatedAt = midnight), summary("end", updatedAt = next - 1), summary("after", updatedAt = next))
        vm.setDateRange(SessionDateRange(day, day))
        assertEquals(setOf("inside", "end"), vm.uiState.first { it.listFilter.dates != null }.rows.map { it.id }.toSet())
        vm.setSearchQuery("inside")
        assertEquals(listOf("inside"), vm.uiState.first { it.listFilter.query == "inside" }.rows.map { it.id })
        store.set(summary("inside", updatedAt = next))
        vm.uiState.first { it.rows.isEmpty() }
        vm.setDateRange(null)
        assertEquals(listOf("inside"), vm.uiState.first { it.listFilter.dates == null }.rows.map { it.id })
    }

    @Test
    fun `directories separate machines and merge worktrees without duplicating global pins`() = runTest {
        val (vm, store, machines) = buildViewModel()
        machines.backing.value = listOf(machine("m1", "Desktop"), machine("m2", "Laptop"))
        val a = summary("a", path = "/repo/app", machineId = "m1", updatedAt = 10)
        val branch = summary("branch", path = "/worktrees/feature", machineId = "m1",
            worktree = WorktreeMetadata(basePath = "/repo/app", name = "feature", branch = "feature", worktreePath = "/worktrees/feature"))
        val otherMachine = a.copy(id = "other", metadata = a.metadata?.copy(machineId = "m2"))
        val pin = a.copy(id = "pin", pinned = true)
        val global = a.copy(id = "global", globalPinned = true)
        store.set(a, branch, otherMachine, pin, global)
        val state = vm.uiState.first { it.rows.size == 5 }
        assertEquals(2, state.groups.size)
        val desktop = state.groups.first { it.machine?.id == "m1" }
        assertEquals("repo/app", desktop.label)
        assertEquals("pin", desktop.rows.first().id)
        assertEquals(setOf("a", "branch", "pin"), desktop.rows.map { it.id }.toSet())
        assertEquals(1, state.rows.count { it.summary.globalPinned == true })
        assertFalse(state.groups.any { group -> group.rows.any { it.id == "global" } })
        vm.setSearchQuery("feature")
        val found = vm.uiState.first { it.listFilter.query == "feature" }
        assertEquals(listOf("branch"), found.groups.single().rows.map { it.id })
    }

    @Test
    fun `groups with identical display names remain distinct and regroup on archive`() = runTest {
        val (vm, store, _) = buildViewModel()
        val a = summary("a", path = "/first/repo/app")
        val b = summary("b", path = "/second/repo/app")
        store.set(a, b)
        var state = vm.uiState.first { it.rows.size == 2 }
        assertEquals(2, state.groups.map { it.key }.distinct().size)
        assertEquals(1, state.groups.map { it.label }.distinct().size)
        store.set(a.copy(metadata = a.metadata?.copy(lifecycleState = "archived")), b)
        state = vm.uiState.first { it.archivedCount == 1 }
        assertEquals(listOf("b"), state.groups.single().rows.map { it.id })
        vm.setShowArchived(true)
        assertEquals(listOf("a"), vm.uiState.first { it.showArchived }.groups.single().rows.map { it.id })
    }

    @Test
    fun `main hides archived sessions on initial load and repeated refresh while preserving inactive sessions`() = runTest {
        val (vm, store, _) = buildViewModel()
        val archived = summary("archived", name = "Saved").let {
            it.copy(pinned = true, metadata = it.metadata?.copy(lifecycleState = "archived"))
        }
        val inactive = summary("inactive", active = false)
        store.set(archived, inactive)
        var state = vm.uiState.first { it.archivedCount == 1 }
        assertFalse(state.showArchived)
        assertEquals(listOf("inactive"), state.rows.map { it.id })
        store.set(archived.copy(updatedAt = 9999), inactive)
        vm.refresh(); runCurrent()
        state = vm.uiState.value
        assertEquals(listOf("inactive"), state.rows.map { it.id })
        vm.setShowArchived(true)
        state = vm.uiState.first { it.showArchived }
        assertEquals(listOf("archived"), state.rows.map { it.id })
        vm.onSessionOpened("archived")
        assertFalse(store.calls.value.any { it.startsWith("reopen:") })
        vm.setShowArchived(false)
        assertEquals(listOf("inactive"), vm.uiState.first { !it.showArchived }.rows.map { it.id })
        store.set(archived.copy(active = true, metadataVersion = 2, metadata = archived.metadata?.copy(lifecycleState = "running")), inactive)
        assertEquals(setOf("archived", "inactive"), vm.uiState.first { it.archivedCount == 0 }.rows.map { it.id }.toSet())
    }

    @Test
    fun `machine filters use the selected archive scope and do not revive vanished choices`() = runTest {
        val (vm, store, _) = buildViewModel()
        fun archived(id: String, machine: String) = summary(id, machineId = machine).let { it.copy(metadata = it.metadata?.copy(lifecycleState = "archived")) }
        store.set(summary("current", machineId = "m1"), archived("old1", "m2"), archived("old2", "m3"))
        assertEquals(listOf("m1"), vm.uiState.first { it.archivedCount == 2 }.machineFilters.map { it.id })
        vm.setShowArchived(true)
        vm.uiState.first { it.showArchived && it.machineFilters.size == 2 }
        vm.setMachineFilter("m2")
        assertEquals(listOf("old1"), vm.uiState.first { it.activeMachineFilter == "m2" }.rows.map { it.id })
        vm.setShowArchived(false)
        assertNull(vm.uiState.first { !it.showArchived }.activeMachineFilter)
        runCurrent()
        vm.setShowArchived(true)
        assertNull(vm.uiState.first { it.showArchived }.activeMachineFilter)
    }

    @Test
    fun `explicit reopen returns to the main scope but failed reopen stays in archive`() = runTest {
        val (vm, store, _) = buildViewModel()
        vm.setShowArchived(true)
        vm.uiState.first { it.showArchived }
        store.reopenFailure = IllegalStateException("offline")
        vm.reopenSession("old"); runCurrent()
        assertTrue(vm.uiState.value.showArchived)
        store.reopenFailure = null
        vm.reopenSession("old")
        vm.uiState.first { !it.showArchived }
        assertTrue(store.calls.value.contains("reopen:old"))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `vanished filter stays cleared when sessions return and is isolated per home`() = runTest {
        val (viewModel, sessions, machines) = buildViewModel()
        sessions.set(summary("one", machineId = "m1"), summary("two", machineId = "m2"))
        viewModel.uiState.first { it.rows.size == 2 }
        viewModel.setMachineFilter("m1")
        viewModel.uiState.first { it.activeMachineFilter == "m1" }
        val (otherHome) = buildViewModel(sessions, machines)
        assertNull(otherHome.uiState.first { it.rows.size == 2 }.activeMachineFilter)
        sessions.set(summary("two", machineId = "m2"))
        viewModel.uiState.first { it.rows.size == 1 && it.activeMachineFilter == null }
        runCurrent()
        sessions.set(summary("one", machineId = "m1"), summary("two", machineId = "m2"))
        assertNull(viewModel.uiState.first { it.rows.size == 2 }.activeMachineFilter)
    }

    @Test
    fun `machine choices stay name ordered across count changes and include historical unknowns`() = runTest {
        val (viewModel, sessions, machines) = buildViewModel()
        machines.backing.value = listOf(
            machine("z", "z", "Zulu"), machine("a", "a", " Devbox "), machine("b", "b", "devbox"),
        )
        sessions.set(
            summary("z1", machineId = "z"), summary("a1", machineId = "a"), summary("b1", machineId = "b"),
            summary("old", machineId = "retired", active = false), summary("unknown"),
        )
        val first = viewModel.uiState.first { it.rows.size == 5 }.machineFilters
        assertEquals(listOf("a", "b", "z", "retired", UNKNOWN_MACHINE_ID), first.map { it.id })
        assertEquals(listOf("Devbox · a", "devbox · b"), first.take(2).map { it.label })
        assertTrue(first.first { it.id == "retired" }.unnamed)
        sessions.set(*sessions.backing.value.toTypedArray(), summary("z2", machineId = "z"), summary("z3", machineId = "z"))
        val updated = viewModel.uiState.first { it.rows.size == 7 }.machineFilters
        assertEquals(first.map { it.id }, updated.map { it.id })
        assertEquals(3, updated.first { it.id == "z" }.sessionCount)
    }

    @Test
    fun `uiState maps rows with titles meta and unread`() = runTest {
        val (viewModel, sessions, machines) = buildViewModel()
        machines.backing.value = listOf(machine("m1", host = "devbox.local", displayName = "Devbox"))
        sessions.set(
            summary("s1", updatedAt = 900, name = "Named session", machineId = "m1", summaryText = "working on it").copy(lastMessagePreview = "Newest message"),
            summary("s2", updatedAt = 500, summaryText = "summary title", machineId = "offline-machine-id"),
            summary("s3", updatedAt = 100, path = "/repo/tail-name"),
        )

        val state = viewModel.uiState.first { it.rows.size == 3 }
        val byId = state.rows.associateBy { it.id }

        // Title cascade: name → summary text → path tail.
        assertEquals("Named session", byId.getValue("s1").title)
        assertEquals("Newest message", byId.getValue("s1").subtitle)
        assertEquals("summary title", byId.getValue("s2").title)
        // Summary text already used as the title → no subtitle (the path no
        // longer backfills it; the meta line carries the project instead).
        assertNull(byId.getValue("s2").subtitle)
        assertEquals("tail-name", byId.getValue("s3").title)

        // Meta = `project · machine`: last two path segments, then the
        // machine label (displayName wins; unlisted machines shorten the id;
        // shown because several machines are known and no filter is active).
        assertEquals("repo/app", byId.getValue("s1").meta)
        assertEquals("Devbox", byId.getValue("s1").machine?.label)
        assertEquals("repo/app", byId.getValue("s2").meta)
        assertTrue(byId.getValue("s2").machine!!.unnamed)
        assertEquals("repo/tail-name", byId.getValue("s3").meta)

        // No baseline seeded (no successful refresh yet) → activity is unread.
        assertTrue(byId.getValue("s1").unread)
    }

    @Test
    fun `machine filter chips need two machines and filter the rows`() = runTest {
        val (viewModel, sessions, _) = buildViewModel()
        sessions.set(
            summary("s1", updatedAt = 900, machineId = "m1"),
            summary("s2", updatedAt = 800, machineId = "m2"),
            summary("s3", updatedAt = 700, machineId = "m1"),
        )

        var state = viewModel.uiState.first { it.rows.size == 3 }
        assertTrue(state.hasMachineFilters)
        assertEquals(listOf("m1", "m2"), state.machineFilters.map { it.id })
        assertEquals(listOf(2, 1), state.machineFilters.map { it.sessionCount })

        // Unfiltered multi-machine view carries the machine in the meta line…
        assertEquals("m1", state.rows.first { it.id == "s1" }.machine?.id)

        viewModel.setMachineFilter("m1")
        state = viewModel.uiState.first { it.activeMachineFilter == "m1" }
        assertEquals(listOf("s1", "s3"), state.rows.map { it.id })
        // …and an active filter drops it: every visible row shares the machine.
        assertEquals(listOf("repo/app", "repo/app"), state.rows.map { it.meta })

        // A filter whose machine lost its sessions falls back to All.
        sessions.set(summary("s2", updatedAt = 800, machineId = "m2"))
        state = viewModel.uiState.first { it.rows.size == 1 }
        assertNull(state.activeMachineFilter)
        assertEquals(listOf("s2"), state.rows.map { it.id })
    }

    @Test
    fun `single machine hides the filter bar and never filters`() = runTest {
        val (viewModel, sessions, _) = buildViewModel()
        sessions.set(summary("s1", machineId = "m1"), summary("s2", machineId = "m1"))
        viewModel.setMachineFilter("m9")
        val state = viewModel.uiState.first { it.rows.size == 2 }
        assertFalse(state.hasMachineFilters)
        assertNull(state.activeMachineFilter)
        // A single machine is not worth naming on every row.
        assertEquals(listOf("repo/app", "repo/app"), state.rows.map { it.meta })
    }

    @Test
    fun `meta line derives the project from the worktree base path`() = runTest {
        val (viewModel, sessions, _) = buildViewModel()
        sessions.set(
            summary(
                "s1",
                updatedAt = 900,
                path = "/data/worktrees/231",
                worktree = WorktreeMetadata(basePath = "/data/github/hapi", branch = "fix/insets", name = "231"),
            ),
            summary(
                "s2",
                updatedAt = 800,
                path = "/data/worktrees/nameless",
                worktree = WorktreeMetadata(basePath = "/data/github/hapi", branch = "main", name = ""),
            ),
            summary("s3", updatedAt = 700, path = "/srv", name = "Named", summaryText = "Ship it"),
        )

        val state = viewModel.uiState.first { it.rows.size == 3 }
        val byId = state.rows.associateBy { it.id }

        // Worktree rows: project from the base path, then the worktree name
        // (branch when the name is blank). One machine → no machine suffix.
        assertEquals("github/hapi · 231", byId.getValue("s1").meta)
        assertEquals("github/hapi · main", byId.getValue("s2").meta)
        // Single-segment paths render as-is.
        assertEquals("srv", byId.getValue("s3").meta)

        // Without a message preview, neither the old summary nor the path is a subtitle.
        assertNull(byId.getValue("s1").subtitle)
        assertNull(byId.getValue("s3").subtitle)
    }

    @Test
    fun `refresh flips the flags and failure marks offline`() = runTest {
        val (viewModel, sessions, machines) = buildViewModel()
        viewModel.refresh()
        var state = viewModel.uiState.first { it.hasLoaded }
        assertFalse(state.isOffline)
        assertEquals(1, machines.refreshes)
        assertEquals(listOf("refresh"), sessions.calls.value)

        sessions.failRefresh = true
        viewModel.refresh()
        state = viewModel.uiState.first { it.isOffline }
        assertFalse(state.isRefreshing)
    }

    @Test
    fun `start runs the entry refresh (the global pipe belongs to HubGraph)`() = runTest {
        // SSE handshake/resync behavior for the global pipe is covered by
        // GlobalSsePipeTest — this VM only owns the explicit entry refresh.
        val (viewModel, sessions, _) = buildViewModel()
        viewModel.start()
        sessions.calls.first { "refresh" in it }
        assertFalse("fullResync" in sessions.calls.value)
        viewModel.stop()
    }

    @Test
    fun `pin failure surfaces on the errors flow`() = runTest {
        val (viewModel, sessions, _) = buildViewModel()
        sessions.set(summary("s1"))
        viewModel.setPinMode("s1", PinMode.Project)
        sessions.calls.first { "pin:s1:project" in it }

        var seen: SessionListError? = null
        val collector = launch { seen = viewModel.errors.first() }
        viewModel.setPinMode("s1", PinMode.Global) // fake throws for global
        sessions.calls.first { "pin:s1:global" in it }
        collector.join()
        assertTrue(seen is SessionListError.PinFailed)
    }

    @Test
    fun `onSessionOpened stamps the last-seen watermark and clears unread`() = runTest {
        val (viewModel, sessions, _) = buildViewModel()
        sessions.set(summary("s1", updatedAt = 900))
        assertTrue(viewModel.uiState.first { it.rows.size == 1 }.rows.single().unread)

        viewModel.onSessionOpened("s1")
        assertFalse(viewModel.uiState.first { !it.rows.single().unread }.rows.single().unread)
    }

    // -------------------------------------------- rename / delete / reopen --

    @Test
    fun `rename trims and routes to the store, surfacing failures`() = runTest {
        val (viewModel, sessions, _) = buildViewModel()
        sessions.set(summary("s1"))

        viewModel.renameSession("s1", "  Fresh title ")
        sessions.calls.first { "rename:s1:Fresh title" in it }

        viewModel.renameSession("s1", "   ") // blank: never sent
        assertEquals(1, sessions.calls.value.count { it.startsWith("rename:") })

        var seen: SessionListError? = null
        val collector = launch { seen = viewModel.errors.first() }
        sessions.renameFailure = RuntimeException("boom")
        viewModel.renameSession("s1", "Another")
        collector.join()
        assertTrue(seen is SessionListError.RenameFailed)
    }

    @Test
    fun `delete failure with 409 gets the active-session wording`() = runTest {
        val (viewModel, sessions, _) = buildViewModel()
        sessions.set(summary("s1", active = true))

        var seen: SessionListError? = null
        val collector = launch { seen = viewModel.errors.first() }
        sessions.deleteFailure = app.hapi.data.api.ApiError(409, code = "session_active")
        viewModel.deleteSession("s1")
        collector.join()

        val error = seen
        assertTrue(error is SessionListError.DeleteFailed)
        // The UI resolves the localized "archive it first" wording from this flag.
        assertTrue(error.stillActive)
    }

    @Test
    fun `reopen emits the returned (possibly superseding) id`() = runTest {
        val (viewModel, sessions, _) = buildViewModel()
        sessions.set(summary("s1"))
        sessions.reopenResult =
            app.hapi.protocol.wire.ReopenSessionResponse(sessionId = "s2", resumed = false)

        var target: String? = null
        val collector = launch { target = viewModel.reopened.first() }
        viewModel.reopenSession("s1")
        collector.join()

        assertEquals("s2", target)
        assertTrue("reopen:s1" in sessions.calls.value)
    }

    @Test
    fun `reopen 422 formats the missing-metadata error`() = runTest {
        val (viewModel, sessions, _) = buildViewModel()
        sessions.set(summary("s1"))
        sessions.reopenFailure = app.hapi.data.api.ApiError(
            422,
            code = "reopen_missing_metadata",
            body = """{"error":"Cannot reopen","missing":["cursorSessionId"]}""",
        )

        var seen: SessionListError? = null
        val collector = launch { seen = viewModel.errors.first() }
        viewModel.reopenSession("s1")
        collector.join()

        val error = seen
        assertTrue(error is SessionListError.ReopenFailed)
        assertEquals("Cannot reopen (missing: cursorSessionId)", error.message)
    }
}
