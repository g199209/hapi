package app.hapi.companion.feature.newsession

import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.HapiApp
import app.hapi.protocol.wire.HapiJson
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in, reversible MRU seeding for signed Release verification against real Git paths.
 * Does not create/import native threads or send any messages. */
class WorktreeDirectoryPrefsLiveTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HapiApp
    private val backup get() = File(app.filesDir, "worktree-directory-prefs-backup.json")

    @Test fun prepare() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val machine = args.getString("worktreeMachine")
        val paths = args.getString("worktreePaths")?.split('|').orEmpty()
        assumeTrue("Explicit machine and real Git paths required", machine != null && paths.isNotEmpty())
        assertFalse("Restore a previous fixture before preparing another", backup.exists())
        val prefs = app.appGraph.newSessionPrefs
        val stored = prefs.readPrefs()
        val draft = prefs.readDraft()
        backup.writeText(buildJsonObject {
            put("prefs", HapiJson.encodeToJsonElement(NewSessionPrefsData.serializer(), stored))
            put("draft", draft?.let { HapiJson.encodeToJsonElement(NewSessionForm.serializer(), it) } ?: JsonNull)
        }.toString())
        prefs.writePrefs(stored.copy(lastMachineId = machine, recentPaths = stored.recentPaths + (machine!! to paths)))
        prefs.clearDraft()
    }

    @Test fun restore() = runBlocking {
        assumeTrue("No worktree MRU fixture to restore", backup.exists())
        val json = HapiJson.parseToJsonElement(backup.readText()).jsonObject
        val prefs = app.appGraph.newSessionPrefs
        prefs.writePrefs(HapiJson.decodeFromJsonElement(NewSessionPrefsData.serializer(), json.getValue("prefs")))
        val draft = json["draft"]
        if (draft == null || draft == JsonNull) prefs.clearDraft()
        else prefs.writeDraft(HapiJson.decodeFromJsonElement(NewSessionForm.serializer(), draft))
        assertTrue(backup.delete())
    }
}
