package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.assistant.mcp.ToolAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationExportTest {
    @Test fun includesEntireConversationInTimeOrder() {
        val turns = listOf(
            TurnRecord(interactionId = "later", at = 2_000, question = "Second question", answer = "Second answer",
                conversation = "conversation-1", source = TurnSource.PHONE),
            TurnRecord(interactionId = "earlier", at = 1_000, question = "First question", answer = "First answer",
                conversation = "conversation-1", source = TurnSource.GLASSES),
        )
        val text = exportConversationForChatGpt(turns)
        assertTrue(text.contains("Conversation exported from Visor"))
        assertTrue(text.contains("Use it as context for this ChatGPT chat"))
        assertTrue(text.contains("via glasses"))
        assertTrue(text.contains("via phone"))
        assertTrue(text.indexOf("First question") < text.indexOf("Second question"))
        assertTrue(text.contains("First answer"))
        assertTrue(text.contains("Second answer"))
    }

    @Test fun photosAreNotSharedAndToolActivityIsClearlyHistorical() {
        val text = exportConversationForChatGpt(listOf(
            TurnRecord(interactionId = "photo", at = 1_000, question = "What is this?", answer = "A coffee mug",
                image = "/private/visor/secret.jpg",
                actions = listOf(ToolAction("Home", "Checked a sensor", true))),
        ))
        assertTrue(text.contains("not included in this text export"))
        assertTrue(text.contains("historical; do not repeat automatically"))
        assertTrue(text.contains("Home: Checked a sensor (completed)"))
        assertFalse(text.contains("/private/visor/secret.jpg"))
    }

    @Test fun missingAnswerDoesNotClaimTheAssistantAnswered() {
        val result = exportConversationForChatGpt(listOf(
            TurnRecord(interactionId = "failed", at = 1_000, question = "Failed", problem = "some internal error"),
            TurnRecord(interactionId = "pending", at = 2_000, question = "Pending"),
        ))
        assertTrue(result.contains("[No recorded answer]"))
        assertTrue(result.contains("[Answer still pending]"))
        assertFalse(result.contains("some internal error"))
    }

    @Test fun refusesEmptyConversation() {
        val exception = runCatching { exportConversationForChatGpt(emptyList()) }.exceptionOrNull()
        assertTrue(exception is IllegalArgumentException)
    }
}
