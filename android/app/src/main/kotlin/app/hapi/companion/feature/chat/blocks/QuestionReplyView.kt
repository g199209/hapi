package app.hapi.companion.feature.chat.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hapi.companion.R
import app.hapi.companion.feature.chat.LocalChatInspection
import app.hapi.companion.feature.chat.messagePreview
import app.hapi.companion.ui.markdown.Markdown
import app.hapi.companion.ui.theme.HapiTypography

/** The answer is primary; the original question remains available as context. */
@Composable
internal fun QuestionReplyView(reply: QuestionReply, messageId: String, index: Int) {
    var expanded by rememberSaveable(reply.question) { mutableStateOf(false) }
    var overflow by remember(reply.question) { mutableStateOf(false) }
    val question = remember(reply.question) { messagePreview(reply.question) }
    val answer = remember(reply.answer) { messagePreview(reply.answer) }
    val inspection = LocalChatInspection.current
    val contextColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
    Column(Modifier.fillMaxWidth().testTag("question-reply-$index"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.question_reply_title), style = MaterialTheme.typography.labelMedium, color = contextColor)
        SelectionContainer {
            Text(
                question.text, style = MaterialTheme.typography.bodySmall, color = contextColor,
                maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow },
                modifier = Modifier.testTag("question-reply-question-$index"),
            )
        }
        if (overflow || expanded) TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("question-reply-expand-$index")) {
            Text(stringResource(if (expanded) R.string.question_reply_less else R.string.question_reply_more))
        }
        Markdown(answer.text, textStyle = HapiTypography.body.copy(fontWeight = FontWeight.Medium), selectable = true, preserveLineBreaks = true)
        if (question.end < reply.question.length || answer.end < reply.answer.length) TextButton(
            onClick = { inspection?.openMessage(messageId) }, enabled = inspection != null,
        ) { Text(stringResource(R.string.chat_view_full_message)) }
    }
}
