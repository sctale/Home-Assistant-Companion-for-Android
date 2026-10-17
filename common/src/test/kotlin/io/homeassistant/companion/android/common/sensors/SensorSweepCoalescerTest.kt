package io.homeassistant.companion.android.common.sensors

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Covers the sweep coalescer.
 *
 * The property that has to hold while duplicate work disappears is: **no requested sweep is ever lost**.
 * A request arriving while a sweep is in flight is absorbed into a trailing run that re-reads live values,
 * so a state change can never be reported from stale data or skipped - which is why this is power work
 * reduction and not a detection-frequency reduction.
 */
class SensorSweepCoalescerTest {

    @AfterEach
    fun tearDown() {
        SensorSweepCoalescer.resetForTests()
    }

    @Test
    fun `Given a burst arriving during a sweep then only one trailing run is added`() = runTest {
        val release = CompletableDeferred<Unit>()
        var sweeps = 0
        val job = async {
            SensorSweepCoalescer.run {
                sweeps++
                release.await()
            }
        }
        runCurrent()
        assertEquals(1, sweeps, "the first request must actually run")

        val late = List(4) {
            async {
                SensorSweepCoalescer.run {
                    sweeps++
                    release.await()
                }
            }
        }
        runCurrent()
        assertEquals(1, sweeps, "the four late requests must be absorbed, not run concurrently")

        release.complete(Unit)
        (listOf(job) + late).awaitAll()

        assertEquals(2, sweeps, "exactly one catch-up run is expected")
        assertEquals(2, SensorSweepCoalescer.executed)
    }

    @Test
    fun `Given sequential sweep requests then each one still gets its own sweep`() = runTest {
        var sweeps = 0
        SensorSweepCoalescer.run { sweeps++ }
        SensorSweepCoalescer.run { sweeps++ }
        SensorSweepCoalescer.run { sweeps++ }

        assertEquals(3, sweeps, "coalescing must not swallow events that arrive one after another")
    }

    @Test
    fun `Given a sweep that never resumes then the coalescer is released instead of blocking every later sweep`() = runTest {
        var sweeps = 0
        val first = async {
            SensorSweepCoalescer.run {
                sweeps++
                awaitCancellation()
            }
        }
        runCurrent()
        assertEquals(1, sweeps)

        advanceTimeBy(SensorSweepCoalescer.SWEEP_TIMEOUT + 1.seconds)
        runCurrent()
        first.await()

        SensorSweepCoalescer.run { sweeps++ }
        assertEquals(
            2,
            sweeps,
            "a hung sweep must not turn every later broadcast into a no-op — that would be a functional " +
                "regression dressed up as a power saving",
        )
    }

    @Test
    fun `Given a sweep that dies with an error then the next request is not blocked forever`() = runTest {
        var sweeps = 0
        try {
            SensorSweepCoalescer.run {
                sweeps++
                throw IllegalStateException("boom")
            }
        } catch (expected: IllegalStateException) {
            // 由调用方记录，这里只关心状态没有被卡住
        }

        SensorSweepCoalescer.run { sweeps++ }

        assertEquals(2, sweeps, "a crashed sweep must release the coalescer, or every later broadcast does nothing")
    }
}
