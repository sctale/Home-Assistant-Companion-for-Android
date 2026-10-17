package io.homeassistant.companion.android.common.sensors

import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Collapses a burst of sensor sweeps into "the one in flight + at most one trailing run".
 *
 * Why this exists: one full sweep touches every `SensorManager`, reads every sensor row and posts to every
 * server, so it takes noticeably longer than the interval between two system broadcasts. A charger being
 * plugged in, a power-save flip and a Wi-Fi state change can land within the same second and each request
 * a sweep of its own - N concurrent sweeps doing the same collection, with only the last one's result
 * actually differing.
 *
 * Coalescing keeps every guarantee that matters for detection: any request that arrives while a sweep is
 * running is remembered and served by a trailing run, and that run re-reads the live values, so no state
 * change is dropped or served from stale data. Sequential requests still each get their own sweep - the
 * only thing removed is *redundant work performed concurrently*, exactly like the dedupe gate does for
 * identical location reports.
 */
internal object SensorSweepCoalescer {

    private val lock = Any()
    private var running = false
    private var pendingWhileRunning = false

    /** Number of sweeps actually executed. Exposed for tests and for the debug log. */
    @Volatile
    var executed = 0
        private set

    /**
     * Runs [sweep], or returns immediately if an identical sweep is already in flight (in which case this
     * caller is covered by the guaranteed trailing run).
     */
    suspend fun run(sweep: suspend () -> Unit) {
        val startNow = synchronized(lock) {
            if (running) {
                pendingWhileRunning = true
                Timber.v("Sweep already running, trailing run scheduled")
                false
            } else {
                running = true
                true
            }
        }
        if (!startNow) return

        try {
            while (true) {
                executed++
                sweep()
                val runAgain = synchronized(lock) {
                    val again = pendingWhileRunning
                    pendingWhileRunning = false
                    if (!again) running = false
                    again
                }
                if (!runAgain) return
                Timber.d("Catching up one sweep requested while the previous one was running")
            }
        } catch (e: CancellationException) {
            resetState()
            throw e
        } catch (e: Exception) {
            resetState()
            throw e
        }
    }

    /**
     * A sweep that dies with an unexpected error must not wedge the coalescer: without this, `running`
     * would stay true forever and every later broadcast would silently do nothing.
     */
    private fun resetState() {
        synchronized(lock) {
            running = false
            pendingWhileRunning = false
        }
    }

    /** Test seam. */
    fun resetForTests() {
        resetState()
        executed = 0
    }
}
