package app.hapi.protocol.wire

import kotlinx.serialization.Serializable

/** Hub returns masked status only; credentials are never read back. */
@Serializable
data class CredentialStatus(
    val configured: Boolean = false,
    val source: String = "none",
    val hint: String? = null,
    val editable: Boolean = false,
)

@Serializable
data class CompatibleCredentialStatus(
    val configured: Boolean = false,
    val source: String = "none",
    val baseUrl: String? = null,
    val model: String? = null,
    val baseUrlEditable: Boolean = false,
    val modelEditable: Boolean = false,
    val apiKey: CredentialStatus = CredentialStatus(),
)

@Serializable
data class TranscriptionCredentials(
    val openai: CredentialStatus = CredentialStatus(),
    val elevenlabs: CredentialStatus = CredentialStatus(),
    val deepgram: CredentialStatus = CredentialStatus(),
    val groq: CredentialStatus = CredentialStatus(),
    val openaiCompatible: CompatibleCredentialStatus = CompatibleCredentialStatus(),
)
