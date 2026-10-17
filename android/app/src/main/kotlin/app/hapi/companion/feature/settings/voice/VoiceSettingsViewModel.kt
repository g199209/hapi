package app.hapi.companion.feature.settings.voice

import app.hapi.data.api.HapiApi
import app.hapi.protocol.wire.CredentialStatus
import app.hapi.protocol.wire.TranscriptionCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** These are standard dictation providers, not voice-assistant backends. */
enum class DictationProvider(val wire: String, val label: String) {
    OpenAI("openai", "OpenAI"), ElevenLabs("elevenlabs", "ElevenLabs"),
    Deepgram("deepgram", "Deepgram"), Groq("groq", "Groq"), Compatible("openaiCompatible", "OpenAI-compatible");
    fun credential(status: TranscriptionCredentials): CredentialStatus = when (this) {
        OpenAI -> status.openai; ElevenLabs -> status.elevenlabs; Deepgram -> status.deepgram
        Groq -> status.groq; Compatible -> status.openaiCompatible.apiKey
    }
    fun configured(status: TranscriptionCredentials) = if (this == Compatible) status.openaiCompatible.configured else credential(status).configured
    fun editable(status: TranscriptionCredentials) = if (this == Compatible) with(status.openaiCompatible) {
        baseUrlEditable || modelEditable || apiKey.editable
    } else credential(status).editable
}

/** Empty fields preserve stored values; only the explicit Clear action sends nulls. */
fun transcriptionUpdate(provider: DictationProvider, status: TranscriptionCredentials, key: String, baseUrl: String, model: String, clear: Boolean): JsonObject {
    fun value(text: String) = if (clear) JsonNull else JsonPrimitive(text.trim())
    return buildJsonObject {
        if (provider == DictationProvider.Compatible) {
            put(provider.wire, buildJsonObject {
                val s = status.openaiCompatible
                if (s.apiKey.editable && (clear || key.isNotBlank())) put("apiKey", value(key))
                if (s.baseUrlEditable && (clear || baseUrl.isNotBlank())) put("baseUrl", value(baseUrl))
                if (s.modelEditable && (clear || model.isNotBlank())) put("model", value(model))
            })
        } else if (provider.credential(status).editable && (clear || key.isNotBlank())) put(provider.wire, value(key))
    }
}

interface VoiceSettingsGateway {
    suspend fun load(): TranscriptionCredentials
    suspend fun update(body: JsonObject): TranscriptionCredentials
}
class ApiVoiceSettingsGateway(private val api: HapiApi) : VoiceSettingsGateway {
    override suspend fun load() = api.getTranscriptionCredentials()
    override suspend fun update(body: JsonObject) = api.updateTranscriptionCredentials(body)
}
data class VoiceSettingsUi(
    val status: TranscriptionCredentials? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val savedRevision: Long = 0,
)
class VoiceSettingsViewModel(private val api: VoiceSettingsGateway, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(VoiceSettingsUi())
    val state = mutable.asStateFlow()
    init { refresh() }
    fun refresh() = runOperation { api.load() }
    fun save(provider: DictationProvider, key: String, baseUrl: String, model: String, clear: Boolean = false) {
        val status = mutable.value.status ?: return
        val body = transcriptionUpdate(provider, status, key, baseUrl, model, clear)
        if (body.isEmpty() || (body[provider.wire] as? JsonObject)?.isEmpty() == true) return
        runOperation(saved = true) { api.update(body) }
    }
    private fun runOperation(saved: Boolean = false, operation: suspend () -> TranscriptionCredentials) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        scope.launch {
            try {
                val result = operation()
                mutable.value = mutable.value.copy(status = result, busy = false, savedRevision = mutable.value.savedRevision + if (saved) 1 else 0)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { mutable.value = mutable.value.copy(busy = false, error = error.message ?: "Unable to update dictation settings") }
        }
    }
}
