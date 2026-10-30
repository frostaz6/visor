package com.macedonga.visor.chatgpt

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.macedonga.visor.core.assistant.ChatGptTokenSource
import com.macedonga.visor.core.assistant.ModelInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Official Sign in with ChatGPT OSS flow. No password or API key ever enters Visor.
 * The single account profile is encrypted using the Android Keystore; the host ID is not a secret.
 * Documentation: https://developers.openai.com/siwc/token-sharing-open-source/sign-in
 */
@Singleton
class ChatGptAccount @Inject constructor(@ApplicationContext private val context: Context) : ChatGptTokenSource {
    private val prefs = context.getSharedPreferences("chatgpt_plan_auth", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(status(read()))
    val state = _state.asStateFlow()

    data class Status(val email: String?, val signedIn: Boolean, val sharing: Boolean)

    private fun status(profile: JSONObject?): Status = Status(
        profile?.optString("email")?.ifBlank { null },
        profile != null,
        profile?.optString("scope")?.split(" ")?.contains("chatgpt.tokens.use.direct") == true,
    )

    fun signOut() {
        prefs.edit().remove(PROFILE).commit()
        _state.value = Status(null, false, false)
    }

    private fun hostId(): String {
        prefs.getString(HOST, null)?.let { return it }
        val id = "urn:uuid:" + UUID.randomUUID().toString()
        check(prefs.edit().putString(HOST, id).commit()) { "Could not save the device identifier" }
        return id
    }

    /** Browser-based PKCE authorization; callback listener accepts only this device's loopback connection. */
    suspend fun signIn() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val before = read()
            val existingClient = before?.optString("client_id")?.takeIf { it.startsWith("oaiapp_") }
            val state = randomToken()
            val nonce = randomToken()
            val verifier = randomToken()
            val challenge = urlSafe(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))

            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 180_000
                val redirect = "http://127.0.0.1:" + server.localPort + "/auth/callback"
                val uri = Uri.parse(AUTH).buildUpon()
                    .appendQueryParameter("client_id", existingClient ?: "dynamic_agent_client")
                    .appendQueryParameter("ext_agent_host_id", hostId())
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("redirect_uri", redirect)
                    .appendQueryParameter("scope", SCOPES)
                    .appendQueryParameter("resource", RESOURCE)
                    .appendQueryParameter("state", state)
                    .appendQueryParameter("nonce", nonce)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .appendQueryParameter("code_challenge", challenge)
                    .apply { if (existingClient == null) appendQueryParameter("agent_name_hint", "Visor") }
                    .build()
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                // The callback is made by the browser on the phone; no custom URL scheme is needed.
                val callback = server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val first = socket.getInputStream().bufferedReader().readLine().orEmpty()
                    val path = first.split(" ").getOrNull(1).orEmpty()
                    val validPath = path.startsWith("/auth/callback?")
                    val params = if (validPath) Uri.parse("http://127.0.0.1" + path) else Uri.EMPTY
                    val message = if (validPath) "Authorization received. Return to Visor." else "Invalid callback."
                    val body = "<html><body>" + message + "</body></html>"
                    socket.getOutputStream().write((
                        "HTTP/1.1 " + (if (validPath) "200 OK" else "400 Bad Request") +
                        "\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: " +
                        body.toByteArray().size + "\r\n\r\n" + body
                    ).toByteArray())
                    check(validPath) { "Unexpected sign-in callback" }
                    params
                }
                check(callback.getQueryParameter("state") == state) { "Sign-in state mismatch" }
                check(callback.getQueryParameter("error") == null) { "Sign-in was declined or cancelled" }
                val code = callback.getQueryParameter("code")?.takeIf(String::isNotBlank)
                    ?: error("No authorization code returned")
                val returnedClient = callback.getQueryParameter("client_id")
                val clientId = if (existingClient != null) {
                    check(returnedClient == null || returnedClient == existingClient) { "Client registration changed" }
                    existingClient
                } else {
                    returnedClient?.takeIf { it.startsWith("oaiapp_") } ?: error("Client registration incomplete")
                }

                val token = postForm(TOKEN, mapOf(
                    "grant_type" to "authorization_code", "client_id" to clientId, "code" to code,
                    "code_verifier" to verifier, "redirect_uri" to redirect, "resource" to RESOURCE,
                ))
                val identity = verifyIdentity(token.getString("id_token"), clientId, nonce)
                if (existingClient != null) {
                    check(identity.getString("sub") == before?.optString("sub")) { "Different ChatGPT account" }
                }
                val scope = token.optString("scope")
                check(scope.split(" ").contains("chatgpt.tokens.use.direct")) {
                    "ChatGPT plan usage was not authorized; approve it during sign-in"
                }
                val profile = JSONObject()
                    .put("client_id", clientId)
                    .put("sub", identity.getString("sub"))
                    .put("email", identity.optString("email"))
                    .put("id_token", token.getString("id_token"))
                    .put("access_token", token.getString("access_token"))
                    .put("refresh_token", token.getString("refresh_token"))
                    .put("scope", scope)
                    .put("expires_at", System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000)
                save(profile)
            }
        }
    }

    override suspend fun accessToken(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val profile = read() ?: error("Sign in with ChatGPT in Assistant settings")
            check(status(profile).sharing) { "ChatGPT plan sharing is not enabled" }
            if (profile.optLong("expires_at") > System.currentTimeMillis() + 60_000) {
                return@withLock profile.getString("access_token")
            }
            // Refresh tokens rotate. Persist the replacement and access token together before using either.
            val renewed = postForm(TOKEN, mapOf(
                "grant_type" to "refresh_token",
                "client_id" to profile.getString("client_id"),
                "refresh_token" to profile.getString("refresh_token"),
                "resource" to RESOURCE,
            ))
            check(renewed.has("refresh_token")) { "No replacement refresh token received" }
            val updated = JSONObject(profile.toString())
                .put("access_token", renewed.getString("access_token"))
                .put("refresh_token", renewed.getString("refresh_token"))
                .put("expires_at", System.currentTimeMillis() + renewed.optLong("expires_in", 3600) * 1000)
            if (renewed.has("scope")) updated.put("scope", renewed.getString("scope"))
            save(updated)
            updated.getString("access_token")
        }
    }

    suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val connection = URL(RESOURCE + "/models").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Authorization", "Bearer " + accessToken())
            val code = connection.responseCode
            check(code in 200..299) { "ChatGPT model list failed (HTTP " + code + ")" }
            val array = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("models")
            (0 until (array?.length() ?: 0)).mapNotNull { i ->
                val model = array!!.optJSONObject(i) ?: return@mapNotNull null
                if (model.optString("visibility") != "list") return@mapNotNull null
                val slug = model.optString("slug").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                ModelInfo(slug, model.optString("display_name", slug), 0)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyIdentity(jwt: String, clientId: String, nonce: String): JSONObject {
        val parts = jwt.split(".")
        check(parts.size == 3) { "Invalid identity token" }
        val header = JSONObject(String(Base64.decode(parts[0], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
        check(header.optString("alg") == "RS256") { "Unexpected identity token algorithm" }
        val claims = JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
        check(claims.optString("iss") == "https://auth.openai.com") { "Untrusted identity issuer" }
        val aud = claims.opt("aud")
        val audienceMatches = if (aud is String) aud == clientId else
            (aud is org.json.JSONArray && (0 until aud.length()).any { aud.optString(it) == clientId })
        check(audienceMatches) { "Identity token audience mismatch" }
        check(claims.optString("nonce") == nonce) { "Identity token nonce mismatch" }
        check(claims.optLong("exp") > System.currentTimeMillis() / 1000) { "Identity token expired" }
        check(claims.optString("sub").isNotEmpty()) { "Missing account identity" }

        val jwksConnection = URL("https://auth.openai.com/.well-known/jwks.json").openConnection() as HttpURLConnection
        val jwks = try {
            jwksConnection.connectTimeout = 5_000
            jwksConnection.readTimeout = 15_000
            check(jwksConnection.responseCode == 200) { "Could not verify the sign-in signature" }
            JSONObject(jwksConnection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            jwksConnection.disconnect()
        }
        val keys = jwks.getJSONArray("keys")
        val key = (0 until keys.length()).map { keys.getJSONObject(it) }.firstOrNull {
            it.optString("kid") == header.optString("kid") && it.optString("kty") == "RSA"
        } ?: error("Identity signing key unavailable")
        val n = BigInteger(1, Base64.decode(key.getString("n"), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        val e = BigInteger(1, Base64.decode(key.getString("e"), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update((parts[0] + "." + parts[1]).toByteArray(StandardCharsets.US_ASCII))
        check(verifier.verify(Base64.decode(parts[2], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))) {
            "Identity signature verification failed"
        }
        return claims
    }

    private fun postForm(url: String, params: Map<String, String>): JSONObject {
        val http = URL(url).openConnection() as HttpURLConnection
        try {
            http.requestMethod = "POST"
            http.doOutput = true
            http.connectTimeout = 5_000
            http.readTimeout = 20_000
            http.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = params.entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
            }
            http.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            check(http.responseCode in 200..299) { "ChatGPT authorization failed (HTTP " + http.responseCode + ")" }
            return JSONObject(http.inputStream.bufferedReader().use { it.readText() })
        } finally {
            http.disconnect()
        }
    }

    private fun urlSafe(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun randomToken() = ByteArray(32).also(SecureRandom()::nextBytes).let(::urlSafe)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun read(): JSONObject? = prefs.getString(PROFILE, null)?.let { value ->
        runCatching {
            val bytes = Base64.decode(value, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8))
        }.getOrNull()
    }

    private fun save(profile: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(profile.toString().toByteArray(StandardCharsets.UTF_8))
        check(prefs.edit().putString(PROFILE, Base64.encodeToString(data, Base64.NO_WRAP)).commit()) {
            "Could not securely save the ChatGPT account"
        }
        _state.value = status(profile)
    }

    private companion object {
        const val AUTH = "https://auth.openai.com/api/accounts/authorize"
        const val TOKEN = "https://auth.openai.com/api/accounts/oauth/token"
        const val RESOURCE = "https://api.openai.com/v1"
        const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
        const val PROFILE = "profile_encrypted"
        const val HOST = "host_id"
        const val KEY_ALIAS = "visor_chatgpt_plan_v1"
    }
}
