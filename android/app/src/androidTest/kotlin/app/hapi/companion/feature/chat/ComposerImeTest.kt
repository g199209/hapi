package app.hapi.companion.feature.chat

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import kotlinx.coroutines.awaitCancellation
import app.hapi.companion.feature.chat.composer.ChatComposer
import app.hapi.companion.ui.theme.HapiTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class ComposerImeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val editor = TextFieldState("已有内容：")
    private val toolbar = mutableStateOf(ComposerUiState(text = "已有内容：", isSending = false, canSteer = false))

    private var connection: InputConnection? = null
    // Own the real Foundation InputConnection so the system keyboard cannot
    // finish our test's composing region from its unrelated updateSelection.
    private val interceptor = PlatformTextInputInterceptor { request, _ ->
        connection = request.createInputConnection(EditorInfo())
        try { awaitCancellation() } finally { connection = null }
    }

    private fun mount() {
        compose.setContent {
            HapiTheme {
                InterceptPlatformTextInput(interceptor) {
                    ChatComposer(state = toolbar.value, editor = editor,
                        onTextChange = {}, onSend = {}, onSendSteer = {}, onAbort = {})
                }
            }
        }
        compose.onNodeWithTag("chat-composer-input").performClick()
            .performTextInputSelection(TextRange(editor.text.length))
        compose.waitForIdle()
        compose.waitUntil { connection != null }
    }
    private fun ime(edit: InputConnection.() -> Unit) {
        compose.runOnIdle {
            checkNotNull(connection).edit()
        }
        compose.waitForIdle()
    }
    private fun staleRefresh() {
        compose.runOnIdle { toolbar.value = toolbar.value.copy(canSteer = !toolbar.value.canSteer) }
        compose.waitForIdle()
    }
    @Test fun committedCandidateSurvivesStaleComposerSnapshots() {
        mount()
        ime { assertTrue(commitText("中文候选词", 1)) }
        repeat(3) { staleRefresh() }
        compose.runOnIdle {
            assertEquals("已有内容：中文候选词", editor.text.toString())
            assertEquals(TextRange(10), editor.selection)
            assertNull(editor.composition)
        }
        ime { assertTrue(commitText("继续", 1)) }
        staleRefresh()
        compose.runOnIdle {
            assertEquals("已有内容：中文候选词继续", editor.text.toString())
            assertEquals(TextRange(12), editor.selection)
        }
    }
    @Test fun compositionAndMiddleInsertionSurviveStatusUpdates() {
        editor.setTextAndPlaceCursorAtEnd("已有内容：末尾")
        mount()
        ime { assertTrue(setSelection(5, 5)); assertTrue(setComposingText("zhongwen", 1)) }
        staleRefresh()
        compose.runOnIdle {
            assertEquals("已有内容：zhongwen末尾", editor.text.toString())
            assertEquals(TextRange(5, 13), editor.composition)
        }
        ime { assertTrue(commitText("中文", 1)); assertTrue(finishComposingText()) }
        staleRefresh()
        compose.runOnIdle {
            assertEquals("已有内容：中文末尾", editor.text.toString())
            assertEquals(TextRange(7), editor.selection)
        }
        ime { assertTrue(setSelection(1, 3)); assertTrue(commitText("替换", 1)) }
        staleRefresh()
        compose.runOnIdle {
            assertEquals("已替换容：中文末尾", editor.text.toString())
            assertEquals(TextRange(3), editor.selection)
        }
    }
    @Test fun sendClearAndExplicitPrefillDoNotResurrectStaleText() {
        mount()
        ime { assertTrue(commitText("中文", 1)) }
        compose.runOnIdle { editor.setTextAndPlaceCursorAtEnd("") }
        staleRefresh()
        compose.runOnIdle {
            assertEquals("", editor.text.toString())
            assertEquals(TextRange.Zero, editor.selection)
            editor.setTextAndPlaceCursorAtEnd("/compact ")
        }
        staleRefresh()
        compose.runOnIdle {
            assertEquals("/compact ", editor.text.toString())
            assertEquals(TextRange(9), editor.selection)
        }
    }
    @Test fun screenRecreationKeepsTheHolderOwnedCursor() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            HapiTheme {
                InterceptPlatformTextInput(interceptor) {
                    ChatComposer(state = toolbar.value, editor = editor,
                        onTextChange = {}, onSend = {}, onSendSteer = {}, onAbort = {})
                }
            }
        }
        compose.runOnIdle { editor.edit { selection = TextRange(2) } }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals("已有内容：", editor.text.toString())
            assertEquals(TextRange(2), editor.selection)
        }
    }
}
