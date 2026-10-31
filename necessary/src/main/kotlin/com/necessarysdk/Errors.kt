package com.necessarysdk

/**
 * A failure of the phone's keystore or of a prompt: [code] says which --
 * `KEY_UNAVAILABLE` (Android's keystore could not be reached for the
 * installation key; no key was made over it: try again, or reset()),
 * `STEPUP_KEY_INVALIDATED` (the key can never sign again: re-enrol),
 * `STEPUP_KEY_UNAVAILABLE` (the keystore could not say whether the key is
 * usable, or could not be reached; it was kept: try again, or re-enrol),
 * `STEPUP_CANCELLED`, `STEPUP_REENROL_PENDING` and the like -- and [message]
 * says it in words, followed by everything that failed beneath it
 * (`[cause: ...]`), down to the keystore's own answer.
 */
class NativeKeyException(
    /** What failed, for the app to act on. */
    val code: String,
    /** The same, in words. */
    override val message: String,
) : Exception(message) {
    override fun toString(): String = "$message [$code]"
}

/**
 * A refusal from your backend, or a request that did not reach it: the
 * backend's `{"error": {"code", "message", "details"}}` (the guide, section
 * 4), with the HTTP status.
 */
class ApiException(
    /** The backend's message, or what went wrong on the way. */
    override val message: String,
    /** The HTTP status, when the backend answered. */
    val statusCode: Int? = null,
    /** The backend's `code`: what the app acts on. */
    val code: String? = null,
    /** The backend's `details`, such as `retry_after_seconds`. */
    val details: Map<String, Any?>? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    override fun toString(): String = message
}

/**
 * A call that DeviceTrust.reset or DeviceTrust.close ended while it ran:
 * what it found belongs to an installation that is gone, or to a client that
 * is closed, so nothing of it was kept. After a reset, call again.
 */
class InterruptedCallException(override val message: String) : Exception(message) {
    override fun toString(): String = message
}

/** What a native call answered when it failed: its code and its words, causes included. */
internal class NativeCallException(val code: String, message: String?) : Exception(message)
