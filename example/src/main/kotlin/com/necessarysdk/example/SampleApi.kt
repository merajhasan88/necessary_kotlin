// The sample backend's own API (backend/README.md): how an app signs what it
// sends to its own backend -- a request proof on every request, and the
// person's approval on a sensitive one -- and requests made wrong on purpose,
// which a backend must refuse.
package com.necessarysdk.example

import com.necessarysdk.DeviceTrust
import com.necessarysdk.RequestProof
import com.necessarysdk.signRequest
import org.json.JSONObject

class SampleApi(private val trust: DeviceTrust) {
    /** The sample's account session for this installation, after [signIn]. */
    var account: String? = null

    private companion object {
        const val ECHO = "/v1/demo/echo"
        const val SENSITIVE = "/v1/demo/sensitive"
        const val ECHO_BODY = "{\"hello\":\"backend\"}"
        const val PAYEE_BODY = "{\"payee\":\"payee-7\"}"
    }

    private val session: String get() = account ?: throw IllegalStateException("Sign in first.")

    /**
     * The sample's sign-in, under the installation session from start(): an
     * account session for this installation. The sample makes the account at
     * its first sign-in.
     */
    suspend fun signIn(handle: String, password: String) {
        val proof = trust.signRequest(
            method = "POST",
            path = "/v1/demo/sign-in",
            bearer = trust.installationSession!!,
            body = Show.json(linkedMapOf("handle" to handle, "password" to password)),
        )
        account = trust.send(proof)["account_session"] as String
    }

    /** A request with its request proof alone: only this installation's key can sign it, so the session is useless on another phone. */
    suspend fun protectedRequest(): Map<String, Any?> =
        trust.send(trust.signRequest(method = "POST", path = ECHO, bearer = session, body = ECHO_BODY))

    /**
     * The sensitive request. With [stepUp] the person approves it with the
     * screen lock -- the phone asks, showing the reason; with [appPin] the
     * backend checks the PIN for this request first. With neither the backend
     * refuses it.
     */
    suspend fun sensitive(stepUp: Boolean = false, appPin: String? = null): Map<String, Any?> = trust.send(
        trust.signRequest(
            method = "POST",
            path = SENSITIVE,
            bearer = session,
            body = PAYEE_BODY,
            stepUp = stepUp,
            reason = "Approve the new payee",
            appPin = appPin,
        ),
    )

    /** A new step-up key, with the person's password: after the screen lock was removed, say, when approvals fail with STEPUP_KEY_INVALIDATED. */
    suspend fun reenrolStepUp(password: String): Map<String, Any?> =
        trust.reenrolStepUp(session = session, reauthentication = mapOf("password" to password))

    /** Sets the account's app PIN, with the person's password. */
    suspend fun setAppPin(pin: String, password: String): Map<String, Any?> =
        trust.setAppPin(session = session, pin = pin, reauthentication = mapOf("password" to password))

    // --- Requests a backend must refuse.

    private suspend fun signedEcho(): RequestProof =
        trust.signRequest(method = "POST", path = ECHO, bearer = session, body = ECHO_BODY)

    /** One signed request sent twice: the second is a replay. */
    suspend fun replay(): Map<String, Any?> {
        val proof = signedEcho()
        try {
            trust.send(proof)
        } catch (error: com.necessarysdk.ApiException) {
            // The first must go through, or the second tests nothing.
            throw IllegalStateException("The first send was refused: ${error.statusCode} ${error.code}.")
        }
        return trust.send(proof)
    }

    /** A signed request whose body is changed on the way. */
    suspend fun changedBody(): Map<String, Any?> =
        trust.client.sendSigned(signedEcho(), encodedBody = "{\"hello\":\"changed\"}")

    /** A signed request sent to another path. */
    suspend fun otherPath(): Map<String, Any?> = trust.client.sendSigned(signedEcho(), path = SENSITIVE)

    /** A request proof signed ten minutes ago. */
    suspend fun staleProof(): Map<String, Any?> = trust.send(
        signRequest(
            identity = trust.loadIdentity(),
            method = "POST",
            path = ECHO,
            bearerToken = session,
            encodedBody = ECHO_BODY,
            timestamp = System.currentTimeMillis() / 1000 - 600,
        ),
    )

    /** This phone's account session and installation, to try on another phone. */
    fun shareSession(): String =
        Show.json(linkedMapOf("session" to session, "installation_id" to trust.identity!!.installationId))

    /** A session [shared] from another phone, used here: this phone's key signs a request naming that phone's installation. */
    suspend fun sessionFromAnotherPhone(shared: String): Map<String, Any?> {
        val copied = JSONObject(shared.trim())
        return trust.send(
            signRequest(
                identity = trust.loadIdentity(),
                method = "POST",
                path = ECHO,
                bearerToken = copied.getString("session"),
                encodedBody = ECHO_BODY,
                installationId = copied.getString("installation_id"),
            ),
        )
    }
}

/** What the example shows of a value: JSON, as a backend sent it. */
object Show {
    fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (key, item) -> json(key.toString()) + ":" + json(item) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
        else -> value.toString()
    }
}
