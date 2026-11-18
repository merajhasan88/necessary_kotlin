package com.necessarysdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TouchWatchingTest {
    private class Screen(val name: String)

    private class Log {
        val events = mutableListOf<String>()
        var discarded = 0
        fun watching() = TouchWatching<Screen>(
            attach = { screen -> events.add("attach ${screen.name}"); { events.add("detach ${screen.name}") } },
            discard = { discarded++ },
        )
    }

    @Test fun turnedOnWithAScreenInFrontWatchesItAtOnce() {
        val log = Log()
        val watching = log.watching()
        val main = Screen("main")
        watching.want(true, main)
        assertSame(main, watching.watched)
        assertEquals(listOf("attach main"), log.events)
    }

    @Test fun nothingIsWatchedUntilTurnedOn() {
        val log = Log()
        val watching = log.watching()
        watching.resumed(Screen("main"))
        assertNull(watching.watched)
        assertEquals(emptyList<String>(), log.events)
    }

    @Test fun aScreenComingBackIsNeverWrappedTwice() {
        val log = Log()
        val watching = log.watching()
        val main = Screen("main")
        watching.want(true, main)
        watching.resumed(main)
        watching.want(true, main)
        assertEquals(listOf("attach main"), log.events)
        // Left and back: one watch off, one on.
        watching.left(main)
        watching.resumed(main)
        assertEquals(listOf("attach main", "detach main", "attach main"), log.events)
    }

    @Test fun anotherScreenInFrontMovesTheWatch() {
        val log = Log()
        val watching = log.watching()
        val main = Screen("main")
        val other = Screen("other")
        watching.want(true, main)
        watching.resumed(other)
        // A screen leaving that is no longer watched changes nothing.
        watching.left(main)
        assertSame(other, watching.watched)
        assertEquals(listOf("attach main", "detach main", "attach other"), log.events)
    }

    @Test fun turnedOffTheWatchComesOffAndTheCountsAreDropped() {
        val log = Log()
        val watching = log.watching()
        val main = Screen("main")
        watching.want(true, main)
        watching.want(false, main)
        assertNull(watching.watched)
        assertEquals(1, log.discarded)
        assertEquals(listOf("attach main", "detach main"), log.events)
        watching.resumed(main)
        assertEquals(listOf("attach main", "detach main"), log.events)
    }

    @Test fun aScreenThatCannotBeWatchedIsNotHeld() {
        val watching = TouchWatching<Screen>(attach = { null }, discard = {})
        watching.want(true, Screen("main"))
        assertNull(watching.watched)
    }
}
