package app.hapi.companion.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatSessionStatusTest {
    private val header = ChatHeaderUi("Session", "Codex · GMF-PC", active = true, thinking = false)

    @Test fun distinguishesWorkingFromConnectedIdle() {
        assertEquals(ChatSessionStatus.Idle, chatSessionStatus(header, false))
        assertEquals(ChatSessionStatus.Working, chatSessionStatus(header.copy(thinking = true), false))
    }

    @Test fun pendingActionsTakePriorityOverTheBusyFlag() {
        val busy = header.copy(thinking = true)
        assertEquals(ChatSessionStatus.NeedsInput, chatSessionStatus(busy.copy(pendingRequestKinds = listOf("input")), false))
        assertEquals(ChatSessionStatus.NeedsApproval, chatSessionStatus(busy.copy(pendingRequestKinds = listOf("permission")), false))
        assertEquals(ChatSessionStatus.NeedsAttention, chatSessionStatus(busy.copy(pendingRequestKinds = listOf("permission", "input")), false))
    }

    @Test fun backgroundActivityDoesNotLookIdle() {
        assertEquals(ChatSessionStatus.Background, chatSessionStatus(header.copy(backgroundTaskCount = 2), false))
        assertEquals(ChatSessionStatus.Working, chatSessionStatus(header.copy(backgroundTaskCount = 2, thinking = true), false))
    }

    @Test fun inactiveSessionsDoNotShowStaleActivityOrRequests() {
        assertEquals(ChatSessionStatus.Inactive, chatSessionStatus(header.copy(active = false, thinking = true,
            pendingRequestKinds = listOf("input"), backgroundTaskCount = 1), false))
    }

    @Test fun archivedLabelRequiresAnInactiveSession() {
        assertEquals(ChatSessionStatus.Archived, chatSessionStatus(header.copy(active = false, lifecycleState = "archived"), false))
        assertEquals(ChatSessionStatus.Idle, chatSessionStatus(header.copy(lifecycleState = "archived"), false))
    }

    @Test fun reconnectingOverridesCachedWorkingAndPendingStates() {
        assertEquals(ChatSessionStatus.Reconnecting, chatSessionStatus(header.copy(thinking = true, pendingRequestKinds = listOf("permission")), true))
    }

    @Test fun unknownSessionIsLoadingRatherThanInactiveAndMessageErrorsDoNotReplaceKnownActivity() {
        assertEquals(ChatSessionStatus.Loading, chatSessionStatus(header.copy(active = false, hasSessionInfo = false), false))
        assertEquals(ChatSessionStatus.LoadFailed, chatSessionStatus(header.copy(hasSessionInfo = false), false, loadFailed = true))
        assertEquals(ChatSessionStatus.Working, chatSessionStatus(header.copy(thinking = true), false, loadFailed = true))
    }
}
