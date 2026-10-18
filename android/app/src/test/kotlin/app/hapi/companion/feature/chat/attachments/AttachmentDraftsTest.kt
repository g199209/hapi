package app.hapi.companion.feature.chat.attachments

import app.hapi.data.api.AttachmentUploadApi
import app.hapi.protocol.wire.DeleteUploadResponse
import app.hapi.protocol.wire.UploadFileResponse
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

private class DraftUploadApi : AttachmentUploadApi {
    val uploads = mutableListOf<ByteArray>()
    val deletes = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var fail = false
    override suspend fun uploadFile(sessionId: String, filename: String, contentBase64: String, mimeType: String): UploadFileResponse {
        uploads += Base64.getDecoder().decode(contentBase64)
        gate?.await()
        if (fail) throw IllegalStateException("offline")
        return UploadFileResponse(success = true, path = "/uploads/$sessionId/$filename")
    }
    override suspend fun deleteUpload(sessionId: String, path: String): DeleteUploadResponse {
        deletes += path; return DeleteUploadResponse(success = true)
    }
}

class AttachmentDraftsTest {
    private fun picked(id: String = "photo") = PreparedAttachment(id, "中文照片😀.png", "image/png", byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6))
    private suspend fun ComposerAttachments.ready() = items.first { it.size == 1 && it.single().status == ComposerAttachmentStatus.Ready }

    @Test fun `binary snapshots preserve ready paths thumbnails and failed payload bytes`() = runTest {
        val dir = Files.createTempDirectory("attachment-draft").toFile()
        try {
            val store = FileAttachmentDraftStore(dir)
            val ui = ComposerAttachmentUi("one", "中文😀.png", "image/png", 4, byteArrayOf(8, 9), ComposerAttachmentStatus.Ready)
            store.save("session", listOf(AttachmentDraftEntry(ui, "/remote/中文.png", null), AttachmentDraftEntry(ui.copy(id = "two", status = ComposerAttachmentStatus.Failed), null, byteArrayOf(1, 2, 3, 4))))
            val entries = store.load("session")
            assertEquals(2, entries.size); assertEquals("/remote/中文.png", entries[0].path)
            assertEquals(ui.filename, entries[0].ui.filename); assertContentEquals(ui.previewBytes, entries[0].ui.previewBytes)
            assertNull(entries[0].bytes); assertContentEquals(byteArrayOf(1, 2, 3, 4), entries[1].bytes)
            store.save("session", emptyList()); assertTrue(store.load("session").isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `returning to the same session reuses its ready tray without upload or delete`() = runTest {
        val dir = Files.createTempDirectory("attachment-nav").toFile()
        try {
            val api = DraftUploadApi(); val registry = ComposerAttachmentDrafts(api, backgroundScope, FileAttachmentDraftStore(dir))
            val tray = registry.get("session"); tray.add(picked()); tray.ready()
            val chatScope = CoroutineScope(SupervisorJob()); chatScope.cancel()
            assertSame(tray, registry.get("session")); assertTrue(tray.allReady())
            assertEquals(1, api.uploads.size); assertTrue(api.deletes.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `navigation keeps an in-flight upload and its thumbnail`() = runTest {
        val dir = Files.createTempDirectory("attachment-inflight").toFile()
        try {
            val api = DraftUploadApi().apply { gate = CompletableDeferred() }
            val registry = ComposerAttachmentDrafts(api, backgroundScope, FileAttachmentDraftStore(dir))
            val tray = registry.get("session"); tray.add(picked())
            tray.items.first { it.size == 1 }; assertSame(tray, registry.get("session"))
            assertEquals(ComposerAttachmentStatus.Uploading, tray.items.value.single().status)
            assertContentEquals(byteArrayOf(5, 6), tray.items.value.single().previewBytes)
            api.gate!!.complete(Unit); tray.ready(); assertEquals(1, api.uploads.size); assertTrue(api.deletes.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `slow picker preparation continues outside the chat and gates sending`() = runTest {
        val dir = Files.createTempDirectory("attachment-prepare").toFile()
        try {
            val api = DraftUploadApi(); val registry = ComposerAttachmentDrafts(api, backgroundScope, FileAttachmentDraftStore(dir))
            val gate = CompletableDeferred<Unit>(); val tray = registry.get("session")
            tray.prepare { gate.await(); picked() }
            assertTrue(tray.hasUnsettled()); assertSame(tray, registry.get("session"))
            gate.complete(Unit); tray.ready(); tray.flush()
            assertEquals(1, api.uploads.size); assertTrue(api.deletes.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `a new hub graph restores Ready without another upload`() = runTest {
        val dir = Files.createTempDirectory("attachment-restart").toFile()
        try {
            val api = DraftUploadApi(); val store = FileAttachmentDraftStore(dir)
            val first = ComposerAttachmentDrafts(api, backgroundScope, store).get("session")
            first.add(picked()); first.ready(); first.flush()
            val second = ComposerAttachmentDrafts(api, backgroundScope, FileAttachmentDraftStore(dir)).get("session")
            second.ready()
            assertEquals(first.draftSnapshot().single().path, second.draftSnapshot().single().path)
            assertContentEquals(first.items.value.single().previewBytes, second.items.value.single().previewBytes)
            assertEquals(1, api.uploads.size); assertTrue(api.deletes.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `failed and interrupted uploads restore retry bytes without automatic resubmission`() = runTest {
        val dir = Files.createTempDirectory("attachment-retry").toFile()
        try {
            val api = DraftUploadApi(); val store = FileAttachmentDraftStore(dir)
            val ui = ComposerAttachmentUi("photo", "pending.png", "image/png", 4, byteArrayOf(5, 6), ComposerAttachmentStatus.Uploading)
            store.save("session", listOf(AttachmentDraftEntry(ui, null, byteArrayOf(1, 2, 3, 4))))
            val restored = ComposerAttachmentDrafts(api, backgroundScope, store).get("session")
            restored.items.first { it.size == 1 && it.single().status == ComposerAttachmentStatus.Failed }
            assertTrue(api.uploads.isEmpty()); assertTrue(restored.hasUnsettled())
            restored.retry("photo"); restored.ready()
            assertContentEquals(byteArrayOf(1, 2, 3, 4), api.uploads.single())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `consume durably clears the draft while keeping the sent remote file`() = runTest {
        val dir = Files.createTempDirectory("attachment-consume").toFile()
        try {
            val api = DraftUploadApi(); val store = FileAttachmentDraftStore(dir)
            val tray = ComposerAttachmentDrafts(api, backgroundScope, store).get("session")
            tray.add(picked()); tray.ready(); tray.flush()
            val sent = tray.consume()!!.single(); tray.flush()
            assertEquals("/uploads/session/中文照片😀.png", sent.path); assertTrue(store.load("session").isEmpty())
            assertTrue(api.deletes.isEmpty()); assertTrue(tray.items.first { it.isEmpty() }.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `removal persists before deleting the remote upload`() = runTest {
        val dir = Files.createTempDirectory("attachment-remove").toFile()
        try {
            val store = FileAttachmentDraftStore(dir)
            val api = object : AttachmentUploadApi {
                override suspend fun uploadFile(sessionId: String, filename: String, contentBase64: String, mimeType: String) = UploadFileResponse(true, "/remote/photo")
                override suspend fun deleteUpload(sessionId: String, path: String): DeleteUploadResponse {
                    assertTrue(store.load(sessionId).isEmpty()); return DeleteUploadResponse(true)
                }
            }
            val tray = ComposerAttachmentDrafts(api, backgroundScope, store).get("session")
            tray.add(picked()); tray.ready(); tray.flush(); tray.remove("photo"); tray.flush()
            assertTrue(store.load("session").isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `late restore cannot resurrect a removed entry or overwrite a new pick`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = DraftUploadApi()
        val old = AttachmentDraftEntry(ComposerAttachmentUi("old", "old.png", "image/png", 1, null, ComposerAttachmentStatus.Ready), "/old", null)
        var saved = emptyList<AttachmentDraftEntry>()
        val store = object : AttachmentDraftStore {
            override suspend fun load(sessionId: String): List<AttachmentDraftEntry> { gate.await(); return listOf(old) }
            override suspend fun save(sessionId: String, entries: List<AttachmentDraftEntry>) { saved = entries }
        }
        val tray = ComposerAttachmentDrafts(api, backgroundScope, store).get("session")
        tray.remove("old"); tray.add(picked("new")); gate.complete(Unit)
        tray.ready(); tray.flush()
        assertEquals(listOf("new"), tray.items.value.map { it.id })
        assertEquals(listOf("new"), saved.map { it.ui.id })
    }

    @Test fun `sessions and hub directories keep attachment drafts isolated`() = runTest {
        val dir = Files.createTempDirectory("attachment-isolation").toFile()
        try {
            val api = DraftUploadApi(); val first = ComposerAttachmentDrafts(api, backgroundScope, FileAttachmentDraftStore(java.io.File(dir, "hub-a")))
            val tray = first.get("session"); tray.add(picked()); tray.ready(); tray.flush()
            val otherSession = first.get("other"); otherSession.flush(); assertTrue(otherSession.items.value.isEmpty())
            val otherHub = ComposerAttachmentDrafts(api, backgroundScope, FileAttachmentDraftStore(java.io.File(dir, "hub-b"))).get("session")
            otherHub.flush(); assertTrue(otherHub.items.value.isEmpty()); assertTrue(tray.allReady())
        } finally { dir.deleteRecursively() }
    }
}
