package app.hapi.companion.feature.newsession

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.protocol.wire.CodexLocalSessionSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CodexSessionImportTest {
    @get:Rule val compose = createComposeRule()

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
}
