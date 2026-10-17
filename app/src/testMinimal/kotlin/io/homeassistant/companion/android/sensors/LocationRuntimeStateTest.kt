package io.homeassistant.companion.android.sensors

import io.homeassistant.companion.android.common.data.integration.Entity
import java.time.LocalDateTime
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the state that makes the location guards actually work.
 *
 * The bug these tests exist for is invisible from inside [LocationSendGate]: the pure decision function
 * was already correct and already green, yet in the shipped app every fix was still re-posted, because a
 * manifest-registered receiver is constructed fresh for each broadcast and the "what did we already
 * report" maps lived in its instance fields. `lastSend = 0` makes every gate return "send", so a phone
 * sitting still on Wi-Fi paid one webhook POST plus one full zone GET per fix per server — and never
 * emitted a single `zone_entered`.
 *
 * So these tests are about *survival*: across a new receiver instance, across a process death, and
 * across the distinction between "never recorded" and "recorded as nothing".
 */
class LocationRuntimeStateTest {

    private class FakeStore : LocationRuntimeState.Store {
        var lastSendMillis = 0L
        var lastCoordinates: String? = null
        var membership: Set<String>? = null
        var writes = 0

        override fun lastSendMillis(serverId: Int): Long = if (serverId == SERVER) lastSendMillis else 0L

        override fun lastCoordinates(serverId: Int): String? = if (serverId == SERVER) lastCoordinates else null

        override fun writeLastSend(serverId: Int, whenMillis: Long, coordinates: String) {
            if (serverId != SERVER) return
            lastSendMillis = whenMillis
            lastCoordinates = coordinates
            writes++
        }

        override fun zoneMembership(serverId: Int): Set<String>? = if (serverId == SERVER) membership else null

        override fun writeZoneMembership(serverId: Int, entityIds: Set<String>) {
            if (serverId != SERVER) return
            membership = entityIds
            writes++
        }
    }

    @AfterEach
    fun tearDown() {
        LocationRuntimeState.reset()
    }

    @Test
    fun `Given a recorded send when a new receiver instance primes then the gate still knows it`() {
        val store = FakeStore()
        LocationRuntimeState.prime(SERVER, store)
        LocationRuntimeState.recordSent(SERVER, NOW, "[31.23, 121.47]", store)

        assertEquals(NOW, LocationRuntimeState.lastSendMillis(SERVER))
        assertEquals("[31.23, 121.47]", LocationRuntimeState.lastCoordinates(SERVER))
        assertEquals(NOW, store.lastSendMillis, "the gate must be written through, not only kept in memory")
    }

    @Test
    fun `Given an identical fix after a send when the gate is consulted then it is skipped`() {
        val store = FakeStore()
        LocationRuntimeState.prime(SERVER, store)
        LocationRuntimeState.recordSent(SERVER, NOW - 60_000L, "[31.23, 121.47]", store)

        val skip = LocationSendGate.skipReason(
            now = NOW,
            locationTime = NOW - 5_000L,
            lastSend = LocationRuntimeState.lastSendMillis(SERVER),
            lastUpdateString = LocationRuntimeState.lastCoordinates(SERVER),
            updateLocationString = "[31.23, 121.47]",
            isGeofence = false,
        )

        // This is the exact pairing that used to be impossible: the state the gate needs was always empty.
        assertEquals(LocationSendGate.Skip.DUPLICATE, skip)
    }

    @Test
    fun `Given a process restart when the state primes again then the durable facts come back`() {
        val store = FakeStore()
        LocationRuntimeState.prime(SERVER, store)
        LocationRuntimeState.recordSent(SERVER, NOW - 10_000L, "[39.9, 116.4]", store)
        LocationRuntimeState.setZoneBaseline(SERVER, setOf("zone.home"), store)

        LocationRuntimeState.reset()
        assertNull(LocationRuntimeState.lastCoordinates(SERVER))

        LocationRuntimeState.prime(SERVER, store)
        assertEquals("[39.9, 116.4]", LocationRuntimeState.lastCoordinates(SERVER))
        assertEquals(setOf("zone.home"), LocationRuntimeState.zoneBaseline(SERVER))
    }

    @Test
    fun `Given nothing was ever recorded then there is no zone baseline`() {
        LocationRuntimeState.prime(SERVER, FakeStore())

        assertNull(
            LocationRuntimeState.zoneBaseline(SERVER),
            "a missing baseline must stay null: diffing against an empty set would fabricate zone_entered",
        )
    }

    @Test
    fun `Given a recorded empty membership then the baseline is empty rather than missing`() {
        val store = FakeStore()
        LocationRuntimeState.prime(SERVER, store)
        LocationRuntimeState.setZoneBaseline(SERVER, emptySet(), store)
        LocationRuntimeState.reset()
        LocationRuntimeState.prime(SERVER, store)

        assertNotNull(LocationRuntimeState.zoneBaseline(SERVER))
        assertTrue(LocationRuntimeState.zoneBaseline(SERVER).orEmpty().isEmpty())
    }

    @Test
    fun `Given a cached zone list when it is older than the ttl then it is treated as absent`() {
        LocationRuntimeState.storeZones(SERVER, listOf(zone("zone.home", 50f)), NOW)

        assertEquals(1, LocationRuntimeState.cachedZones(SERVER, NOW, TTL)?.size)
        assertNull(LocationRuntimeState.cachedZones(SERVER, NOW + TTL + 1, TTL))
        // …but a fetch failure may still fall back to it, which is what the manager asks for.
        assertEquals(1, LocationRuntimeState.cachedZones(SERVER, NOW + TTL + 1, Long.MAX_VALUE)?.size)
    }

    @Test
    fun `Given a server that is dropped then its gate is gone`() {
        val store = FakeStore()
        LocationRuntimeState.prime(SERVER, store)
        LocationRuntimeState.recordSent(SERVER, NOW, "[1.0, 2.0]", store)
        LocationRuntimeState.subscriptionSignature = "fused|900|100.0"

        LocationRuntimeState.drop(SERVER)

        assertEquals(0L, LocationRuntimeState.lastSendMillis(SERVER))
        assertNull(LocationRuntimeState.lastCoordinates(SERVER))
        assertNull(LocationRuntimeState.subscriptionSignature)
    }

    @Test
    fun `Given an unchanged alert set when it is compared then no re-registration is needed`() {
        val wanted = listOf(
            // 真实键形如 "1_zone.home"（zoneKey 用下划线）；第二条故意带 '|' 验证指纹拆不开。
            ProximityAlert("1_zone.home", 39.9, 116.4, 50f),
            ProximityAlert("2_zone.ba|nan", 31.2, 121.4, 200f),
        ).sortedWith(compareBy({ it.radius }, { it.key }))
        val persisted = wanted.map { it.spec() }.mapNotNull { ProximityAlert.fromSpec(it) }

        assertEquals(wanted, persisted, "the fingerprint must round-trip, otherwise alerts churn on every broadcast")
        assertEquals("2_zone.ba|nan", ProximityAlert.keyFromSpec(wanted.last().spec()))
        assertNull(ProximityAlert.fromSpec("nope"))
    }

    private fun zone(entityId: String, radius: Float) = Entity(
        entityId = entityId,
        state = "unknown",
        attributes = mapOf("radius" to radius, "latitude" to 39.9, "longitude" to 116.4),
        lastChanged = LocalDateTime.now(),
        lastUpdated = LocalDateTime.now(),
    )

    private companion object {
        const val SERVER = 1
        const val NOW = 1_700_000_000_000L
        const val TTL = 4L * 60 * 60 * 1000
    }
}
