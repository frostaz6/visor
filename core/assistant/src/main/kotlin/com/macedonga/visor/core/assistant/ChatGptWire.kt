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

    /** The Responses route accepts hosted web_search alongside Visor's namespaced client tools. */
    fun tools(localTools: JSONArray, webSearch: Boolean): JSONArray = JSONArray().apply {
        if (webSearch) put(JSONObject().put("type", "web_search").put("search_context_size", "medium"))
        if (localTools.length() > 0) put(namespace(localTools).getJSONObject(0))
    }

    /**
     * Privacy-safe diagnostic for subscription sharing failures.
     * Never include the raw request, bearer token, response body, or user prompts.
     */
    fun httpFailure(status: Int, body: String, requestId: String?, request: JSONObject): String {
        val serverError = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val code = serverError?.optString("code").orEmpty()
            .replace(Regex("[^A-Za-z0-9_\\-]"), "").take(100).ifBlank { "not_returned" }
        val detail = serverError?.optString("message").orEmpty()
            .replace(Regex("[\\r\\n]+"), " ").take(220).ifBlank { "No explanation returned" }
        val safeId = requestId.orEmpty().replace(Regex("[^A-Za-z0-9_\\-]"), "").take(128)
            .ifBlank { "not_returned" }
        val tools = request.optJSONArray("tools")
        val hasWebSearch = (0 until (tools?.length() ?: 0)).any {
            tools?.optJSONObject(it)?.optString("type") == "web_search"
        }
        return buildString {
            append("ChatGPT HTTP ").append(status)
            append("\nCode: ").append(code)
            append("\nMessage: ").append(detail)
            append("\nRequest ID: ").append(safeId)
            append("\nModel: ").append(request.optString("model", "not_selected").take(80))
            append("\nStream: ").append(request.optBoolean("stream"))
            append("; Store: ").append(request.optBoolean("store", false))
            append("\nWeb search: ").append(if (hasWebSearch) "on" else "off")
            append("; Tool groups: ").append(tools?.length() ?: 0)
            append("\nShare the request ID with OpenAI Support (never your tokens).")
        }
    }

    /** Citations belong in the phone diary, not in speech sent to the glasses. */
    fun webSources(response: JSONObject): List<WebSource> {
        val output = response.optJSONArray("output") ?: return emptyList()
        val sources = LinkedHashMap<String, WebSource>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val annotations = content.optJSONObject(j)?.optJSONArray("annotations") ?: continue
                for (k in 0 until annotations.length()) {
                    val cite = annotations.optJSONObject(k) ?: continue
                    if (cite.optString("type") != "url_citation") continue
                    val url = cite.optString("url").trim()
                    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: continue
                    if (uri.scheme !in listOf("https", "http") || uri.host.isNullOrBlank()) continue
                    val title = cite.optString("title").ifBlank { uri.host }
                    sources.putIfAbsent(url, WebSource(title.take(140), url))
                    if (sources.size >= 8) return sources.values.toList()
                }
            }
        }
        return sources.values.toList()
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
