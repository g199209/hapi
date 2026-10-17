package app.hapi.companion.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import app.hapi.companion.feature.chat.ComposerUiState
import app.hapi.companion.feature.chat.composer.*
import app.hapi.companion.feature.settings.voice.*
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.protocol.wire.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CompanionFeaturesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun text(id: Int) = compose.activity.getString(id)

    @Test fun dictationSavesOnlySelectedProviderAndDoesNotReadBackKey() {
        var saved: JsonObject? = null
        val status = TranscriptionCredentials(openai = CredentialStatus(editable = true))
        lateinit var scope: CoroutineScope
        compose.setContent {
            scope = rememberCoroutineScope()
            val vm = remember { VoiceSettingsViewModel(object : VoiceSettingsGateway {
                override suspend fun load() = status
                override suspend fun update(body: JsonObject): TranscriptionCredentials {
                    saved = body
                    return status.copy(openai = CredentialStatus(true, "settings", "••••1234", true))
                }
            }, scope) }
            HapiTheme { VoiceSettingsScreen(vm) {} }
        }
        compose.onNode(hasSetTextAction() and hasText("API key")).performScrollTo().performTextInput("test-only-1234")
        compose.onNodeWithText(text(app.hapi.companion.R.string.voice_save)).performScrollTo().performClick()
        compose.waitUntil { saved != null }
        compose.runOnIdle {
            assertEquals(setOf("openai"), saved!!.keys)
            assertEquals("test-only-1234", saved!!["openai"]!!.jsonPrimitive.content)
        }
        compose.onNodeWithText("test-only-1234").assertDoesNotExist()
    }

    @Test fun machineRenameUsesSelectedIdAndUnicodeDisplayName() {
        var result: Pair<String, String>? = null
        compose.setContent {
            val scope = rememberCoroutineScope()
            val store = remember { object : app.hapi.data.store.MachineListStore {
                override val machines = kotlinx.coroutines.flow.MutableStateFlow(listOf(Machine(
                    id = "remote", namespace = "default", seq = 1, createdAt = 1, updatedAt = 1, active = true, activeAt = 1,
                    metadataVersion = 1, runnerStateVersion = 1, metadata = MachineMetadata("quantlab2", "linux", "test"),
                )))
                override suspend fun refresh() {}
                override fun scheduleRefresh() {}
                override fun applyMachineEvent(event: SyncEvent.MachineUpdated) {}
            } }
            val vm = remember { MachinesSettingsViewModel(store, { id, name -> result = id to name }, scope) }
            HapiTheme { MachinesSettingsScreen(vm) {} }
        }
        compose.onNodeWithText("quantlab2").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("  量化节点  ")
        compose.onNodeWithText(text(app.hapi.companion.R.string.voice_save)).performClick()
        compose.waitUntil { result != null }
        compose.runOnIdle { assertEquals("remote" to "量化节点", result) }
    }

    @Test fun envManagedCredentialIsReadOnly() {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val vm = remember { VoiceSettingsViewModel(object : VoiceSettingsGateway {
                override suspend fun load() = TranscriptionCredentials(openai = CredentialStatus(true, "env", "••••xxxx", false))
                override suspend fun update(body: JsonObject): TranscriptionCredentials = error("Environment key must not be changed")
            }, scope) }
            HapiTheme { VoiceSettingsScreen(vm) {} }
        }
        compose.onNodeWithText("API key").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(app.hapi.companion.R.string.voice_save)).performScrollTo().assertIsNotEnabled()
    }

    @Test fun pickerReplacesTokenAtCaretAndPreservesSurroundingChineseDraft() {
        val editor = TextFieldState("前文 @fi 后文", TextRange(6))
        val item = ComposerPick("file:src/a.kt", "@a.kt", "src", "@\"src/a.kt\"")
        compose.setContent {
            val ui = PickerUi(pickerToken(editor.text.toString(), editor.selection.start), listOf(item))
            HapiTheme { ChatComposer(
                ComposerUiState(text = editor.text.toString(), isSending = false, canSteer = false),
                editor, {}, {}, {}, {}, pickerState = ui, onPick = { applyComposerPick(editor, ui, it) },
            ) }
        }
        compose.onNodeWithText("@a.kt").performClick()
        compose.runOnIdle {
            assertEquals("前文 @\"src/a.kt\" 后文", editor.text.toString())
            assertEquals(TextRange(15), editor.selection)
        }
    }

    @Test fun stalePickerCannotReplaceAChangedDraftOrSelection() {
        val editor = TextFieldState("\$skill", TextRange(6))
        val item = ComposerPick("skill:skill", "\$skill", null, "\$skill")
        val state = PickerUi(pickerToken(editor.text.toString(), 6), listOf(item))
        compose.runOnUiThread { editor.setTextAndPlaceCursorAtEnd("中文新草稿") }
        compose.runOnIdle {
            assertFalse(applyComposerPick(editor, state, item))
            assertEquals("中文新草稿", editor.text.toString())
            editor.setTextAndPlaceCursorAtEnd("\$skill")
            editor.edit { selection = TextRange(0, 6) }
            assertFalse(applyComposerPick(editor, state, item))
        }
    }
}
