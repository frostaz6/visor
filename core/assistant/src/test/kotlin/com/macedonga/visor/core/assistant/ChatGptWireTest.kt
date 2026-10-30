package com.macedonga.visor.core.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptWireTest {
    @Test fun completedStreamReturnsReply() {
        val response = ChatGptWire.completed(
            sequenceOf(
                "event: response.created",
                "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\"}}",
                "",
                "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}]}}",
                "data: [DONE]",
            ),
        )
        assertEquals("resp_1", response.getString("id"))
        assertEquals("hello", response.getJSONArray("output").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test fun failureEventIsNotReportedAsSuccess() {
        expectFailure("response failed") {
            ChatGptWire.completed(sequenceOf(
                "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}",
            ))
        }
    }

    @Test fun incompleteEventIsNotReportedAsSuccess() {
        expectFailure("incomplete") {
            ChatGptWire.completed(sequenceOf(
                "data: {\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}",
            ))
        }
    }

    @Test fun truncatedStreamIsRejected() {
        expectFailure("without response.completed") {
            ChatGptWire.completed(sequenceOf(
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}",
                "data: [DONE]",
            ))
        }
    }

    @Test fun functionToolsUseSupportedNamespace() {
        val tool = JSONObject().put("type", "function").put("name", "garage_status")
            .put("description", "Check garage door")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))
            .put("strict", false)
        val requestTools = ChatGptWire.namespace(JSONArray().put(tool))
        assertEquals(1, requestTools.length())
        val namespace = requestTools.getJSONObject(0)
        assertEquals("namespace", namespace.getString("type"))
        assertEquals("visor", namespace.getString("name"))
        assertEquals("garage_status", namespace.getJSONArray("tools").getJSONObject(0).getString("name"))
        assertEquals(0, ChatGptWire.namespace(JSONArray()).length())
    }

    @Test fun combinesTextSegmentsInsteadOfKeepingOnlyTheLastOne() {
        val content = JSONArray()
            .put(JSONObject().put("type", "output_text").put("text", "Hello"))
            .put(JSONObject().put("type", "output_text").put("text", "world"))
            .put(JSONObject().put("type", "refusal").put("refusal", "ignored"))
        val output = JSONArray().put(JSONObject().put("type", "reasoning"))
            .put(JSONObject().put("type", "message").put("role", "assistant").put("content", content))
        val response = JSONObject().put("status", "completed").put("output", output)
        assertEquals("Hello\\nworld".replace("\\n", "\n"), ChatGptWire.assistantText(response))
        assertTrue(ChatGptWire.shape(response).contains("reasoning"))
        assertTrue(ChatGptWire.shape(response).contains("message(output_text,output_text,refusal)"))
    }

    @Test fun reasoningOnlyCompletionIsNotMistakenForAnAnswer() {
        val response = JSONObject().put("status", "completed")
            .put("output", JSONArray().put(JSONObject().put("type", "reasoning")))
        assertEquals("", ChatGptWire.assistantText(response))
        assertTrue(ChatGptWire.shape(response).contains("reasoning"))
        assertTrue(!ChatGptWire.shape(response).contains("private"))
    }

    private fun expectFailure(expected: String, action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        assertTrue("Expected failure containing $expected, got $error", error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains(expected, ignoreCase = true))
    }
}
