package app.hapi.companion.feature.chat.annotations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hapi.companion.R

@Composable
fun AnnotationCard(value: ResponseAnnotation, index: Int, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(value.text) { mutableStateOf(false) }
    var overflow by remember(value.text) { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SelectionContainer {
                Column {
                    Text("〔${index + 1}〕 ${value.text}", style = MaterialTheme.typography.bodySmall,
                        maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow })
                }
            }
            if (value.annotation.isNotBlank()) app.hapi.companion.ui.markdown.Markdown(
                value.annotation, textStyle = MaterialTheme.typography.bodyMedium, selectable = true, preserveLineBreaks = true,
            )
            if (overflow || expanded) TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.annotation_less else R.string.annotation_more))
            }
        }
    }
}

@Composable
fun AnnotationTray(drafts: AnnotationDrafts) {
    val items by drafts.items.collectAsState()
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(8.dp).testTag("annotation-tray")) {
        items.forEachIndexed { index, item ->
            AnnotationCard(item.value, index)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { drafts.edit(item.id) }, modifier = Modifier.testTag("annotation-edit-$index")) { Text(stringResource(R.string.annotation_edit)) }
                TextButton(onClick = { drafts.remove(item.id) }, modifier = Modifier.testTag("annotation-remove-$index")) { Text(stringResource(R.string.annotation_remove)) }
            }
        }
    }
}

@Composable
fun AnnotationDialog(drafts: AnnotationDrafts) {
    val edit by drafts.edit.collectAsState()
    val current = edit ?: return
    AlertDialog(
        onDismissRequest = drafts::dismiss,
        title = { Text(stringResource(if (current.id == null) R.string.annotation_add else R.string.annotation_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(current.value.text, style = MaterialTheme.typography.bodySmall, maxLines = 8, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.annotation_comment), style = MaterialTheme.typography.labelMedium)
                Surface(shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    BasicTextField(
                        state = current.editor,
                        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 6),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("annotation-comment"),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = drafts::confirm, modifier = Modifier.testTag("annotation-confirm")) { Text(stringResource(R.string.annotation_save)) } },
        dismissButton = { TextButton(onClick = drafts::dismiss) { Text(stringResource(R.string.annotation_cancel)) } },
    )
}
