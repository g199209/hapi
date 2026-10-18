package app.hapi.companion.feature.chat.attachments

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.HapiApp
import app.hapi.companion.MainActivity
import app.hapi.companion.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in against a task-owned session; the host force-stops between phases. */
class AttachmentDraftLiveTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HapiApp
    private val filename = "hapi-draft.png"
    private val text = "看一下附件图片，只回复 HAPI_ATTACHMENT_OK，不要使用工具。"
    private fun fixture(): String {
        val id = InstrumentationRegistry.getArguments().getString("attachmentFixture").orEmpty()
        assumeTrue("An explicit task-owned fixture is required", id.isNotBlank())
        compose.waitUntil(15_000) { app.appGraph.activeHubGraph.value != null }
        compose.runOnUiThread { app.appGraph.pendingOpenSessionId.value = id }
        compose.waitUntil(30_000) { app.appGraph.openChatSessionId.value == id }
        return id
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun prepareBackReenterAndRotationKeepOneUploadedImageAndText() {
        val id = fixture(); val graph = app.appGraph.activeHubGraph.value!!
        val before = runBlocking { graph.session.api.getMessages(id, limit = 100).messages }
        assertTrue(before.any { it.content.toString().contains("HAPI_ATTACHMENT_SOURCE") })
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val preview = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        bitmap.recycle()
        compose.onNodeWithTag("chat-composer-input").performTextClearance()
        compose.onNodeWithTag("chat-composer-input").performTextInput(text)
        compose.waitUntil { runBlocking { graph.chatDrafts.load(id) } == text }
        val tray = graph.attachmentDrafts.get(id)
        runBlocking { tray.flush() }
        compose.runOnIdle { tray.draftSnapshot().forEach { tray.remove(it.ui.id) } }
        runBlocking { tray.flush() }
        compose.runOnIdle { tray.prepare { delay(800); PreparedAttachment("draft-image", filename, "image/png", bytes, preview) } }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_back)).performClick()
        compose.waitUntil(15_000) { app.appGraph.openChatSessionId.value != id }
        compose.runOnUiThread { app.appGraph.pendingOpenSessionId.value = id }
        compose.waitUntil(15_000) { app.appGraph.openChatSessionId.value == id }
        compose.waitUntil(30_000) { tray.items.value.singleOrNull()?.status == ComposerAttachmentStatus.Ready }
        compose.onNodeWithText(filename).assertExists()
        compose.onNodeWithTag("chat-composer-input").assertTextContains(text)
        val first = tray.draftSnapshot().single(); assertNotNull(first.path)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_back)).performClick()
        compose.waitUntil(15_000) { app.appGraph.openChatSessionId.value != id }
        compose.runOnUiThread { app.appGraph.pendingOpenSessionId.value = id }
        compose.waitUntil(15_000) { app.appGraph.openChatSessionId.value == id }
        compose.onNodeWithText(filename).assertExists()
        assertSame(tray, graph.attachmentDrafts.get(id))
        assertEquals(first.path, tray.draftSnapshot().single().path)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(filename).assertExists()
        compose.onNodeWithTag("chat-composer-input").assertTextContains(text)
        runBlocking { tray.flush() }
        File(app.filesDir, "attachment-draft-live.json").writeText(buildJsonObject {
            put("sessionId", id); put("path", first.path); put("previewBase64", Base64.getEncoder().encodeToString(preview))
            put("imageSHA256", hash(bytes)); put("text", text); put("navigationVerified", true); put("rotationVerified", true)
        }.toString())
    }

    @Test fun processRestartRestoresSamePathAndThumbnailThenSendsOnce() {
        val id = fixture(); val graph = app.appGraph.activeHubGraph.value!!
        val saved = Json.parseToJsonElement(File(app.filesDir, "attachment-draft-live.json").readText()).jsonObject
        assertEquals(id, saved["sessionId"]!!.jsonPrimitive.content)
        val tray = graph.attachmentDrafts.get(id)
        compose.waitUntil(30_000) { tray.items.value.singleOrNull()?.status == ComposerAttachmentStatus.Ready }
        assertEquals(saved["path"]!!.jsonPrimitive.content, tray.draftSnapshot().single().path)
        assertEquals(saved["previewBase64"]!!.jsonPrimitive.content, Base64.getEncoder().encodeToString(tray.items.value.single().previewBytes!!))
        compose.onNodeWithText(filename).assertExists(); compose.onNodeWithTag("chat-composer-input").assertTextContains(text)
        fun users() = runBlocking { graph.session.api.getMessages(id, limit = 100).messages }.filter { it.content.jsonObject["role"]?.jsonPrimitive?.content == "user" }
        assertEquals(1, users().size)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_composer_send)).performClick()
        compose.waitUntil(60_000) { users().size == 2 }
        val user = users().last(); val content = user.content.jsonObject["content"]!!.jsonObject
        val attachment = content["attachments"]!!.jsonArray.single().jsonObject
        assertEquals(saved["path"]!!.jsonPrimitive.content, attachment["path"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64," + saved["previewBase64"]!!.jsonPrimitive.content, attachment["previewUrl"]!!.jsonPrimitive.content)
        compose.waitUntil(90_000) { compose.onAllNodesWithText("HAPI_ATTACHMENT_OK", substring = true, useUnmergedTree = true).fetchSemanticsNodes().any { !it.config.toString().contains("只回复") } }
        assertTrue(tray.draftSnapshot().isEmpty()); runBlocking { tray.flush() }
        File(app.filesDir, "attachment-draft-send.json").writeText(buildJsonObject {
            put("sessionId", id); put("path", attachment["path"]!!); put("localId", user.localId)
            put("restartVerified", true); put("sentExactlyOnce", true); put("draftCleared", true)
        }.toString())
    }
}
