package app.hapi.companion.feature.chat

import app.hapi.companion.feature.chat.composer.*
import app.hapi.data.store.*
import app.hapi.data.sse.SseSubscriptionKey
import app.hapi.protocol.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

class PickerSessions : SessionListStore {
    override val sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    override suspend fun refresh() {}
    override fun scheduleRefresh() {}
    override suspend fun fullResync() {}
    override fun applySessionEvent(scope: SseSubscriptionKey, event: SyncEvent) {}
    override suspend fun setPinMode(sessionId: String, mode: String) {}
    override suspend fun archiveSession(sessionId: String) {}
    override suspend fun renameSession(sessionId: String, name: String) {}
    override suspend fun deleteSession(sessionId: String) {}
    override suspend fun reopenSession(sessionId: String) = ReopenSessionResponse(sessionId = sessionId, resumed = true)
}
class PickerMachines : MachineListStore {
    override val machines = MutableStateFlow<List<Machine>>(emptyList())
    var refreshes = 0
    override suspend fun refresh() { refreshes++ }
    override fun scheduleRefresh() { refreshes++ }
    override fun applyMachineEvent(event: SyncEvent.MachineUpdated) {}
}
class ComposerPickersTest {
    private fun summary(id: String, title: String, content: Boolean = true, archived: Boolean = false) = SessionSummary(id, active = false,
        hasConversationContent = content, metadata = SessionSummaryMetadata(name = title, path = "/home/proj", lifecycleState = if (archived) "archived" else "idle"))
    private open class Api : ComposerPickerApi {
        override suspend fun skills() = emptyList<SkillSummary>()
        override suspend fun files(query: String) = emptyList<FileSearchItem>()
    }
    @Test fun `tokens follow caret selection and IME composition without touching text`() {
        val text = "中文 @file.ts 后文"
        assertEquals(PickerToken('@', "fi", 3, 11), pickerToken(text, 6))
        assertNull(pickerToken("email@host", 10))
        assertNull(pickerToken(text, 6, 8))
        assertNull(pickerToken(text, 6, composing = true))
        assertNull(pickerToken("plain", 99))
        assertEquals(PickerToken('$', "", 3, 4), pickerToken("前文 $", 4))
        assertEquals(PickerToken('$', "skill", 2, 8), pickerToken("a\n\$skill", 8))
    }
    @Test fun `reference uses same hub citation grammar and escaped one line title`() {
        val value = sessionReference("  中文 \"title\"\nline  ", "a b")
        assertTrue(value.startsWith("See session \"中文 \\\"title\\\" line\" (/sessions/a%20b) for context."))
        assertTrue(value.endsWith("do not Grep/Glob/Read /sessions/ as a local file."))
        assertTrue(sessionReference("", "s").startsWith("See HAPI session /sessions/s for context."))
    }
    @Test fun `session picker excludes self and empty stubs archived remains searchable`() = runTest {
        val sessions = PickerSessions().apply { this.sessions.value = listOf(summary("self", "current"), summary("stub", "empty", false), summary("old", "Archived", archived = true), summary("peer", "中文 thread")) }
        val vm = ComposerPickers("self", Api(), sessions, PickerMachines(), backgroundScope)
        vm.update(pickerToken("@", 1), "claude"); runCurrent()
        assertEquals(listOf("session:peer"), vm.state.value.items.map { it.key })
        vm.update(pickerToken("@arch", 5), "claude"); runCurrent()
        assertEquals(listOf("session:old"), vm.state.value.items.map { it.key })
        sessions.sessions.value = listOf(summary("new", "Archived newer"))
        runCurrent(); assertEquals(listOf("session:new"), vm.state.value.items.map { it.key })
    }
    @Test fun `file search debounce cancels obsolete text and escapes Codex paths`() = runTest {
        val calls = mutableListOf<String>()
        val vm = ComposerPickers("self", object : Api() {
            override suspend fun files(query: String): List<FileSearchItem> {
                calls += query
                return listOf(FileSearchItem("中文 file", "src", "src/中文 \"file\".kt", "file"))
            }
        }, PickerSessions(), PickerMachines(), backgroundScope)
        vm.update(pickerToken("@o", 2), "codex"); advanceTimeBy(100)
        vm.update(pickerToken("@new", 4), "codex"); advanceTimeBy(181); runCurrent()
        assertEquals(listOf("new"), calls)
        assertEquals("@\"src/中文 \\\"file\\\".kt\"", vm.state.value.items.single().text)
        vm.update(null, "codex"); assertTrue(vm.state.value.items.isEmpty())
    }
    @Test fun `late noncancellable file result cannot restore dismissed picker`() = runTest {
        val pending = CompletableDeferred<List<FileSearchItem>>()
        val vm = ComposerPickers("self", object : Api() {
            override suspend fun files(query: String) = withContext(NonCancellable) { pending.await() }
        }, PickerSessions(), PickerMachines(), backgroundScope, debounceMs = 0)
        vm.update(pickerToken("@x", 2), "codex"); runCurrent()
        vm.update(null, "codex")
        pending.complete(listOf(FileSearchItem("x", "", "x", "file"))); runCurrent()
        assertNull(vm.state.value.token); assertTrue(vm.state.value.items.isEmpty())
    }
    @Test fun `skills RPC failures keep error and retry while selection records recent use`() = runTest {
        var count = 0
        val vm = ComposerPickers("self", object : Api() {
            override suspend fun skills(): List<SkillSummary> {
                if (++count == 1) error("Runner offline")
                return listOf(SkillSummary("alpha"), SkillSummary("beta", "中文 skill"))
            }
        }, PickerSessions(), PickerMachines(), backgroundScope, debounceMs = 0)
        vm.update(pickerToken("$", 1), "codex"); runCurrent(); assertEquals("Runner offline", vm.state.value.error)
        vm.update(pickerToken("\$b", 2), "codex"); runCurrent()
        val item = vm.state.value.items.single(); assertEquals("\$beta", item.text)
        vm.selected(item); vm.update(pickerToken("$", 1), "codex"); runCurrent()
        assertEquals("\$beta", vm.state.value.items.first().label)
    }
    @Test fun `failed file RPC keeps session matches and changing flavor cancels file search`() = runTest {
        val sessions = PickerSessions().apply { this.sessions.value = listOf(summary("peer", "peer")) }
        val vm = ComposerPickers("self", object : Api() { override suspend fun files(query: String): List<FileSearchItem> = error("file RPC failed") }, sessions, PickerMachines(), backgroundScope, debounceMs = 0)
        vm.update(pickerToken("@", 1), "codex"); runCurrent()
        assertEquals("file RPC failed", vm.state.value.error)
        assertEquals(1, vm.state.value.items.size)
        vm.update(pickerToken("@", 1), "claude"); runCurrent()
        assertFalse(vm.state.value.loading); assertNull(vm.state.value.error)
    }
}
