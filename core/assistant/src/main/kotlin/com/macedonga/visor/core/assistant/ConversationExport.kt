package com.macedonga.visor.core.assistant

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A plain-text transcript to paste into a new ChatGPT conversation, not a ChatGPT history sync.
 * Intentionally excludes local photo paths, tokens, and other private application metadata.
 */
fun exportConversationForChatGpt(turns: List<TurnRecord>): String {
    require(turns.isNotEmpty()) { "A conversation must have at least one question to export" }
    val clock = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", Locale.ENGLISH)
        .withZone(ZoneId.systemDefault())
    val sorted = turns.sortedBy { it.at }
    return buildString {
        appendLine("Conversation exported from Visor")
        appendLine("This is a transcript from my smart glasses. Use it as context for this ChatGPT chat.")
        appendLine("It has not been automatically synced to ChatGPT. Any photos are not included.")
        for (turn in sorted) {
            val origin = when (turn.source) {
                TurnSource.GLASSES -> "via glasses"
                TurnSource.PHONE -> "via phone"
                TurnSource.LIVE_AI -> "during Live AI"
            }
            appendLine()
            appendLine("You (" + origin + ", " + clock.format(Instant.ofEpochMilli(turn.at)) + "):")
            appendLine(turn.question.ifBlank { "[No question text recorded]" }.trim())
            if (turn.image.isNotEmpty()) appendLine("[A photo was provided in Visor; not included in this text export]")
            appendLine()
            appendLine("Visor assistant:")
            when {
                turn.answer.isNotBlank() -> appendLine(turn.answer.trim())
                turn.problem.isNotBlank() -> appendLine("[No recorded answer]")
                else -> appendLine("[Answer still pending]")
            }
            if (turn.actions.isNotEmpty()) {
                appendLine("Visor tool activity (historical; do not repeat automatically):")
                turn.actions.forEach {
                    appendLine("- " + it.server + ": " + it.what + " (" + (if (it.ok) "completed" else "failed") + ")")
                }
            }
        }
    }.trimEnd()
}
