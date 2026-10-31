package com.necessarysdk

/**
 * Protects the app's window while a secret is typed: no screenshots,
 * recordings or casts of the window, other apps' windows not drawn over it
 * (Android 12 and later), and touches that passed through another app's
 * window dropped.
 *
 * Calls nest: each `protect(true)` needs its `protect(false)`, and the
 * window is put back as it was -- a window the app keeps secure itself stays
 * secure -- when the last one ends. Other apps' overlays, which no public
 * call reads, are shown again then. Returns what the window has now: `held`,
 * `secure`, `overlays_hidden` and `obscured_touches_dropped`; nothing when
 * there is no window to protect.
 */
object SecureScreen {
    /** The window's native side; tests replace it. */
    internal var native: ScreenNative? = null

    suspend fun protect(on: Boolean): Map<String, Any?> {
        val screen = native ?: AndroidNative.installed() ?: return emptyMap()
        return try {
            screen.protect(on)
        } catch (_: NativeCallException) {
            emptyMap()
        }
    }
}
