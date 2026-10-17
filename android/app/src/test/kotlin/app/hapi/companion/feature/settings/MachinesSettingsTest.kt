package app.hapi.companion.feature.settings

import app.hapi.companion.feature.chat.PickerMachines
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlin.test.*

class MachinesSettingsTest {
    @Test fun `rename trims clears and enforces server length before request`() = runTest {
        val calls = mutableListOf<Pair<String, String>>()
        val store = PickerMachines()
        val vm = MachinesSettingsViewModel(store, { id, name -> calls += id to name }, backgroundScope)
        runCurrent()
        vm.renameMachine("remote", "  quantlab2-new  "); runCurrent()
        assertEquals("remote" to "quantlab2-new", calls.single())
        assertEquals(2, store.refreshes)
        vm.renameMachine("remote", " "); runCurrent()
        assertEquals("", calls.last().second)
        vm.renameMachine("remote", "a".repeat(65)); runCurrent()
        assertEquals(2, calls.size)
    }
    @Test fun `single submit failure keeps error and retry succeeds`() = runTest {
        val pending = CompletableDeferred<Unit>()
        var calls = 0; var fail = true
        val vm = MachinesSettingsViewModel(PickerMachines(), { _, _ -> calls++; pending.await(); if (fail) error("offline") }, backgroundScope)
        runCurrent(); vm.renameMachine("remote", "name"); vm.renameMachine("remote", "second"); runCurrent()
        assertEquals(1, calls); pending.complete(Unit); runCurrent()
        assertEquals("offline", vm.error.value); assertEquals(0L, vm.renamed.value)
        fail = false; vm.renameMachine("remote", "retry"); runCurrent()
        assertEquals(1L, vm.renamed.value); assertNull(vm.error.value)
    }
}
