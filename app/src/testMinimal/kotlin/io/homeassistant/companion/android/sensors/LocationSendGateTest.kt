package io.homeassistant.companion.android.sensors

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Covers the rules that decide whether a fix reaches Home Assistant.
 *
 * A previous revision of this project commented several of these out and then advanced the
 * "last sent" bookkeeping *before* the network call rather than after it. The combination produced
 * two failures users reported as "the location froze": the same coordinates re-posted on every
 * heartbeat (which makes `device_tracker.last_changed` useless and bloats the recorder), and a single
 * offline moment suppressing every subsequent fix until the next distinct position. The cases below
 * pin both directions: what must be skipped, and what must not be.
 */
class LocationSendGateTest {

    private val freshFixTime = NOW - 10_000L

    @Test
    fun `Given a fix from the future beyond tolerance when evaluated then it is skipped as future`() {
        assertEquals(
            LocationSendGate.Skip.FUTURE,
            gate(
                locationTime = NOW + LocationSendGate.FUTURE_TOLERANCE_MS + 10_000L,
            ),
        )
    }

    @Test
    fun `Given a fix slightly ahead of the device clock when evaluated then it is accepted`() {
        // Small clock skew between the device and the GPS receiver is normal; rejecting it would
        // discard good fixes.
        assertNull(gate(locationTime = NOW + 1_000L))
    }

    @Test
    fun `Given a fix older than the last successful send when evaluated then it is skipped as not latest`() {
        assertEquals(
            LocationSendGate.Skip.NOT_LATEST,
            gate(locationTime = NOW - 60_000L, lastSend = NOW - 30_000L),
        )
    }

    @Test
    fun `Given a stale cached fix when evaluated then it is skipped as old`() {
        assertEquals(
            LocationSendGate.Skip.OLD,
            gate(locationTime = NOW - LocationSendGate.FRESH_WINDOW_MS - 1_000L),
        )
    }

    @Test
    fun `Given unchanged coordinates within the duplicate window when evaluated then it is skipped`() {
        assertEquals(
            LocationSendGate.Skip.DUPLICATE,
            gate(
                lastSend = NOW - 60_000L,
                lastUpdateString = PAYLOAD,
                updateLocationString = PAYLOAD,
            ),
        )
    }

    @Test
    fun `Given unchanged coordinates past the duplicate window when evaluated then it is sent again`() {
        // The periodic re-send of the same position is what tells Home Assistant the device is still
        // alive at that place.
        assertNull(
            gate(
                lastSend = NOW - LocationSendGate.DUPLICATE_WINDOW_MS - 1_000L,
                lastUpdateString = PAYLOAD,
                updateLocationString = PAYLOAD,
            ),
        )
    }

    @Test
    fun `Given a new position within the debounce window when evaluated then it is skipped`() {
        assertEquals(
            LocationSendGate.Skip.DEBOUNCE,
            gate(
                locationTime = NOW - 500L,
                lastSend = NOW - 1_000L,
                lastUpdateString = "[0.0, 0.0]",
                updateLocationString = PAYLOAD,
            ),
        )
    }

    @Test
    fun `Given a new position past the debounce window when evaluated then it is sent`() {
        assertNull(
            gate(
                locationTime = NOW - 500L,
                lastSend = NOW - LocationSendGate.DEBOUNCE_MS - 1_000L,
                lastUpdateString = "[0.0, 0.0]",
                updateLocationString = PAYLOAD,
            ),
        )
    }

    @Test
    fun `Given a zone boundary inside the debounce window when evaluated then it is sent`() {
        // "Just crossed into home" is the single most automated event in Home Assistant. Debouncing
        // it would delay an automation by up to the debounce interval for no benefit.
        assertNull(
            gate(
                locationTime = NOW - 500L,
                lastSend = NOW - 1_000L,
                lastUpdateString = "[0.0, 0.0]",
                updateLocationString = PAYLOAD,
                isGeofence = true,
            ),
        )
    }

    @Test
    fun `Given no previous send when evaluated then nothing is skipped`() {
        // Regression guard for advancing last-sent before the request is confirmed: a failed send
        // must not leave a mark that silences the next perfectly good fix.
        assertNull(gate(lastSend = 0L, lastUpdateString = null, updateLocationString = PAYLOAD))
    }

    @Test
    fun `Given a fix that is both stale and a duplicate when evaluated then stale wins`() {
        // The history screen is the only diagnostic a user can offer for "my location is wrong", so
        // the reported reason has to be the actionable one.
        assertEquals(
            LocationSendGate.Skip.OLD,
            gate(
                locationTime = NOW - LocationSendGate.FRESH_WINDOW_MS - 1_000L,
                lastUpdateString = PAYLOAD,
                updateLocationString = PAYLOAD,
            ),
        )
    }

    private fun gate(
        now: Long = NOW,
        locationTime: Long = freshFixTime,
        lastSend: Long = 0L,
        lastUpdateString: String? = null,
        updateLocationString: String = PAYLOAD,
        isGeofence: Boolean = false,
    ) = LocationSendGate.skipReason(
        now = now,
        locationTime = locationTime,
        lastSend = lastSend,
        lastUpdateString = lastUpdateString,
        updateLocationString = updateLocationString,
        isGeofence = isGeofence,
    )

    companion object {
        private const val NOW = 1_700_000_000_000L
        private const val PAYLOAD = "[31.2304, 121.4737]"
    }
}
