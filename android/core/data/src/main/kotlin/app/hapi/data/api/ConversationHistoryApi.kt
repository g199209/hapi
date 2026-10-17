package app.hapi.data.api

import kotlinx.serialization.Serializable

/** Client actions use hub localIds; never send rendered block or native thread ids. */
interface ConversationHistoryApi {
    suspend fun forkConversation(sessionId: String, messageLocalId: String? = null): ForkConversationResponse
    suspend fun rewindConversation(sessionId: String, messageLocalId: String)
}

@Serializable
data class ForkConversationResponse(val sessionId: String)

@Serializable
internal data class RewindConversationResponse(val success: Boolean)
