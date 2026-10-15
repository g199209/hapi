package app.hapi.protocol.wire

import kotlinx.serialization.Serializable

/** Runner-owned Codex history, shared with the WebUI import flow. */
@Serializable
data class CodexLocalSessionSummary(
    val id: String,
    val title: String,
    @Serializable(with = LenientEpochMs::class)
    val modifiedAt: Long,
    val cwd: String? = null,
    val lastUserMessage: String? = null,
    val originator: String? = null,
    val cliVersion: String? = null,
    val threadSource: String? = null,
    val forkedFromId: String? = null,
)

@Serializable
data class CodexLocalSessionsResponse(
    val success: Boolean,
    val sessions: List<CodexLocalSessionSummary> = emptyList(),
    val machineId: String? = null,
    val error: String? = null,
)

/** No launch options: importing keeps the native thread's settings. */
@Serializable
data class CodexImportRequest(val machineId: String, val sessionIds: List<String>)

@Serializable
data class CodexImportResponse(
    val success: Boolean,
    val hapiSessionIds: List<String> = emptyList(),
    val error: String? = null,
    val message: String? = null,
)
