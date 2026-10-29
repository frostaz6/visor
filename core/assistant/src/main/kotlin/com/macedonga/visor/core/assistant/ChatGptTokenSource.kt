package com.macedonga.visor.core.assistant

/** An authenticated ChatGPT-plan bearer token, provided by the Android account layer. */
interface ChatGptTokenSource {
    suspend fun accessToken(): String
}
