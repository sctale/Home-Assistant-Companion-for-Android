package io.homeassistant.companion.android.common.sensors

import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the rate limit on the entity-registry sync (`get_config`), which is the only large payload in a
 * sensor sweep and used to run after *every* system broadcast (screen on/off, power-save toggle,
 * Wi-Fi/network state changes).
 *
 * The invariant these tests protect is the one the whole change rests on: limiting the registry sync must
 * not limit anything that reports state. Registration of a not-yet-registered sensor and the
 * state-changed comparison happen outside `get_config`, so the only thing that can lag is reconciling an
 * enabled/disabled flip made in the Home Assistant frontend.
 */
class SensorRegistrySyncLimitTest {

    @AfterEach
    fun tearDown() {
        SensorReceiverBase.resetRegistrySyncForTests()
    }

    @Test
    fun `Given a server that never synced when a sweep runs then the registry is due`() {
        assertTrue(SensorReceiverBase.isRegistrySyncDue(SERVER))
    }

    @Test
    fun `Given a sync that just happened when another sweep runs then the registry is skipped`() {
        SensorReceiverBase.markRegistrySync(SERVER, NOW)

        assertFalse(SensorReceiverBase.isRegistrySyncDue(SERVER, NOW + 1_000))
        assertFalse(SensorReceiverBase.isRegistrySyncDue(SERVER, NOW + REGISTRY_INTERVAL_MS - 1))
    }

    @Test
    fun `Given the interval elapsed when a sweep runs then the registry syncs again`() {
        SensorReceiverBase.markRegistrySync(SERVER, NOW)

        assertTrue(SensorReceiverBase.isRegistrySyncDue(SERVER, NOW + REGISTRY_INTERVAL_MS))
    }

    @Test
    fun `Given several servers when one syncs then the others are still due`() {
        SensorReceiverBase.markRegistrySync(SERVER, NOW)

        assertFalse(SensorReceiverBase.isRegistrySyncDue(SERVER, NOW))
        assertTrue(SensorReceiverBase.isRegistrySyncDue(SERVER + 1, NOW))
    }

    @Test
    fun `Given a removed server when its record is forgotten then it is due again`() {
        SensorReceiverBase.markRegistrySync(SERVER, NOW)
        SensorReceiverBase.forgetRegistrySync(SERVER)

        assertTrue(SensorReceiverBase.isRegistrySyncDue(SERVER, NOW))
    }

    @Test
    fun `Given the window when the interval is read then it is minutes not seconds`() {
        // 断言这个常量本身：把它改成 30 秒就等于恢复了"每次广播都拉一次注册表"的老行为。
        assertEquals(30, SensorReceiverBase.REGISTRY_SYNC_INTERVAL.inWholeMinutes)
        assertEquals(30.minutes, SensorReceiverBase.REGISTRY_SYNC_INTERVAL)
    }

    private companion object {
        const val SERVER = 1
        const val NOW = 1_700_000_000_000L
        val REGISTRY_INTERVAL_MS = SensorReceiverBase.REGISTRY_SYNC_INTERVAL.inWholeMilliseconds
    }
}
