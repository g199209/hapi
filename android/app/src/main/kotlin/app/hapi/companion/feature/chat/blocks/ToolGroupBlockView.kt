package app.hapi.companion.feature.chat.blocks

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.hapi.companion.R
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.companion.ui.theme.hapi
import app.hapi.protocol.chat.ToolGroupBlock
import app.hapi.protocol.chat.ToolGroupActionKind

/** A group always occupies one transcript row; browsing belongs to the inspector. */
@Composable
fun ToolGroupBlockView(block: ToolGroupBlock, basePath: String?, modifier: Modifier = Modifier) {
    val inspection = app.hapi.companion.feature.chat.LocalChatInspection.current
    val title = if (block.summary.totalTools == 1) stringResource(R.string.chat_group_tools_one)
        else stringResource(R.string.chat_group_tools_many, block.summary.totalTools)
    val summary = listOfNotNull(block.activityTitle, groupSummaryText(block).takeIf { it.isNotEmpty() }).joinToString(" · ")
    Surface(
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        ToolSummaryRow(
            app.hapi.companion.feature.chat.ToolCardPresentation("🔧", title, summary.takeIf { it.isNotEmpty() }),
            when {
                block.summary.errorCount > 0 -> app.hapi.protocol.chat.ToolState.ERROR
                block.summary.runningCount > 0 -> app.hapi.protocol.chat.ToolState.RUNNING
                else -> app.hapi.protocol.chat.ToolState.COMPLETED
            },
            onClick = { inspection?.openGroup(block.id) },
        )
    }
}

/** Describe actions, rather than treating a whole shell script as a file path. */
@Composable
private fun groupSummaryText(block: ToolGroupBlock): String {
    val labels = listOf(
        ToolGroupActionKind.MUTATION to R.string.chat_group_count_mutation,
        ToolGroupActionKind.READ to R.string.chat_group_count_read,
        ToolGroupActionKind.COMMAND to R.string.chat_group_count_command,
        ToolGroupActionKind.SEARCH to R.string.chat_group_count_search,
        ToolGroupActionKind.WEB to R.string.chat_group_count_web,
        ToolGroupActionKind.OTHER to R.string.chat_group_count_other,
    )
    val parts = labels.mapNotNull { (kind, label) ->
        val count = block.summary.countsByKind[kind] ?: 0
        if (count > 0) stringResource(label, count) else null
    }.toMutableList()
    if (block.summary.errorCount > 0) parts.add(stringResource(R.string.chat_group_count_error, block.summary.errorCount))
    return parts.joinToString(" · ")
}

@Preview(showBackground = true)
@Composable
private fun ToolGroupBlockPreview() {
    HapiTheme {
        Surface {
            val tools = listOf(
                previewToolCall("g1", "Read", input = mapOf("file_path" to "web/src/chat/reducer.ts")),
                previewToolCall("g2", "Grep", input = mapOf("pattern" to "tailRevision")),
                previewToolCall("g3", "Bash", input = mapOf("command" to "bun test")),
            )
            ToolGroupBlockView(
                app.hapi.protocol.chat.buildVisibleChatBlocks(
                    tools,
                    app.hapi.protocol.chat.ToolGroupingOptions(hasMoreMessages = false),
                ).filterIsInstance<ToolGroupBlock>().first(),
                basePath = null,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}
