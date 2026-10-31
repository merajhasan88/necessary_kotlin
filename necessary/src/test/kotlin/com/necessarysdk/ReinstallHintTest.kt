package com.necessarysdk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reinstall hint's bytes: what a backend keys and stores to link
 * reinstalls, so a phone's hint must not change when the SDK's code does.
 * The digest is the one the reference computed apart, with Python's hashlib.
 */
class ReinstallHintTest {
    private class Identifier(private val value: String?) : InstallationKeyNative by NoNative {
        override suspend fun reinstallHintIdentifier(): String =
            value ?: throw NativeCallException("NATIVE_INTEGRITY_ERROR", "no identifier")
    }

    @Test
    fun androidIdTrimmedAndInLowerCase() = runBlocking {
        val hint = ReinstallHintReader(Identifier(" 9774D56D682E549C ")).read()
        assertEquals("android_id_sha256", hint?.kind)
        assertEquals("fe8339ee3d9adfd02d18c670d45a27b139a58455c395e2972a14529d76316d24", hint?.value)
    }

    @Test
    fun noIdentifierNoHint() = runBlocking {
        assertNull(ReinstallHintReader(Identifier("")).read())
        // A failed read is no hint, and never stops the installation.
        assertNull(ReinstallHintReader(Identifier(null)).read())
    }
}
