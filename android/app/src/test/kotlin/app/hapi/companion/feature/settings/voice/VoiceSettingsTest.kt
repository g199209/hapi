package app.hapi.companion.feature.settings.voice

import app.hapi.protocol.wire.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.*
import kotlin.test.*

class VoiceSettingsTest {
    private val editable = CredentialStatus(editable = true)
    private fun status() = TranscriptionCredentials(openai = editable, elevenlabs = editable, deepgram = editable, groq = editable,
        openaiCompatible = CompatibleCredentialStatus(baseUrlEditable = true, modelEditable = true, apiKey = editable))
    @Test fun `each provider saves only its key with whitespace trimmed`() {
        for (p in DictationProvider.entries.filter { it != DictationProvider.Compatible }) {
            val update = transcriptionUpdate(p, status(), " key123 ", "ignored", "ignored", false)
            assertEquals(setOf(p.wire), update.keys)
            assertEquals("key123", update[p.wire]!!.jsonPrimitive.content)
        }
    }
    @Test fun `empty key preserves stored value and clear alone sends null`() {
        assertTrue(transcriptionUpdate(DictationProvider.OpenAI, status(), " ", "", "", false).isEmpty())
        assertEquals(JsonNull, transcriptionUpdate(DictationProvider.OpenAI, status(), "", "", "", true)["openai"])
    }
    @Test fun `compatible optional key is omitted while local url and model are saved`() {
        val update = transcriptionUpdate(DictationProvider.Compatible, status(), "", " http://lan:8000/v1 ", " whisper ", false)["openaiCompatible"]!!.jsonObject
        assertEquals(setOf("baseUrl", "model"), update.keys)
        assertEquals("http://lan:8000/v1", update["baseUrl"]!!.jsonPrimitive.content)
        assertEquals("whisper", update["model"]!!.jsonPrimitive.content)
    }
    @Test fun `clear omits environment locked fields and preserves unrelated providers`() {
        val mixed = status().copy(openai = CredentialStatus(configured = true, source = "env"),
            openaiCompatible = status().openaiCompatible.copy(baseUrlEditable = false, apiKey = CredentialStatus(configured = true, source = "env")))
        assertTrue(transcriptionUpdate(DictationProvider.OpenAI, mixed, "overwrite", "", "", false).isEmpty())
        val body = transcriptionUpdate(DictationProvider.Compatible, mixed, "", "", "", true)
        assertEquals(setOf("openaiCompatible"), body.keys)
        assertEquals(setOf("model"), body["openaiCompatible"]!!.jsonObject.keys)
        assertEquals(JsonNull, body["openaiCompatible"]!!.jsonObject["model"])
    }
    @Test fun `error keeps status retry succeeds and double save is coalesced`() = runTest {
        val pending = CompletableDeferred<TranscriptionCredentials>()
        var loads = 0; var updates = 0
        val api = object : VoiceSettingsGateway {
            override suspend fun load(): TranscriptionCredentials { if (++loads == 1) error("offline"); return status() }
            override suspend fun update(body: JsonObject): TranscriptionCredentials { updates++; return pending.await() }
        }
        val vm = VoiceSettingsViewModel(api, backgroundScope)
        runCurrent(); assertEquals("offline", vm.state.value.error)
        vm.refresh(); runCurrent(); assertNotNull(vm.state.value.status)
        vm.save(DictationProvider.OpenAI, "new", "", "")
        vm.save(DictationProvider.Groq, "second", "", "")
        runCurrent(); assertEquals(1, updates); assertTrue(vm.state.value.busy)
        pending.complete(status().copy(openai = CredentialStatus(configured = true, source = "settings", hint = "••••new", editable = true)))
        runCurrent(); assertEquals(1, vm.state.value.savedRevision); assertFalse(vm.state.value.busy)
        assertTrue(vm.state.value.status!!.openai.configured)
    }
    @Test fun `failed save retains masked status for retry`() = runTest {
        var fail = true
        val api = object : VoiceSettingsGateway {
            override suspend fun load() = status()
            override suspend fun update(body: JsonObject): TranscriptionCredentials { if (fail) error("HTTP 409 env lock"); return status() }
        }
        val vm = VoiceSettingsViewModel(api, backgroundScope); runCurrent()
        vm.save(DictationProvider.OpenAI, "secret", "", ""); runCurrent()
        assertEquals("HTTP 409 env lock", vm.state.value.error); assertEquals(0, vm.state.value.savedRevision)
        fail = false; vm.save(DictationProvider.OpenAI, "secret", "", ""); runCurrent()
        assertNull(vm.state.value.error); assertEquals(1, vm.state.value.savedRevision)
    }
}
