package dev.bridge.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.bridge.assistant.core.OAuthProtocol
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
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
import java.math.BigInteger
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val RESOURCE = "https://api.openai.com/v1"
private const val AUTH = "https://auth.openai.com/api/accounts/authorize"
private const val TOKEN = "https://auth.openai.com/api/accounts/oauth/token"
private const val JWKS = "https://auth.openai.com/.well-known/jwks.json"
private const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"

class ChatGptAuth(private val context: Context) {
    private val prefs = context.getSharedPreferences("auth_metadata", Context.MODE_PRIVATE)
    private val crypto = LocalTokenVault(context)
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val rng = SecureRandom()
    private val attemptActive = AtomicBoolean(false)
    @Volatile private var activeListener: ServerSocket? = null
    @Volatile private var closed = false

    val accountLabel: String
        get() = crypto.load()?.optString("email")?.takeIf { it.isNotBlank() } ?: "Not connected"

    fun signOut(finished: (Boolean) -> Unit = {}) {
        val session = try { crypto.load() } catch (_: Exception) { null }
        crypto.clear()
        try {
            io.execute {
                val revoked = try { session?.let { revoke(it) } ?: true } catch (_: Exception) { false }
                handler.post { if (!closed) finished(revoked) }
            }
        } catch (_: Exception) {
            finished(false)
        }
    }

    fun close() {
        closed = true
        activeListener?.let { try { it.close() } catch (_: Exception) { } }
        activeListener = null
        io.shutdownNow()
    }

    fun begin(activity: Activity, finished: (Result<String>) -> Unit) {
        if (closed) { finished(Result.failure(IllegalStateException("Sign-in session is closed"))); return }
        if (!attemptActive.compareAndSet(false, true)) {
            finished(Result.failure(IllegalStateException("Sign-in is already in progress")))
            return
        }
        val delivered = AtomicBoolean(false)
        val complete: (Result<String>) -> Unit = { result ->
            handler.post {
                if (!closed && delivered.compareAndSet(false, true)) {
                    activeListener = null
                    attemptActive.set(false)
                    finished(result)
                }
            }
        }
        val state = random(32)
        val nonce = random(32)
        val verifier = random(48)
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
        val host = prefs.getString("host_id", null) ?: "urn:uuid:${UUID.randomUUID()}".also {
            prefs.edit().putString("host_id", it).apply()
        }
        val existing = prefs.getString("client_id", null)
        val listener = try {
            ServerSocket().apply {
                reuseAddress = false
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
                soTimeout = 180_000
            }
        } catch (e: Exception) {
            attemptActive.set(false)
            complete(Result.failure(e)); return
        }
        activeListener = listener
        val redirect = "http://127.0.0.1:${listener.localPort}/auth/callback"
        val fields = linkedMapOf(
            "client_id" to (existing ?: "dynamic_agent_client"),
            "response_type" to "code",
            "redirect_uri" to redirect,
            "scope" to SCOPES,
            "resource" to RESOURCE,
            "state" to state,
            "nonce" to nonce,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "ext_agent_host_id" to host
        )
        if (existing == null) fields["agent_name_hint"] = "Chabi"
        else {
            crypto.load()?.let { previous ->
                previous.optString("id_token").takeIf { it.isNotBlank() }?.let { fields["id_token_hint"] = it }
                previous.optString("email").takeIf { it.isNotBlank() }?.let { fields["login_hint"] = it }
            }
        }
        val url = "$AUTH?${encode(fields)}"
        io.execute {
            try {
                val callback = awaitCallback(listener)
                require(callback.getQueryParameter("state")?.let {
                    MessageDigest.isEqual(it.toByteArray(), state.toByteArray())
                } == true) { "OAuth state mismatch" }
                callback.getQueryParameter("error")?.let { error("Authorization failed: $it") }
                val code = callback.getQueryParameter("code") ?: error("Authorization code missing")
                val issued = OAuthProtocol.issuedClientId(existing, callback.getQueryParameter("client_id"))
                val exchanged = postForm(TOKEN, mapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to issued,
                    "code" to code,
                    "code_verifier" to verifier,
                    "redirect_uri" to redirect,
                    "resource" to RESOURCE
                ))
                val identity = verifyIdToken(exchanged.getString("id_token"), issued, nonce)
                val grantedScope = exchanged.optString("scope")
                require(OAuthProtocol.grantsChatGptPlan(grantedScope)) {
                    "ChatGPT plan usage permission was not granted"
                }
                val previous = crypto.load()
                if (previous != null && previous.optString("client_id") == issued) {
                    require(previous.optString("subject") == identity.getString("sub")) { "Account identity changed" }
                }
                val session = JSONObject().put("subject", identity.getString("sub"))
                    .put("email", identity.optString("email", ""))
                    .put("client_id", issued)
                    .put("access_token", exchanged.getString("access_token"))
                    .put("refresh_token", exchanged.optString("refresh_token", ""))
                    .put("id_token", exchanged.getString("id_token"))
                    .put("scope", grantedScope)
                    .put("saved_at", System.currentTimeMillis())
                    .put("expires_in", exchanged.optLong("expires_in", 3600L))
                crypto.save(session)
                prefs.edit().putString("client_id", issued).apply()
                complete(Result.success(accountLabel))
            } catch (e: Exception) {
                complete(Result.failure(e))
            } finally {
                try { listener.close() } catch (_: Exception) { }
                if (activeListener === listener) activeListener = null
            }
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: Exception) {
            listener.close()
            complete(Result.failure(e))
        }
    }

    fun accessToken(): String {
        val session = crypto.load() ?: error("Sign in with ChatGPT first")
        require(session.getString("scope").split(' ').contains("chatgpt.tokens.use.direct")) { "Plan permission is missing" }
        val age = System.currentTimeMillis() - session.getLong("saved_at")
        if (age < (session.getLong("expires_in") - 120L) * 1000L) {
            return session.getString("access_token")
        }
        val refresh = session.optString("refresh_token")
        require(refresh.isNotBlank()) { "Session expired. Sign in again." }
        val response = postForm(TOKEN, mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refresh,
            "client_id" to session.getString("client_id"),
            "resource" to RESOURCE
        ))
        val newId = response.optString("id_token")
        if (newId.isNotBlank()) {
            val verified = verifyIdToken(newId, session.getString("client_id"), null)
            require(verified.getString("sub") == session.getString("subject")) { "Refresh account mismatch" }
            session.put("id_token", newId)
        }
        val grantedScopes = response.optString("scope").ifBlank { session.getString("scope") }
        require(OAuthProtocol.grantsChatGptPlan(grantedScopes)) { "Plan access revoked" }
        val rotatedRefreshToken = response.optString("refresh_token").ifBlank { refresh }
        session.put("scope", grantedScopes)
            .put("access_token", response.getString("access_token"))
            .put("refresh_token", rotatedRefreshToken)
            .put("expires_in", response.optLong("expires_in", 3600L))
            .put("saved_at", System.currentTimeMillis())
        crypto.save(session)
        return session.getString("access_token")
    }

    private fun awaitCallback(listener: ServerSocket): Uri {
        val deadline = System.nanoTime() + 180_000_000_000L
        var rejectedRequests = 0
        while (true) {
            val remainingMillis = ((deadline - System.nanoTime()) / 1_000_000).toInt()
            if (remainingMillis <= 0) error("OAuth callback timed out")
            listener.soTimeout = remainingMillis.coerceAtLeast(1)
            listener.accept().use { socket ->
                socket.soTimeout = 4000
                val request = readRequestLine(socket.getInputStream()) ?: return@use
                val path = request.split(' ').getOrNull(1) ?: return@use
                val matched = path.substringBefore('?') == "/auth/callback"
                val payload = if (matched) "Authentication received. Return to Chabi." else "Not found"
                val body = payload.toByteArray(StandardCharsets.UTF_8)
                val status = if (matched) "200 OK" else "404 Not Found"
                socket.getOutputStream().write((
                    "HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                ).toByteArray(StandardCharsets.US_ASCII) + body)
                socket.getOutputStream().flush()
                if (matched) return Uri.parse("http://127.0.0.1$path")
                if (++rejectedRequests >= 8) error("Too many invalid OAuth callback requests")
            }
        }
    }

    private fun readRequestLine(input: java.io.InputStream): String? {
        val line = ByteArrayOutputStream()
        while (line.size() <= 8192) {
            val value = input.read()
            if (value < 0 || value == '\n'.code) break
            if (value != '\r'.code) line.write(value)
        }
        require(line.size() <= 8192) { "OAuth callback request is too large" }
        return line.toString("US-ASCII").takeIf { it.isNotBlank() }
    }

    private fun revoke(session: JSONObject): Boolean {
        val clientId = session.optString("client_id").takeIf { it.isNotBlank() } ?: return false
        val token = session.optString("refresh_token").ifBlank { session.optString("access_token") }
        if (token.isBlank()) return true
        val metadata = getJson("https://auth.openai.com/.well-known/openid-configuration")
        val endpoint = metadata.optString("revocation_endpoint")
        if (!endpoint.startsWith("https://")) return false
        val fields = linkedMapOf("token" to token, "client_id" to clientId)
        if (session.optString("refresh_token").isNotBlank()) fields["token_type_hint"] = "refresh_token"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(encode(fields).toByteArray(StandardCharsets.UTF_8)) }
            connection.responseCode in 200..299
        } finally { connection.disconnect() }
    }

    private fun verifyIdToken(token: String, clientId: String, nonce: String?): JSONObject {
        val parts = token.split('.')
        require(parts.size == 3) { "Invalid ID token" }
        val header = JSONObject(String(unb64(parts[0]), StandardCharsets.UTF_8))
        require(header.optString("alg") == "RS256") { "Unsupported ID token algorithm" }
        val kid = header.getString("kid")
        val keyset = getJson(JWKS).getJSONArray("keys")
        val jwk = (0 until keyset.length()).map { keyset.getJSONObject(it) }
            .firstOrNull { it.optString("kid") == kid && it.optString("kty") == "RSA" }
            ?: error("Signing key not found")
        val spec = RSAPublicKeySpec(BigInteger(1, unb64(jwk.getString("n"))),
            BigInteger(1, unb64(jwk.getString("e"))))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(spec)
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(publicKey)
        signature.update("${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII))
        require(signature.verify(unb64(parts[2]))) { "Bad ID token signature" }
        val claims = JSONObject(String(unb64(parts[1]), StandardCharsets.UTF_8))
        require(claims.optString("iss") == "https://auth.openai.com") { "Wrong issuer" }
        val aud = claims.opt("aud")
        require((aud is String && aud == clientId) ||
            (aud is org.json.JSONArray && (0 until aud.length()).any { aud.optString(it) == clientId })) {
            "Wrong audience"
        }
        val now = System.currentTimeMillis() / 1000
        require(claims.getLong("exp") > now && claims.optLong("nbf", 0) <= now) { "Expired ID token" }
        require(claims.optString("sub").isNotBlank()) { "Missing subject" }
        if (nonce != null) require(claims.optString("nonce") == nonce) { "Nonce mismatch" }
        return claims
    }

    private fun random(count: Int): String = ByteArray(count).also { rng.nextBytes(it) }.let { b64(it) }
    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun unb64(value: String): ByteArray = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP)
    private fun encode(fields: Map<String, String>): String = fields.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }
    private fun postForm(url: String, fields: Map<String, String>): JSONObject {
        return Http.post(url, encode(fields).toByteArray(StandardCharsets.UTF_8),
            "application/x-www-form-urlencoded", null)
    }
    private fun getJson(url: String): JSONObject = Http.get(url, null)
}

private class LocalTokenVault(context: Context) {
    private val prefs = context.getSharedPreferences("encrypted_session", Context.MODE_PRIVATE)
    private val alias = "gpt_bridge_oauth"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
    fun save(record: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(record.toString().toByteArray(StandardCharsets.UTF_8))
        val payload = cipher.iv + encrypted
        check(prefs.edit().putString("payload", Base64.encodeToString(payload, Base64.NO_WRAP)).commit()) { "Cannot persist OAuth session" }
    }
    fun load(): JSONObject? {
        val blob = prefs.getString("payload", null) ?: return null
        val bytes = Base64.decode(blob, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8))
    }
    fun clear() { prefs.edit().remove("payload").commit() }
}

object Http {
    fun get(url: String, bearer: String?): JSONObject = request("GET", url, null, null, bearer)
    fun post(url: String, body: ByteArray, type: String, bearer: String?): JSONObject =
        request("POST", url, body, type, bearer)
    private fun request(method: String, url: String, body: ByteArray?, type: String?, bearer: String?): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = 45000
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", type)
                connection.outputStream.use { it.write(body) }
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= 1_048_576) { "HTTP response exceeds size limit" }
                    output.write(buffer, 0, count)
                }
                String(output.toByteArray(), StandardCharsets.UTF_8)
            } ?: ""
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}: ${text.take(300)}")
            return JSONObject(text)
        } finally { connection.disconnect() }
    }
}
