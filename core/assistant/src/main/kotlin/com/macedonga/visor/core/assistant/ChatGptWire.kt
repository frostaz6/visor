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

    /** Only completed assistant output_text pieces are spoken; never expose reasoning items. */
    fun assistantText(response: JSONObject): String {
        val output = response.optJSONArray("output") ?: return response.optString("_visor_stream_text")
        val pieces = ArrayList<String>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message" || item.optString("role", "assistant") != "assistant") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") {
                    val value = part.optString("text")
                    if (value.isNotBlank()) pieces.add(value)
                }
            }
        }
        return pieces.joinToString("\n").trim().ifEmpty { response.optString("_visor_stream_text") }
    }

    /** Structural diagnostics only: never log questions, response text, tool arguments or credentials. */
    fun shape(response: JSONObject): String {
        val output = response.optJSONArray("output") ?: return "no output array"
        val kinds = (0 until output.length()).map { i ->
            val item = output.optJSONObject(i)
            when (item?.optString("type")) {
                "message" -> {
                    val content = item.optJSONArray("content")
                    val types = (0 until (content?.length() ?: 0)).mapNotNull { j ->
                        content?.optJSONObject(j)?.optString("type")?.takeIf(String::isNotEmpty)
                    }
                    "message(" + types.joinToString(",") + ")"
                }
                else -> item?.optString("type")?.ifEmpty { "unnamed" } ?: "non-object"
            }
        }
        return "status=" + response.optString("status", "unknown") + ", output=" + kinds.joinToString(",").take(180)
    }

    fun completed(lines: Sequence<String>): JSONObject {
        var completed: JSONObject? = null
        val streamedText = StringBuilder()
        for (line in lines) {
            if (!line.startsWith("data: ")) continue
            val data = line.removePrefix("data: ").trim()
            if (data == "[DONE]") break
            val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
            when (event.optString("type")) {
                "response.output_text.delta" -> streamedText.append(event.optString("delta"))
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
        val response = completed ?: error("ChatGPT response stream ended without response.completed")
        // In unusual streams, text deltas may be available even if the final payload omits its text.
        if (assistantText(response).isBlank() && streamedText.isNotBlank()) {
            response.put("_visor_stream_text", streamedText.toString())
        }
        return response
    }
}
