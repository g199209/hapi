package app.hapi.companion.feature.chat.annotations

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class AnnotationDraft(val id: String, val value: ResponseAnnotation)
data class AnnotationEdit(val id: String?, val value: ResponseAnnotation, val editor: TextFieldState = TextFieldState(value.annotation))

/** Holder-owned edit state survives activity recreation without changing the IME buffer. */
class AnnotationDrafts(private val changed: () -> Unit) {
    private val mutableItems = MutableStateFlow<List<AnnotationDraft>>(emptyList())
    val items = mutableItems.asStateFlow()
    private val mutableEdit = MutableStateFlow<AnnotationEdit?>(null)
    val edit = mutableEdit.asStateFlow()
    var revision: Long = 0; private set

    fun request(text: String, messageId: String?) {
        if (text.isBlank()) return
        mutableEdit.value = AnnotationEdit(null, ResponseAnnotation(text, source = messageId?.let { AnnotationSource(it) }))
    }
    fun edit(id: String) {
        mutableItems.value.firstOrNull { it.id == id }?.let { mutableEdit.value = AnnotationEdit(id, it.value) }
    }
    fun updateComment(text: String) {
        mutableEdit.value?.editor?.setTextAndPlaceCursorAtEnd(text)
    }
    fun dismiss() { mutableEdit.value = null }
    fun confirm() {
        val edit = mutableEdit.value ?: return
        val value = edit.value.copy(annotation = edit.editor.text.toString())
        if (edit.id == null) mutableItems.value += AnnotationDraft(UUID.randomUUID().toString(), value)
        else mutableItems.value = mutableItems.value.map { if (it.id == edit.id) it.copy(value = value) else it }
        mutableEdit.value = null
        revision++; changed()
    }
    fun remove(id: String) {
        mutableItems.value = mutableItems.value.filterNot { it.id == id }
        if (mutableEdit.value?.id == id) dismiss()
        revision++; changed()
    }
    fun restore(values: List<ResponseAnnotation>) {
        mutableItems.value = values.map { AnnotationDraft(UUID.randomUUID().toString(), it) }
        revision++
    }
    fun consume(): List<ResponseAnnotation> {
        val values = mutableItems.value.map { it.value }
        mutableItems.value = emptyList(); dismiss(); revision++
        return values
    }
    fun encode(request: String) = ResponseAnnotations.encode(request, mutableItems.value.map { it.value })
}
