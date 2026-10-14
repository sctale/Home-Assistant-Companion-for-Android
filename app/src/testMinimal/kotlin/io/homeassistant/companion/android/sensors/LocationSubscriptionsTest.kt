package io.homeassistant.companion.android.sensors

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the property that keeps this build from draining the battery.
 *
 * `LocationManager` de-duplicates `requestLocationUpdates` by the identity of the [PendingIntent] it
 * is given, so as long as two calls for the same purpose produce the same handle, re-subscribing
 * replaces the previous subscription. If they ever stop being equal - for example by folding a
 * timestamp, an instance id or an extras-only difference into the request code - each sensor cycle adds
 * another live location subscription that nothing removes. That is precisely the failure this project
 * is fixing, so it gets a test rather than a comment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class LocationSubscriptionsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `Given the same action requested twice when building the handle then it is the same registration`() {
        val first = LocationSubscriptions.pendingIntent(context, LocationSensorManager.ACTION_PROCESS_LOCATION)
        val second = LocationSubscriptions.pendingIntent(context, LocationSensorManager.ACTION_PROCESS_LOCATION)

        assertEquals(first, second, "a differing handle would add a second live location subscription")
        assertEquals(
            LocationSubscriptions.requestCode(LocationSensorManager.ACTION_PROCESS_LOCATION),
            LocationSubscriptions.requestCode(LocationSensorManager.ACTION_PROCESS_LOCATION),
        )
    }

    @Test
    fun `Given different actions when building the handle then they stay separate registrations`() {
        // Background fixes and zone wake-ups must be removable independently of one another.
        val background = LocationSubscriptions.pendingIntent(
            context,
            LocationSensorManager.ACTION_PROCESS_LOCATION,
        )
        val proximity = LocationSubscriptions.pendingIntent(
            context,
            LocationSensorManager.ACTION_PROCESS_GEO,
        )

        assertNotEquals(background, proximity)
    }

    @Test
    fun `Given repeated cycles when building the handle then it does not depend on instance state`() {
        // The registration is rebuilt from the Context alone. A per-instance field feeding into the
        // identity is what made the previous implementation accumulate listeners.
        val handles = (1..10).map {
            LocationSubscriptions.pendingIntent(context, LocationSensorManager.ACTION_REQUEST_LOCATION_UPDATES)
        }
        assertEquals(1, handles.distinct().size, "ten cycles must remain one subscription")
    }
}
