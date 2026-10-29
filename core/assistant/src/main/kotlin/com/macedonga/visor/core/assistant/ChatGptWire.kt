package com.macedonga.visor.core.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * Response wire contract for Sign in with ChatGPT (non-stored, streamed Responses API).
 * The decoder refuses truncated streams and failure events instead of pretending a reply succeeded.
 */
internal object ChatGptWire {
    fun namespace(tools: JSONArray): JSONArray {
        if (tools.length() == 0) return JSONArray()
        return JSONArray().put(
            JSONObject().put("type", "namespace")
                .put("name", "visor")
                .put("description", "Trusted local functions from Visor and its enabled MCP servers.")
                .put("tools", tools),
        )
    }

    fun completed(lines: Sequence<String>): JSONObject {
        var completed: JSONObject? = null
        for (line in lines) {
            if (!line.startsWith("data: ")) continue
            val data = line.removePrefix("data: ").trim()
            if (data == "[DONE]") break
            val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
            when (event.optString("type")) {
                "response.completed" -> completed = event.getJSONObject("response")
                "response.failed" -> {
                    val error = event.optJSONObject("response")?.optJSONObject("error")
                    throw IllegalStateException(
                        "ChatGPT response failed: " + (error?.optString("code")?.ifEmpty { error.optString("message") }
                            ?: "unknown"),
                    )
                }
                "response.incomplete" -> {
                    val reason = event.optJSONObject("response")?.optJSONObject("incomplete_details")
                        ?.optString("reason").orEmpty().ifEmpty { "unknown" }
                    throw IllegalStateException("ChatGPT response incomplete: $reason")
                }
                "error" -> {
                    val error = event.optJSONObject("error")
                    throw IllegalStateException(
                        "ChatGPT stream error: " + (error?.optString("code")?.ifEmpty { error.optString("message") }
                            ?: event.optString("message", "unknown")),
                    )
                }
            }
        }
        return completed ?: error("ChatGPT response stream ended without response.completed")
    }
}
