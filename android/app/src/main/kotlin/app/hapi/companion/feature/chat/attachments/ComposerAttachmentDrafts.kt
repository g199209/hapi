package app.hapi.companion.feature.chat.attachments

import app.hapi.data.api.AttachmentUploadApi
import kotlinx.coroutines.CoroutineScope

/** One tray per session on the active Hub. Navigating away disposes the chat,
 * while its uploads and state continue here. Hub teardown cancels work; files
 * restore the draft when that Hub is opened again. */
class ComposerAttachmentDrafts(
    private val api: AttachmentUploadApi,
    private val scope: CoroutineScope,
    private val store: AttachmentDraftStore,
) {
    private val trays = mutableMapOf<String, ComposerAttachments>()

    @Synchronized
    fun get(sessionId: String): ComposerAttachments = trays.getOrPut(sessionId) {
        ComposerAttachments(api, sessionId, scope, draftStore = store)
    }
}
