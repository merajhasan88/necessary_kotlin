package com.necessarysdk

/** [block] throws a [T]; anything else, or no failure, fails the test. Answers what it threw. */
internal suspend inline fun <reified T : Throwable> expectThrows(reason: String = "", noinline block: suspend () -> Any?): T {
    val outcome = try {
        block()
    } catch (error: Throwable) {
        if (error is T) return error
        throw AssertionError("$reason: expected ${T::class.simpleName}, got $error", error)
    }
    throw AssertionError("$reason: expected ${T::class.simpleName}, got a result: $outcome")
}

/** The JSON object a base64url payload carries. */
internal fun decoded(payload: String): Map<String, Any?> =
    Json.decodeObject(String(base64UrlDecode(payload), Charsets.UTF_8))

/** A storage in memory, as the phone's secure storage keeps values. */
internal class MemoryStorage(initial: Map<String, String> = emptyMap()) : SecureStorage {
    val values = java.util.concurrent.ConcurrentHashMap(initial)
    override suspend fun read(key: String): String? = values[key]
    override suspend fun write(key: String, value: String) {
        values[key] = value
    }
    override suspend fun delete(key: String) {
        values.remove(key)
    }
}
