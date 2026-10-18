package app.hapi.companion.feature.chat.attachments

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class AttachmentDraftEntry(
    val ui: ComposerAttachmentUi,
    val path: String?,
    val bytes: ByteArray?,
)

interface AttachmentDraftStore {
    suspend fun load(sessionId: String): List<AttachmentDraftEntry>
    suspend fun save(sessionId: String, entries: List<AttachmentDraftEntry>)
}

/** Private per-Hub files: small metadata/thumbnail plus binary retry payloads.
 * A prepared photo is copied before upload, so picker URI grants are unnecessary
 * after navigation or a process restart. Ready uploads retain only their path. */
class FileAttachmentDraftStore(private val directory: File) : AttachmentDraftStore {
    private fun file(sessionId: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$key.draft")
    }

    override suspend fun load(sessionId: String): List<AttachmentDraftEntry> {
        val file = file(sessionId)
        if (!file.exists()) return emptyList()
        return DataInputStream(file.inputStream().buffered()).use { input ->
            check(input.readInt() == 0x48415031)
            val count = input.readInt(); check(count >= 0 && count.toLong() <= file.length() / 8)
            List(count) {
                val id = input.readUTF(); val filename = input.readUTF(); val mime = input.readUTF()
                val size = input.readLong(); val status = ComposerAttachmentStatus.valueOf(input.readUTF())
                val path = if (input.readBoolean()) input.readUTF() else null
                val preview = input.bytes(); val payload = input.bytes()
                AttachmentDraftEntry(ComposerAttachmentUi(id, filename, mime, size, preview, status), path, payload)
            }
        }
    }

    override suspend fun save(sessionId: String, entries: List<AttachmentDraftEntry>) {
        val file = file(sessionId)
        if (entries.isEmpty()) { Files.deleteIfExists(file.toPath()); return }
        directory.mkdirs()
        val temporary = File.createTempFile("attachment-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                val output = DataOutputStream(stream.buffered())
                output.writeInt(0x48415031); output.writeInt(entries.size)
                entries.forEach { entry ->
                    val ui = entry.ui
                    output.writeUTF(ui.id); output.writeUTF(ui.filename); output.writeUTF(ui.mimeType)
                    output.writeLong(ui.sizeBytes); output.writeUTF(ui.status.name)
                    output.writeBoolean(entry.path != null); entry.path?.let(output::writeUTF)
                    output.bytes(ui.previewBytes); output.bytes(entry.bytes)
                }
                output.flush(); stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private fun DataInputStream.bytes(): ByteArray? {
        val size = readInt()
        if (size == -1) return null
        check(size >= 0 && size.toLong() <= AttachmentPolicy.MAX_UPLOAD_BYTES)
        return ByteArray(size).also(::readFully)
    }
    private fun DataOutputStream.bytes(bytes: ByteArray?) {
        writeInt(bytes?.size ?: -1); if (bytes != null) write(bytes)
    }
}
