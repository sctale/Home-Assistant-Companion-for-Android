package io.homeassistant.companion.android.sensors

import android.location.Location
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Covers the coalescing of high-accuracy fix requests.
 *
 * A single accurate fix chases the GPS chip for up to `MAX_ACCURATE_FIX_ATTEMPTS × ONE_FIX_TIMEOUT` with a
 * partial wake lock held. Two things can ask for it at nearly the same moment — a zone `enter` immediately
 * followed by an `exit` (boundary flapping), and a server-initiated "locate me" landing on top of either.
 * Without coalescing each one ran its own full session: double the radio and GPS time for the same answer.
 *
 * Coalescing is not a reduction in accuracy: whoever joins still receives a fix measured seconds ago.
 */
class AccurateFixCoalescingTest {

    @AfterEach
    fun tearDown() {
        LocationRuntimeState.reset()
    }

    @Test
    fun `Given two concurrent accurate requests then only one GPS session runs`() = runTest {
        var sessions = 0

        val results = coroutineScope {
            val first = async {
                LocationRuntimeState.accurateFix(FRESH_WINDOW_MS) {
                    sessions++
                    delay(50)
                    null
                }
            }
            val second = async {
                LocationRuntimeState.accurateFix(FRESH_WINDOW_MS) {
                    sessions++
                    delay(50)
                    null
                }
            }
            first.await() to second.await()
        }

        assertEquals(1, sessions, "the second request must join the session already in flight")
        assertNull(results.first)
        assertNull(results.second)
    }

    @Test
    fun `Given a fix already chased when another request arrives inside the window then it is reused`() = runTest {
        var sessions = 0
        val fix = Location("gps")

        val first = LocationRuntimeState.accurateFix(FRESH_WINDOW_MS) {
            sessions++
            fix
        }
        val second = LocationRuntimeState.accurateFix(FRESH_WINDOW_MS) {
            sessions++
            fix
        }

        assertEquals(1, sessions)
        assertNotNull(second)
        assertSame(first, second)
    }

    @Test
    fun `Given a failed session when another request arrives then GPS is not hammered either`() = runTest {
        var sessions = 0

        LocationRuntimeState.accurateFix(FRESH_WINDOW_MS) {
            sessions++
            null
        }
        val again = LocationRuntimeState.accurateFix(FRESH_WINDOW_MS) {
            sessions++
            null
        }

        assertEquals(
            1,
            sessions,
            "a fix that could not be obtained is also not retried instantly — that is how a poor indoor " +
                "signal used to burn 150s of GPS per attempt pair",
        )
        assertNull(again)
    }

    private companion object {
        const val FRESH_WINDOW_MS = 15_000L
    }
}
