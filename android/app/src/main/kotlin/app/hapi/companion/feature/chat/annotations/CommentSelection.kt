package app.hapi.companion.feature.chat.annotations

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import app.hapi.companion.R

/** Capture Compose's selected text through its copy callback, without reading or
 * writing the system clipboard. MathClipboard inside Markdown decodes TeX first. */
@Composable
fun CommentSelection(onComment: ((String) -> Unit)?, content: @Composable () -> Unit) {
    if (onComment == null) { content(); return }
    val view = LocalView.current
    val platform = LocalClipboard.current
    val comment = rememberUpdatedState(onComment)
    val label = stringResource(R.string.annotation_add)
    val capture = remember(platform) { SelectionCapture(platform) }
    val toolbar = remember(view, capture, label) { CommentToolbar(view, label) { copy ->
        capture.capture { text -> if (text.isNotBlank()) comment.value?.invoke(text) }
        copy()
    } }
    DisposableEffect(toolbar, capture) { onDispose { toolbar.hide(); capture.cancel() } }
    val directComment = remember { { quote: String -> comment.value?.invoke(quote); Unit } }
    CompositionLocalProvider(
        LocalClipboard provides capture,
        LocalTextToolbar provides toolbar,
        app.hapi.companion.ui.markdown.LocalMarkdownComment provides directComment,
        content = content,
    )
}

internal class SelectionCapture(private val platform: Clipboard) : Clipboard by platform {
    private var pending: ((String) -> Unit)? = null
    fun capture(callback: (String) -> Unit) { pending = callback }
    fun cancel() { pending = null }
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val callback = pending
        if (callback == null) { platform.setClipEntry(clipEntry); return }
        pending = null
        val text = clipEntry?.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text?.toString()
        if (text != null) callback(text)
    }
}

private class CommentToolbar(
    private val view: View,
    private val label: String,
    private val comment: (() -> Unit) -> Unit,
) : TextToolbar {
    private var mode: ActionMode? = null
    override val status get() = if (mode == null) TextToolbarStatus.Hidden else TextToolbarStatus.Shown
    override fun hide() { mode?.finish(); mode = null }
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        hide()
        val callbacks = mutableMapOf<Int, () -> Unit>()
        mode = view.startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                fun add(id: Int, title: CharSequence, action: (() -> Unit)?) {
                    if (action != null) { callbacks[id] = action; menu.add(0, id, id, title).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM) }
                }
                add(1, view.context.getString(android.R.string.copy), onCopyRequested)
                add(2, label, onCopyRequested?.let { copy -> { comment(copy) } })
                add(3, view.context.getString(android.R.string.selectAll), onSelectAllRequested)
                return callbacks.isNotEmpty()
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val action = callbacks[item.itemId] ?: return false
                // Finish before Select all: it may synchronously open the updated toolbar.
                mode.finish(); action(); return true
            }
            override fun onDestroyActionMode(mode: ActionMode) { if (this@CommentToolbar.mode === mode) this@CommentToolbar.mode = null }
            override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
                outRect.set(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
            }
        }, ActionMode.TYPE_FLOATING)
    }
}
