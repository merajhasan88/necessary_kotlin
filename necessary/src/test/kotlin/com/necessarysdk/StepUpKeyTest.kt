package com.necessarysdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the app replaces a step-up key its options do not accept
 * (replacesStepUpKey). Under a per-use passcode policy, which Android 9
 * cannot make a key for, the registration that follows each re-enrolment must
 * not replace the key the server has just bound, or step-up stays unavailable.
 */
class StepUpKeyTest {
    private fun key(mode: String = "windowed", created: Boolean = false) = StepUpKeyMetadata(
        publicKey = mapOf("kty" to "EC", "crv" to "P-256", "alg" to "ES256", "x" to "x", "y" to "y"),
        factor = "passcode",
        mode = mode,
        windowSeconds = if (mode == "windowed") 30 else 0,
        securityLevel = "tee",
        hardwareBacked = true,
        created = created,
    )

    private val perUse = StepUpOptions()

    @Test
    fun aKnownInstallationKeepsAKeyTheOptionsWantMoreOf() =
        assertFalse(replacesStepUpKey(key(), perUse, replaceMismatched = false, knownInstallation = true))

    @Test
    fun aReenrolmentReplacesItAndBindsTheNewOne() =
        assertTrue(replacesStepUpKey(key(), perUse, replaceMismatched = true, knownInstallation = true))

    @Test
    fun anInstallationsFirstRegistrationReplacesIt() =
        assertTrue(replacesStepUpKey(key(), perUse, replaceMismatched = false, knownInstallation = false))

    @Test
    fun aKeyJustMadeOrOneTheOptionsAcceptIsNeverReplaced() {
        for (known in listOf(true, false)) {
            assertFalse(replacesStepUpKey(key(created = true), perUse, replaceMismatched = true, knownInstallation = known))
            assertFalse(replacesStepUpKey(key(mode = "per_use"), perUse, replaceMismatched = true, knownInstallation = known))
        }
    }
}
