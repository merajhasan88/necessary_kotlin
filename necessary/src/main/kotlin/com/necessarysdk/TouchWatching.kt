package com.necessarysdk

/**
 * When the app's screen is watched for the accessibility measurement
 * (collector 12): only while the app enables it, on the activity in front,
 * from when it comes to the front until it leaves. At most one watch at a
 * time, so a callback is never wrapped twice. Turned off, the watch is taken
 * off and what was counted is dropped. On the main thread.
 */
internal class TouchWatching<A : Any>(
    /** Starts watching an activity; answers how to stop, or null where it cannot watch. */
    private val attach: (A) -> (() -> Unit)?,
    private val discard: () -> Unit,
) {
    private var wanted = false
    private var detach: (() -> Unit)? = null

    /** The activity watched now, if any. */
    var watched: A? = null
        private set

    /** On or off, with [current] the activity in front, if any. */
    fun want(on: Boolean, current: A?) {
        if (on == wanted) return
        wanted = on
        if (on) {
            if (current != null) watch(current)
        } else {
            stop()
            discard()
        }
    }

    fun resumed(activity: A) {
        if (wanted && watched !== activity) watch(activity)
    }

    fun left(activity: A) {
        if (watched === activity) stop()
    }

    private fun watch(activity: A) {
        stop()
        val stop = attach(activity) ?: return
        detach = stop
        watched = activity
    }

    private fun stop() {
        detach?.invoke()
        detach = null
        watched = null
    }
}
