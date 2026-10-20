package app.hapi.companion.feature.chat

import app.hapi.protocol.wire.CodexRestoreProgress
import org.junit.Assert.*
import org.junit.Test

class SessionRestoreTest {
    @Test fun `only known phase totals yield a fraction`() {
        assertNull(restoreFraction(CodexRestoreProgress("history", 1, 100)))
        assertNull(restoreFraction(CodexRestoreProgress("syncing", 1, 1, 1)))
        assertNull(restoreFraction(CodexRestoreProgress("children", 1, 0, 0)))
        assertNull(restoreFraction(CodexRestoreProgress("projecting", 1, 11, 10)))
        assertEquals(0.25f, restoreFraction(CodexRestoreProgress("projecting", 1, 25, 100)))
        assertEquals(1f, restoreFraction(CodexRestoreProgress("children", 1, 3, 3)))
    }

    @Test fun `restoring takes precedence over cached idle and inactive`() {
        val header = ChatHeaderUi("Title", null, active = true, thinking = false, restoring = true)
        assertEquals(ChatSessionStatus.Restoring, chatSessionStatus(header, false))
        assertEquals(ChatSessionStatus.Restoring, chatSessionStatus(header.copy(active = false), false))
        assertEquals(ChatSessionStatus.Reconnecting, chatSessionStatus(header, true))
        assertEquals(ChatSessionStatus.Idle, chatSessionStatus(header.copy(restoring = false), false))
    }
}
