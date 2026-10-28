package app.hapi.companion.feature.newsession

import app.hapi.protocol.wire.CodexImportResponse
import app.hapi.protocol.wire.CodexLocalSessionSummary
import app.hapi.protocol.wire.CodexLocalSessionsResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class FakeCodexSessionImportGateway : CodexSessionImportGateway {
    var listResponse = CodexLocalSessionsResponse(true)
    var listHandler: (suspend (String) -> CodexLocalSessionsResponse)? = null
    var importResponse = CodexImportResponse(true, listOf("hapi-existing"))
    var resumeError: Exception? = null
    var activeId: String? = null
    var activeHandler: (() -> String?)? = null
    val imports = mutableListOf<Pair<String, String>>()
    val resumes = mutableListOf<String>()
    override suspend fun list(machineId: String) = listHandler?.invoke(machineId) ?: listResponse
    override suspend fun activeSessionId(machineId: String, nativeSessionId: String) = activeHandler?.invoke() ?: activeId
    override suspend fun importSession(machineId: String, nativeSessionId: String): CodexImportResponse {
        imports += machineId to nativeSessionId
        return importResponse
    }
    override suspend fun resume(hapiSessionId: String): String {
        resumes += hapiSessionId
        resumeError?.let { throw it }
        return "hapi-resumed"
    }
}

private fun native(id: String, cwd: String? = "/home/project", at: Long = 10) =
    CodexLocalSessionSummary(id, "Title $id", at, cwd, "中文候选内容 $id")

class CodexSessionImportControllerTest {
    @Test fun `primary directory includes linked worktrees and canonicalizes imported recents without changing native thread`() = runTest {
        val gateway = FakeCodexSessionImportGateway().apply {
            listResponse = CodexLocalSessionsResponse(true, listOf(
                native("main", "/base", 1).copy(basePath = "/base"),
                native("worktree", "/custom/location", 2).copy(basePath = "/base"),
                native("clone", "/other/base", 3).copy(basePath = "/other/base"),
            ))
        }
        val completed = mutableListOf<String?>()
        val controller = CodexSessionImportController(gateway, this, "failed", "failed") { _, cwd, _ -> completed += cwd }
        controller.open("m1", "Machine", "/custom/location")
        advanceUntilIdle()
        assertEquals("/base", controller.state.value.directory)
        assertEquals(listOf("/base", "/other/base"), controller.state.value.directories)
        assertEquals(listOf("worktree", "main"), controller.state.value.filteredSessions.map { it.id })
        controller.setQuery("/base")
        assertEquals(2, controller.state.value.filteredSessions.size)
        controller.select("worktree")
        controller.confirm()
        advanceUntilIdle()
        assertEquals(listOf("m1" to "worktree"), gateway.imports)
        assertEquals(listOf<String?>("/base"), completed)
    }

    @Test fun `base directory remains selectable when all its history is in linked worktrees`() = runTest {
        val gateway = FakeCodexSessionImportGateway().apply {
            listResponse = CodexLocalSessionsResponse(true, listOf(native("tree", "/trees/x").copy(basePath = "/base")))
        }
        val controller = CodexSessionImportController(gateway, this, "failed", "failed") { _, _, _ -> }
        controller.open("m1", "Machine", "/base")
        advanceUntilIdle()
        assertEquals("/base", controller.state.value.directory)
        assertEquals(listOf("tree"), controller.state.value.filteredSessions.map { it.id })
        controller.setDirectory(null)
        assertEquals(1, controller.state.value.filteredSessions.size)
    }
    @Test fun `existing active thread opens directly and concurrent import resolves new active record`() = runTest {
        val gateway = FakeCodexSessionImportGateway().apply {
            listResponse = CodexLocalSessionsResponse(true, listOf(native("a")))
            activeId = "active-existing"
        }
        val controller = CodexSessionImportController(gateway, this, "load failed", "import failed") { _, _, _ -> }
        controller.open("m1", "Machine", null)
        advanceUntilIdle()
        controller.select("a")
        controller.confirm()
        advanceUntilIdle()
        assertTrue(gateway.imports.isEmpty())
        assertEquals(listOf("active-existing"), gateway.resumes)
        gateway.activeId = null
        var checks = 0
        gateway.activeHandler = { if (++checks == 1) null else "concurrent-existing" }
        gateway.importResponse = CodexImportResponse(false, error = "Cannot sync active session")
        controller.open("m1", "Machine", null)
        advanceUntilIdle()
        controller.select("a")
        controller.confirm()
        advanceUntilIdle()
        assertEquals(listOf("active-existing", "concurrent-existing"), gateway.resumes)
        assertFalse(controller.state.value.open)
    }

    @Test fun `active lookup is scoped to machine original thread and unarchived Codex`() {
        fun session(id: String, machine: String = "m1", thread: String = "original", active: Boolean = true, flavor: String = "codex", lifecycle: String? = null, time: Long = 1) =
            app.hapi.protocol.wire.SessionSummary(
                id, active, updatedAt = time,
                metadata = app.hapi.protocol.wire.SessionSummaryMetadata(path = "/project", machineId = machine, agentSessionId = thread, flavor = flavor, lifecycleState = lifecycle),
            )
        val sessions = listOf(
            session("other-machine", machine = "m2"), session("fork", thread = "forked"),
            session("archived", lifecycle = "archived"), session("inactive", active = false),
            session("other-agent", flavor = "claude"), session("old"), session("new", time = 2),
        )
        assertEquals("new", findActiveCodexSessionId(sessions, "m1", "original"))
        assertNull(findActiveCodexSessionId(sessions, "m1", "missing"))
    }

    @Test fun `lists machine history and defaults to matching directory with searchable sorted sessions`() = runTest {
        val gateway = FakeCodexSessionImportGateway().apply {
            listResponse = CodexLocalSessionsResponse(true, listOf(native("a"), native("b", "/other", 20), native("a")))
        }
        val controller = CodexSessionImportController(gateway, this, "load failed", "import failed") { _, _, _ -> }
        controller.open("quantlab2", "Remote", "/home/project")
        advanceUntilIdle()
        assertEquals(listOf("b", "a"), controller.state.value.sessions.map { it.id })
        assertEquals(listOf("a"), controller.state.value.filteredSessions.map { it.id })
        controller.select("a")
        assertTrue(controller.state.value.canImport)
        controller.setDirectory(null)
        assertNull(controller.state.value.selectedId)
        controller.setQuery("中文候选")
        assertEquals(2, controller.state.value.filteredSessions.size)
        controller.setQuery("TITLE B")
        assertEquals(listOf("b"), controller.state.value.filteredSessions.map { it.id })
        controller.select("a")
        assertFalse(controller.state.value.canImport)
        controller.setQuery("no match")
        assertTrue(controller.state.value.filteredSessions.isEmpty())
    }

    @Test fun `confirm imports only selected native thread once then navigates to resumed ID`() = runTest {
        val gateway = FakeCodexSessionImportGateway().apply { listResponse = CodexLocalSessionsResponse(true, listOf(native("a"))) }
        val completed = mutableListOf<List<String?>>()
        val controller = CodexSessionImportController(gateway, this, "load failed", "import failed") { machine, cwd, id -> completed += listOf(machine, cwd, id) }
        controller.open("m1", "Machine", null)
        advanceUntilIdle()
        controller.confirm()
        assertTrue(gateway.imports.isEmpty())
        controller.select("a")
        controller.confirm()
        controller.confirm()
        controller.close()
        controller.open("m2", "Other", null)
        assertTrue(controller.state.value.importing)
        advanceUntilIdle()
        assertEquals(listOf("m1" to "a"), gateway.imports)
        assertEquals(listOf("hapi-existing"), gateway.resumes)
        assertEquals(listOf<List<String?>>(listOf("m1", "/home/project", "hapi-resumed")), completed)
        assertFalse(controller.state.value.open)
    }

    @Test fun `failed resume keeps selection and retry reuses imported session`() = runTest {
        val gateway = FakeCodexSessionImportGateway().apply {
            listResponse = CodexLocalSessionsResponse(true, listOf(native("a")))
            resumeError = IllegalStateException("already has an active writer")
        }
        val controller = CodexSessionImportController(gateway, this, "load failed", "import failed") { _, _, _ -> }
        controller.open("m1", "Machine", null)
        advanceUntilIdle()
        controller.select("a")
        controller.confirm()
        advanceUntilIdle()
        assertEquals("already has an active writer", controller.state.value.error)
        assertEquals("a", controller.state.value.selectedId)
        assertTrue(controller.state.value.canImport)
        gateway.resumeError = null
        controller.confirm()
        advanceUntilIdle()
        assertEquals(1, gateway.imports.size)
        assertEquals(2, gateway.resumes.size)
        assertFalse(controller.state.value.open)
    }

    @Test fun `failed RPC envelope does not resume or navigate and missing imported ID is an error`() = runTest {
        val gateway = FakeCodexSessionImportGateway().apply {
            listResponse = CodexLocalSessionsResponse(true, listOf(native("a")))
            importResponse = CodexImportResponse(false, error = "offline")
        }
        var navigated = false
        val controller = CodexSessionImportController(gateway, this, "load failed", "import failed") { _, _, _ -> navigated = true }
        controller.open("m1", "Machine", null)
        advanceUntilIdle()
        controller.select("a")
        controller.confirm()
        advanceUntilIdle()
        assertEquals("offline", controller.state.value.error)
        assertTrue(gateway.resumes.isEmpty())
        gateway.importResponse = CodexImportResponse(true)
        controller.confirm()
        advanceUntilIdle()
        assertEquals("import failed", controller.state.value.error)
        assertFalse(navigated)
    }

    @Test fun `late uncooperative response from previous machine cannot replace new roster`() = runTest {
        val late = CompletableDeferred<CodexLocalSessionsResponse>()
        val gateway = FakeCodexSessionImportGateway().apply {
            listHandler = { machine ->
                if (machine == "m1") withContext(NonCancellable) { late.await() }
                else CodexLocalSessionsResponse(true, listOf(native("b")), machine)
            }
        }
        val controller = CodexSessionImportController(gateway, this, "load failed", "import failed") { _, _, _ -> }
        controller.open("m1", "Machine", null)
        runCurrent()
        controller.open("m2", "Remote", "/not-in-history")
        runCurrent()
        late.complete(CodexLocalSessionsResponse(true, listOf(native("a")), "m1"))
        advanceUntilIdle()
        assertEquals("m2", controller.state.value.machineId)
        assertEquals(listOf("b"), controller.state.value.sessions.map { it.id })
        assertNull(controller.state.value.directory)
        controller.close()
        assertTrue(controller.state.value.sessions.isEmpty())
    }

    @Test fun `load errors can refresh and mismatched machine responses are rejected`() = runTest {
        val gateway = FakeCodexSessionImportGateway().apply { listResponse = CodexLocalSessionsResponse(false, error = "runner offline") }
        val controller = CodexSessionImportController(gateway, this, "load failed", "import failed") { _, _, _ -> }
        controller.open("m1", "Machine", null)
        advanceUntilIdle()
        assertEquals("runner offline", controller.state.value.error)
        gateway.listResponse = CodexLocalSessionsResponse(true, listOf(native("a")), "wrong")
        controller.refresh()
        advanceUntilIdle()
        assertTrue(controller.state.value.sessions.isEmpty())
        gateway.listResponse = CodexLocalSessionsResponse(true, listOf(native("a")), "m1")
        controller.refresh()
        advanceUntilIdle()
        assertNull(controller.state.value.error)
        assertEquals(1, controller.state.value.sessions.size)
    }
}
