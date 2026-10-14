package io.homeassistant.companion.android.sensors

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * The registration handle for a self-managed `LocationManager` subscription.
 *
 * `LocationManager` keys both `requestLocationUpdates` and `removeUpdates` by the [PendingIntent]
 * handed to it, and `PendingIntent` identity is the tuple (request code, intent filterEquals) -
 * where `filterEquals` compares action, data, type, component and package, but **not** extras. Two
 * subscriptions that differ only in extras are therefore the *same* registration, and two that differ
 * in request code are different ones.
 *
 * Deriving the request code from the action, and never from an object allocated per call, is what
 * makes re-subscribing idempotent. A previous revision of this file passed a fresh anonymous
 * `LocationListener` each time instead; those are never equal, so every sensor cycle added another
 * live GPS subscription that nothing ever removed - roughly one hundred per day, each one waking the
 * app for every fix. That is the root cause of the battery drain this build is meant to end.
 */
internal object LocationSubscriptions {

    /**
     * @param action the receiver action that a delivered fix will be routed to; also the identity of
     *   the subscription, so callers that register must pass the same value when they unregister.
     */
    fun pendingIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, LocationSensorManager::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode(action),
            intent,
            // The platform adds the location extra when it delivers the broadcast, so the intent has
            // to stay mutable. This is why a fresh listener object is not used as the handle here.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    fun requestCode(action: String): Int = action.hashCode()
}
