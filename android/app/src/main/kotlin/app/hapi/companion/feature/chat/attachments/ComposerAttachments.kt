package app.hapi.companion.feature.chat.attachments

import app.hapi.data.api.AttachmentUploadApi
import app.hapi.protocol.wire.AttachmentMetadata
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A picked file after platform preparation (ContentResolver read + optional
 * image downscale): everything the upload flow needs, no Android types —
 * JVM tests feed these directly.
 */
class PreparedAttachment(
    val id: String,
    /** Display/upload filename (extension rewritten to `.jpg` when compressed). */
    val filename: String,
    val mimeType: String,
    /** The exact bytes that will upload (post-compression when applicable). */
    val bytes: ByteArray,
    /** Small JPEG thumbnail for the chip + wire `previewUrl`; null for non-images. */
    val previewBytes: ByteArray? = null,
) {
    val sizeBytes: Long get() = bytes.size.toLong()
}

/** Chip lifecycle: uploading → ready (or failed → retry/remove). */
enum class ComposerAttachmentStatus { Uploading, Ready, Failed }

/** One composer attachment chip. */
@Suppress("ArrayInDataClass")
data class ComposerAttachmentUi(
    val id: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** JPEG thumbnail bytes for image picks; null renders a file glyph. */
    val previewBytes: ByteArray?,
    val status: ComposerAttachmentStatus,
)

/**
 * Composer attachment tray (B-M3f): upload-on-pick state machine feeding
 * `SendMessageRequest.attachments`.
 *
 * Mirrors the web `attachmentAdapter.ts` flow with mobile adjustments:
 *
 * - [add] uploads immediately (`POST upload`, JSON + base64) and tracks the
 *   chip through [ComposerAttachmentStatus]; failures keep the payload bytes
 *   for [retry], successes drop them (only the small preview stays resident).
 * - [remove] deletes the uploaded file best-effort (`POST upload/delete`);
 *   removing a chip whose upload is still in flight lets the upload finish
 *   and then deletes the orphan (web `cancelledAttachmentIds` semantics).
 * - [consume] converts every Ready chip into [AttachmentMetadata] for the
 *   send body — `previewUrl` is a small JPEG data URL
 *   ([AttachmentPolicy.PREVIEW_MAX_DIMENSION]) so user bubbles render
 *   thumbnails on every client.
 * - **Drafts**: the Hub-scoped registry retains the tray and uploads across
 *   navigation. Private snapshots retain paths/previews and pending payloads.
 *   An interrupted upload restores Failed for an explicit retry; Ready paths
 *   restore without uploading again. Only remove/discard delete remote files.
 * - **Inactive sessions (v1 simplification)**: the hub's upload route
 *   requires an active session, and unlike the web this tray does not
 *   resume-then-upload — a pick on an inactive session settles Failed;
 *   sending any text auto-resumes (B-M3ab), after which the chip's retry
 *   succeeds. Uploaded paths are absolute on the agent machine, so they
 *   stay readable across a resume (even one that supersedes the id).
 */
class ComposerAttachments(
    private val api: AttachmentUploadApi,
    private val sessionId: String,
    private val scope: CoroutineScope,
    /** Base64 of up to 50 MB happens off the main thread. */
    private val encodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** [discardAllDetached] launch target; null ⇒ GlobalScope (production). */
    private val detachedCleanupScope: CoroutineScope? = null,
    private val draftStore: AttachmentDraftStore? = null,
) {
    private class Entry(
        val ui: ComposerAttachmentUi,
        /** Hub upload path once Ready. */
        val path: String? = null,
        /** Upload payload, retained only until the upload succeeds (retry source). */
        val bytes: ByteArray? = null,
    )

    private val entries = MutableStateFlow<List<Entry>>(emptyList())
    private val restored = CompletableDeferred<Unit>()
    private val persistenceMutex = Mutex()
    private val preparationMutex = Mutex()
    private val preparing = MutableStateFlow(0)
    private val removedIds = ConcurrentHashMap.newKeySet<String>()

    init {
        if (draftStore == null) restored.complete(Unit)
        else scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { draftStore.load(sessionId) }.getOrDefault(emptyList()) }
            entries.update { current ->
                val known = current.map { it.ui.id }.toSet()
                saved.filterNot { it.ui.id in known || it.ui.id in removedIds }.map {
                    // A process died before an upload outcome was persisted.
                    // Keep its bytes for explicit Retry, never submit it twice automatically.
                    val status = if (it.path != null) ComposerAttachmentStatus.Ready else ComposerAttachmentStatus.Failed
                    Entry(it.ui.copy(status = status), path = it.path, bytes = it.bytes)
                } + current
            }
            restored.complete(Unit)
            entries.collect { flush() }
        }
    }

    /** Serialize the latest state under the same lock for every writer. A late
     * background save therefore cannot resurrect an entry after consume/remove. */
    suspend fun flush() {
        restored.await()
        val store = draftStore ?: return
        persistenceMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching { store.save(sessionId, draftSnapshot()) }
            }
        }
    }

    internal fun draftSnapshot(): List<AttachmentDraftEntry> = entries.value.map { AttachmentDraftEntry(it.ui, it.path, it.bytes) }

    /** Chip states for the composer row. */
    val items: StateFlow<List<ComposerAttachmentUi>> = entries
        .map { list -> list.map { it.ui } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Convenience for send gating: chips exist and every one settled Ready. */
    fun allReady(): Boolean =
        entries.value.let { list -> list.isNotEmpty() && list.all { it.ui.status == ComposerAttachmentStatus.Ready } }

    /** True while any chip is Uploading or Failed — send must wait or resolve. */
    fun hasUnsettled(): Boolean =
        !restored.isCompleted || preparing.value > 0 || entries.value.any { it.ui.status != ComposerAttachmentStatus.Ready }

    /** Picker/camera reads also belong to the Hub scope. Keep preparation serial
     * to avoid decoding several full-size phone photos at once. */
    fun prepare(producer: suspend () -> PreparedAttachment?) {
        preparing.update { it + 1 }
        scope.launch {
            try { preparationMutex.withLock { producer()?.let(::add) } }
            finally { preparing.update { it - 1 } }
        }
    }

    /** Add a prepared pick to the tray and start its upload. */
    fun add(prepared: PreparedAttachment) {
        val ui = ComposerAttachmentUi(
            id = prepared.id,
            filename = prepared.filename,
            mimeType = prepared.mimeType,
            sizeBytes = prepared.sizeBytes,
            previewBytes = prepared.previewBytes,
            status = ComposerAttachmentStatus.Uploading,
        )
        entries.update { it + Entry(ui, bytes = prepared.bytes) }
        upload(prepared.id, prepared.filename, prepared.mimeType, prepared.bytes)
    }

    /** Failed chip tap: re-fire the upload with the retained bytes. */
    fun retry(id: String) {
        var payload: Entry? = null
        entries.update { list ->
            val entry = list.firstOrNull {
                it.ui.id == id && it.ui.status == ComposerAttachmentStatus.Failed && it.bytes != null
            } ?: return@update list
            payload = entry
            list.map {
                if (it.ui.id == id) Entry(it.ui.copy(status = ComposerAttachmentStatus.Uploading), bytes = it.bytes)
                else it
            }
        }
        val entry = payload ?: return
        upload(id, entry.ui.filename, entry.ui.mimeType, entry.bytes!!)
    }

    /**
     * Drop a chip. An already-uploaded file is deleted best-effort; an
     * in-flight upload deletes its result on completion (see [upload]).
     */
    fun remove(id: String) {
        removedIds.add(id)
        var removed: Entry? = null
        entries.update { list ->
            removed = list.firstOrNull { it.ui.id == id }
            list.filterNot { it.ui.id == id }
        }
        removed?.path?.let { path ->
            scope.launch { flush(); runCatching { api.deleteUpload(sessionId, path) } }
        }
    }

    /**
     * Take every Ready chip as send metadata, clearing them from the tray
     * (unsettled chips stay put — the ViewModel guards against calling with
     * any pending, but a race can settle one to Failed in between).
     *
     * @return the metadata list, or null when nothing was ready.
     */
    fun consume(): List<AttachmentMetadata>? {
        var taken: List<Entry> = emptyList()
        entries.update { list ->
            taken = list.filter { it.ui.status == ComposerAttachmentStatus.Ready && it.path != null }
            list - taken.toSet()
        }
        if (taken.isEmpty()) return null
        removedIds.addAll(taken.map { it.ui.id })
        return taken.map { entry ->
            AttachmentMetadata(
                id = entry.ui.id,
                filename = entry.ui.filename,
                mimeType = entry.ui.mimeType,
                size = entry.ui.sizeBytes,
                path = entry.path!!,
                previewUrl = entry.ui.previewBytes?.let { AttachmentPolicy.dataUrl("image/jpeg", it) },
            )
        }
    }

    /**
     * Explicitly discard the tray. Navigation retains it instead. This also
     * handles uploads completing after a removal through the orphan cleanup.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun discardAllDetached() {
        var dropped: List<Entry> = emptyList()
        entries.update { list ->
            dropped = list
            emptyList()
        }
        val paths = dropped.mapNotNull { it.path }
        removedIds.addAll(dropped.map { it.ui.id })
        (detachedCleanupScope ?: GlobalScope).launch(Dispatchers.IO) {
            flush()
            paths.forEach { path -> runCatching { api.deleteUpload(sessionId, path) } }
        }
    }

    private fun upload(id: String, filename: String, mimeType: String, bytes: ByteArray) {
        scope.launch {
            restored.await()
            flush()
            val base64 = withContext(encodeDispatcher) { Base64.getEncoder().encodeToString(bytes) }
            val path = try {
                val response = api.uploadFile(sessionId, filename, base64, mimeType)
                if (response.success) response.path else null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }

            var applied = false
            entries.update { list ->
                applied = list.any { it.ui.id == id }
                if (!applied) list
                else list.map { entry ->
                    when {
                        entry.ui.id != id -> entry
                        // Success: drop the payload bytes — only the preview stays.
                        path != null -> Entry(entry.ui.copy(status = ComposerAttachmentStatus.Ready), path = path)
                        else -> Entry(entry.ui.copy(status = ComposerAttachmentStatus.Failed), bytes = entry.bytes)
                    }
                }
            }
            flush()
            // Removed while uploading: the hub file just became an orphan.
            if (!applied && path != null) {
                runCatching { api.deleteUpload(sessionId, path) }
            }
        }
    }
}
