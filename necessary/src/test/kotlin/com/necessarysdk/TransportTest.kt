package com.necessarysdk

import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

/** HttpTransport: every call that got no answer is an ApiException, the one failure an app handles. */
class TransportTest {
    private fun failing(failure: IOException) = HttpTransport(
        "https://backend.example",
        releaseBuild = false,
        client = OkHttpClient.Builder().addInterceptor(Interceptor { throw failure }).build(),
    )

    @Test
    fun aRefusedCertificateIsAnApiExceptionNotARawTlsError() = runBlocking {
        expectThrows<ApiException> {
            failing(SSLHandshakeException("CERTIFICATE_VERIFY_FAILED")).send("POST", "/v1/installations/register")
        }
        Unit
    }

    /**
     * A caller on the main thread must never read the socket: Android refuses
     * it (NetworkOnMainThreadException, no IOException), and an answer that
     * arrives after its headers would reach the app as that raw failure.
     */
    @Test
    fun theAnswerIsReadOffTheCallersThread() {
        val readOn = AtomicReference<Thread>()
        val client = OkHttpClient.Builder().addInterceptor(
            Interceptor { chain ->
                val text = Buffer().writeUtf8("{\"done\":true}")
                val source = object : Source by text {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        readOn.set(Thread.currentThread())
                        return text.read(sink, byteCount)
                    }
                }
                val body = object : ResponseBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength(): Long = -1
                    override fun source(): BufferedSource = source.buffer()
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body).build()
            },
        ).build()
        val transport = HttpTransport("https://backend.example", releaseBuild = false, client = client)
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { caller ->
            runBlocking(caller) {
                assertEquals(true, transport.send("POST", "/v1/demo/echo")["done"])
                assertNotSame(Thread.currentThread(), readOn.get())
            }
        }
    }

    @Test
    fun soAreASocketAndAClientFailure() = runBlocking {
        expectThrows<ApiException> { failing(ConnectException("refused")).send("POST", "/v1/installations/register") }
        expectThrows<ApiException> { failing(IOException("closed")).send("POST", "/v1/installations/register") }
        Unit
    }
}
