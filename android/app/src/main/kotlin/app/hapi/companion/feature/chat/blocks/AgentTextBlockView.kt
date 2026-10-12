package app.hapi.companion.feature.chat.blocks

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.hapi.companion.R
import app.hapi.companion.ui.markdown.Markdown
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.companion.ui.theme.hapi
import app.hapi.protocol.chat.AgentReasoningBlock
import app.hapi.protocol.chat.AgentTextBlock
import app.hapi.protocol.chat.reasoningPreview

/** Assistant prose: full-width markdown (the shared M2d1 renderer). */
@Composable
fun AgentTextBlockView(block: AgentTextBlock, modifier: Modifier = Modifier) {
    Markdown(text = block.text, modifier = modifier.fillMaxWidth(), selectable = true)
}

/**
 * Extended thinking: collapsed to a subdued one-liner by default, expands in
 * place to the full reasoning markdown (still subdued — it is meta-content).
 */
@Composable
fun AgentReasoningBlockView(block: AgentReasoningBlock, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(block.id) { mutableStateOf(false) }
    val hint = MaterialTheme.hapi.hint

    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .sizeIn(minHeight = 48.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("›", modifier = Modifier.rotate(if (expanded) 90f else 0f), color = hint)
                Text(stringResource(R.string.tool_reasoning), style = MaterialTheme.typography.labelMedium, color = hint)
                if (!expanded) Text(
                    text = reasoningPreview(block.text),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = hint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                CompositionLocalProvider(LocalContentColor provides hint) {
                    Markdown(
                        text = block.text,
                        modifier = Modifier.padding(14.dp),
                        selectable = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AgentTextBlockPreview() {
    HapiTheme {
        Surface {
            Column(modifier = Modifier.padding(12.dp)) {
                AgentTextBlockView(
                    AgentTextBlock(
                        id = "a1",
                        localId = null,
                        createdAt = 0,
                        invokedAt = null,
                        text = "The failing test was a **cursor regression**:\n\n" +
                            "1. `beforeSeq` lost its `beforeAt` half\n" +
                            "2. the hub returned `reset: true`\n\n" +
                            "```kotlin\nval cursor = MessagePosition(at, seq)\n```",
                        meta = null,
                    ),
                )
                AgentReasoningBlockView(
                    AgentReasoningBlock(
                        id = "r1",
                        localId = null,
                        createdAt = 0,
                        invokedAt = null,
                        text = "The user wants pagination fixed. Let me check the cursor pair first…",
                        meta = null,
                    ),
                )
            }
        }
    }
}
