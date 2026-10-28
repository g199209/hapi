package app.hapi.companion.feature.newsession

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.protocol.wire.CodexLocalSessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CodexSessionImportTest {
    @get:Rule val compose = createComposeRule()

    @Test fun baseDirectoryPickerIncludesRelatedWorktreesAndKeepsTheirNativeSelection() {
        var state by mutableStateOf(CodexSessionImportState(
            open = true, machineId = "local", machineLabel = "GMF-PC",
            sessions = listOf(
                CodexLocalSessionSummary("main", "Primary", 3, "/home/repo", basePath = "/home/repo"),
                CodexLocalSessionSummary("tree", "Feature worktree", 2, "/custom/feature", basePath = "/home/repo"),
                CodexLocalSessionSummary("other", "Other clone", 1, "/elsewhere/repo", basePath = "/elsewhere/repo"),
            ),
        ))
        compose.setContent { HapiTheme {
            CodexSessionImportSheet(state, {}, {}, {},
                { state = state.copy(directory = it, selectedId = null) },
                { state = state.copy(selectedId = it) }, {})
        } }
        compose.onNodeWithTag("codex-import-directory").performClick()
        compose.onNodeWithTag("codex-import-path-/custom/feature").assertDoesNotExist()
        compose.onNodeWithTag("codex-import-path-/home/repo").performClick()
        compose.onNodeWithTag("codex-import-main").assertIsDisplayed()
        compose.onNodeWithTag("codex-import-tree").assertIsDisplayed().performClick().assertIsSelected()
        compose.onNodeWithTag("codex-import-other").assertDoesNotExist()
        compose.onNodeWithTag("codex-import-confirm").assertIsEnabled()
        compose.runOnIdle { assertEquals("tree", state.selectedId) }
    }

    @Test fun searchAndWholeRowSelectionEnableImportAndKeepErrorVisible() {
        val state = mutableStateOf(CodexSessionImportState(
            open = true, machineId = "remote", machineLabel = "quantlab2",
            sessions = listOf(
                CodexLocalSessionSummary("a", "Project A", 1791980000000, "/home/a"),
                CodexLocalSessionSummary("b", "Project B", 1791980000001, "/home/b"),
            ),
        ))
        var confirmed = 0
        compose.setContent {
            HapiTheme {
                CodexSessionImportSheet(
                    state.value, onDismiss = {}, onRefresh = {},
                    onQueryChange = { state.value = state.value.copy(query = it, selectedId = null) },
                    onDirectoryChange = { state.value = state.value.copy(directory = it, selectedId = null) },
                    onSelect = { state.value = state.value.copy(selectedId = it) },
                    onConfirm = { confirmed++; state.value = state.value.copy(error = "Connection failed") },
                )
            }
        }
        compose.onNodeWithText("quantlab2").assertIsDisplayed()
        compose.onNodeWithTag("codex-import-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("codex-import-search").performTextInput("Project B")
        compose.onNodeWithTag("codex-import-a").assertDoesNotExist()
        compose.onNodeWithTag("codex-import-b").performClick().assertIsSelected()
        compose.onNodeWithTag("codex-import-confirm").assertIsEnabled().performClick()
        compose.onNodeWithText("Connection failed").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun directoryFilterAndPendingImportDisableSelectionAndDismiss() {
        val state = mutableStateOf(CodexSessionImportState(
            open = true, machineId = "local", machineLabel = "GMF-PC",
            sessions = listOf(CodexLocalSessionSummary("a", "Project A", 1791980000000, "/home/a")),
        ))
        compose.setContent {
            HapiTheme {
                CodexSessionImportSheet(
                    state.value, onDismiss = {}, onRefresh = {}, onQueryChange = {},
                    onDirectoryChange = { state.value = state.value.copy(directory = it) },
                    onSelect = { state.value = state.value.copy(selectedId = it) },
                    onConfirm = { state.value = state.value.copy(importing = true) },
                )
            }
        }
        compose.onNodeWithTag("codex-import-directory").performClick()
        compose.onNodeWithTag("codex-import-path-/home/a").performClick()
        compose.onNodeWithTag("codex-import-a").performClick()
        compose.onNodeWithTag("codex-import-confirm").performClick()
        compose.onNodeWithTag("codex-import-search").assertIsNotEnabled()
        compose.onNodeWithTag("codex-import-a").assertIsNotEnabled()
        compose.onNodeWithTag("codex-import-confirm").assertIsNotEnabled()
    }

    @Test fun listScrollsBothWaysWithoutMovingOrDismissingSheetAtItsTop() {
        var state by mutableStateOf(sampleState(40).copy(selectedId = "0"))
        var dismissed = 0
        compose.setContent {
            HapiTheme {
                if (state.open) CodexSessionImportSheet(state, { dismissed++; state = state.copy(open = false) }, {}, {}, {}, {}, {})
            }
        }
        val list = compose.onNodeWithTag("codex-import-list")
        val top = list.fetchSemanticsNode().boundsInRoot.top
        for (duration in listOf(180L, 600L, 180L)) {
            list.performTouchInput { swipeDown(durationMillis = duration) }
            list.assertIsDisplayed()
            assertEquals(top, list.fetchSemanticsNode().boundsInRoot.top, 1f)
        }
        list.performTouchInput { swipeUp(durationMillis = 300) }
        compose.waitForIdle()
        assertTrue(list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0)
        repeat(4) { list.performTouchInput { swipeDown(durationMillis = 180) } }
        compose.waitForIdle()
        assertEquals(0f, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
        assertEquals(top, list.fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithTag("codex-import-0").assertIsSelected()
        compose.runOnIdle { assertEquals(0, dismissed) }
    }

    @Test fun shortEmptyAndLoadingListsKeepTheSheetOpenOnDownwardFling() {
        var state by mutableStateOf(sampleState(1))
        var dismissed = 0
        compose.setContent {
            HapiTheme {
                if (state.open) CodexSessionImportSheet(state, { dismissed++; state = state.copy(open = false) }, {}, {}, {}, {}, {})
            }
        }
        for (mode in 0..2) {
            compose.runOnIdle { state = state.copy(sessions = if (mode == 0) sampleState(1).sessions else emptyList(), loading = mode == 2) }
            val list = compose.onNodeWithTag("codex-import-list")
            val top = list.fetchSemanticsNode().boundsInRoot.top
            list.performTouchInput { swipeDown(durationMillis = 180) }
            list.assertIsDisplayed()
            assertEquals(top, list.fetchSemanticsNode().boundsInRoot.top, 1f)
        }
        compose.runOnIdle { assertEquals(0, dismissed) }
    }

    @Test fun onlyHandleDragsDismissWhileTitleAndScrimStayPut() {
        var state by mutableStateOf(sampleState(40))
        var dismissed = 0
        compose.setContent {
            HapiTheme {
                if (state.open) CodexSessionImportSheet(state, { dismissed++; state = state.copy(open = false) }, {}, {}, {}, {}, {})
            }
        }
        val distance = compose.onNodeWithTag("codex-import-list").fetchSemanticsNode().boundsInRoot.height
        val panel = compose.onNodeWithTag("codex-import-panel")
        val panelTop = panel.fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithText("GMF-PC").performTouchInput {
            swipe(center, center + Offset(0f, distance), durationMillis = 180)
        }
        compose.onNodeWithTag("codex-import-drag-handle").performTouchInput { click(center) }
        compose.onNodeWithTag("codex-import-scrim").performTouchInput { click(Offset(width / 2f, panelTop - 8f)) }
        assertEquals(panelTop, panel.fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.runOnIdle { assertEquals(0, dismissed) }
        compose.onNodeWithTag("codex-import-drag-handle").performTouchInput {
            swipe(center, center + Offset(0f, distance), durationMillis = 180)
        }
        compose.waitUntil(5_000) { !state.open }
        compose.runOnIdle { assertEquals(1, dismissed); state = state.copy(open = true) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(2, dismissed); state = state.copy(open = true) }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { !state.open }
        compose.runOnIdle { assertEquals(3, dismissed) }
    }

    @Test fun directoryRoundTripsAndInterruptedFlingsLeaveTheListScrollable() {
        var state by mutableStateOf(sampleState(160).copy(
            directory = "/home/a",
            sessions = sampleState(160).sessions.mapIndexed { index, item -> item.copy(cwd = if (index < 70) "/home/a" else "/home/b") },
        ))
        var dismissed = 0
        compose.setContent {
            HapiTheme {
                if (state.open) CodexSessionImportSheet(state, { dismissed++; state = state.copy(open = false) }, {},
                    { state = state.copy(query = it) }, { state = state.copy(directory = it) }, {}, {})
            }
        }
        val panel = compose.onNodeWithTag("codex-import-panel")
        val top = panel.fetchSemanticsNode().boundsInRoot.top
        fun choose(tag: String) {
            compose.onNodeWithTag("codex-import-directory").performClick()
            compose.onNodeWithTag(tag).performClick()
        }
        repeat(3) {
            compose.onNodeWithTag("codex-import-list").performTouchInput { swipeUp(durationMillis = 120) }
            choose("codex-import-path-/home/b")
            choose("codex-import-all-directories")
            val list = compose.onNodeWithTag("codex-import-list")
            assertEquals(0f, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
            repeat(12) {
                // A single injection block avoids waiting for each fling to settle.
                list.performTouchInput {
                    swipeUp(durationMillis = 100)
                    advanceEventTime(16)
                    swipeDown(durationMillis = 120)
                    advanceEventTime(16)
                    swipeDown(durationMillis = 100)
                }
                compose.waitForIdle()
                assertEquals(top, panel.fetchSemanticsNode().boundsInRoot.top, 1f)
                assertEquals("Round trip must reach the first item", 0f,
                    list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
                list.performTouchInput { swipeUp(durationMillis = 250) }
                compose.waitForIdle()
                assertTrue("List must still scroll after returning to its top",
                    list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0)
            }
        }
        compose.onNodeWithTag("codex-import-search").performTextInput("Project 1")
        assertEquals(0f, compose.onNodeWithTag("codex-import-list").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
        compose.runOnIdle { assertEquals(0, dismissed) }
    }

    @Test fun pendingImportBlocksHandleAndBackDismissal() {
        var state by mutableStateOf(sampleState(40))
        var dismissed = 0
        compose.setContent {
            HapiTheme {
                if (state.open) CodexSessionImportSheet(state, { dismissed++; state = state.copy(open = false) }, {}, {}, {}, {}, {})
            }
        }
        compose.runOnIdle { state = state.copy(importing = true) }
        val distance = compose.onNodeWithTag("codex-import-list").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("codex-import-drag-handle").assertIsNotEnabled().performTouchInput {
            swipe(center, center + Offset(0f, distance), durationMillis = 150)
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("codex-import-list").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, dismissed) }
    }

    @Test fun draggingBodyGapAfterDirectoryChangesCannotMoveOrDismissPanel() {
        var state by mutableStateOf(sampleState(60).copy(
            directory = "/home/a",
            sessions = sampleState(60).sessions.mapIndexed { index, item -> item.copy(cwd = if (index < 20) "/home/a" else "/home/b") },
        ))
        var dismissed = 0
        compose.setContent {
            HapiTheme {
                if (state.open) CodexSessionImportSheet(state, { dismissed++; state = state.copy(open = false) }, {}, {},
                    { state = state.copy(directory = it) }, {}, {})
            }
        }
        compose.onNodeWithTag("codex-import-directory").performClick()
        compose.onNodeWithTag("codex-import-path-/home/b").performClick()
        compose.onNodeWithTag("codex-import-directory").performClick()
        compose.onNodeWithText("All directories").performClick()
        val directory = compose.onNodeWithTag("codex-import-directory")
        val list = compose.onNodeWithTag("codex-import-list")
        val fieldBounds = directory.fetchSemanticsNode().boundsInRoot
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        val gap = listBounds.top - fieldBounds.bottom
        assertTrue("Test must start in the spacing outside LazyColumn", gap > 0f)
        val headerTop = compose.onNodeWithText("GMF-PC").fetchSemanticsNode().boundsInRoot.top
        directory.performTouchInput {
            val start = Offset(center.x, height + gap / 2f)
            swipe(start, start + Offset(0f, listBounds.height * 0.8f), durationMillis = 150)
        }
        list.assertIsDisplayed()
        assertEquals(headerTop, compose.onNodeWithText("GMF-PC").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.runOnIdle { assertEquals(0, dismissed) }
    }

    private fun sampleState(count: Int) = CodexSessionImportState(
        open = true, machineId = "local", machineLabel = "GMF-PC",
        sessions = List(count) { CodexLocalSessionSummary("$it", "Project $it", 1791980000000L + it, "/home/project-$it") },
    )
}
