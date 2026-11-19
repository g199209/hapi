package app.hapi.protocol.wire

import kotlinx.serialization.Serializable

@Serializable
data class OpencodeModelSummary(val modelId: String, val name: String? = null) {
    val displayName: String
        get() = name?.takeIf(String::isNotBlank)?.let { "$it · ${modelId.substringBefore('/')}" } ?: modelId
}

@Serializable
data class OpencodeModelsResponse(
    val success: Boolean,
    val availableModels: List<OpencodeModelSummary> = emptyList(),
    val currentModelId: String? = null,
    val error: String? = null,
)
