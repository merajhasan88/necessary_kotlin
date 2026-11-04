package com.necessarysdk

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Sends one request to your backend and answers its JSON object. A refusal
 * is thrown as an [ApiException] with the backend's `code`, `message` and
 * `details` (the public guide, section 4, Errors).
 */
interface DeviceTrustTransport {
    /** [encodedBody] is sent exactly as given: it is what a request proof hashes. A GET sends no body. */
    suspend fun send(
        method: String,
        path: String,
        encodedBody: String? = null,
        bearerToken: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Map<String, Any?>

    fun close()
}

/**
 * JSON over HTTPS under [baseUrl]. A release build ([releaseBuild]: the app is
 * not debuggable) refuses any other scheme, so a mistyped endpoint fails
 * loudly instead of sending tokens in the clear. Each call has [timeoutMillis].
 */
class HttpTransport(
    val baseUrl: String,
    private val releaseBuild: Boolean,
    val timeoutMillis: Long = 15_000,
    client: OkHttpClient? = null,
) : DeviceTrustTransport {
    private val client: OkHttpClient = (client ?: OkHttpClient.Builder().dispatcher(concurrent()).build()).newBuilder()
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var closed = false

    private val base: String
        get() {
            if (baseUrl.isEmpty()) {
                throw ApiException("No API base URL was set for the backend.", code = "api_base_url_missing")
            }
            if (releaseBuild && !baseUrl.lowercase().startsWith("https://")) {
                throw ApiException(
                    "The API base URL must be an https:// URL in a release build.",
                    code = "api_base_url_insecure",
                )
            }
            return baseUrl.removeSuffix("/")
        }

    override suspend fun send(
        method: String,
        path: String,
        encodedBody: String?,
        bearerToken: String?,
        headers: Map<String, String>,
    ): Map<String, Any?> {
        if (closed) throw ApiException("HTTP connection failed: the transport is closed.")
        val builder = try {
            Request.Builder().url("$base$path")
        } catch (error: IllegalArgumentException) {
            throw ApiException("HTTP connection failed: ${error.message}", cause = error)
        }
        builder.header("Accept", "application/json")
        builder.header("Content-Type", "application/json")
        if (bearerToken != null) builder.header("Authorization", "Bearer $bearerToken")
        for ((name, value) in headers) builder.header(name, value)
        val body = if (method != "GET") (encodedBody ?: "{}").toRequestBody(JSON_TYPE) else null
        try {
            builder.method(method, body)
        } catch (error: IllegalArgumentException) {
            throw ApiException("HTTP connection failed: ${error.message}", cause = error)
        }

        val (status, text) = try {
            client.newCall(builder.build()).answer()
        } catch (error: InterruptedIOException) {
            throw ApiException("The server did not respond before the timeout.", cause = error)
        } catch (error: IOException) {
            throw ApiException("Network connection failed: ${error.message}", cause = error)
        }

        var decoded: Map<String, Any?> = emptyMap()
        if (text.isNotBlank()) {
            decoded = try {
                Json.decodeObject(text)
            } catch (error: IllegalArgumentException) {
                throw ApiException("The server returned a non-JSON response.", statusCode = status, cause = error)
            }
        }
        if (status < 200 || status >= 300) {
            val refusal = decoded["error"].asObject()
            throw ApiException(
                refusal?.get("message") as? String ?: "The server rejected the request ($status).",
                statusCode = status,
                code = refusal?.get("code") as? String,
                details = refusal?.get("details").asObject(),
            )
        }
        return decoded
    }

    override fun close() {
        closed = true
        client.dispatcher.cancelAll()
    }

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Calls at once, to one backend: OkHttp's default sends five and queues
         * the rest, so a sixth waits for one of the five -- for its whole timeout
         * when the backend holds them (a backend checking app PINs one attempt at
         * a time does). Every call is sent as it is made.
         */
        fun concurrent() = Dispatcher().apply {
            maxRequests = 256
            maxRequestsPerHost = 256
        }
    }
}

/**
 * The call's status and body, abandoned when the caller is. The body is read
 * here, on the HTTP client's own thread: the caller may be on the main thread,
 * where Android refuses to read a socket.
 */
private suspend fun Call.answer(): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            val answer = try {
                response.use { it.code to (it.body?.string() ?: "") }
            } catch (error: IOException) {
                continuation.resumeWithException(error)
                return
            }
            continuation.resume(answer)
        }

        override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
    })
}
