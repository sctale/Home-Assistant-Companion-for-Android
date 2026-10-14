package io.homeassistant.companion.android.sensors

/**
 * The decision of whether a freshly received location should be sent to a given Home Assistant server.
 *
 * Extracted from [LocationSensorManager.sendLocationUpdate] and expressed over plain values rather
 * than framework types so it can be tested on the JVM. These rules are the reason the location
 * feature is bearable on a metered connection and a recorder-backed Home Assistant: without them every
 * fix becomes a `device_tracker` state change.
 *
 * The object is `internal` to the module: production code is a legitimate caller, while the unit
 * test source set can still reach it (Kotlin treats the test compilation as a friend), so no
 * `@VisibleForTesting` marker is wanted here - it would flag the production call sites.
 *
 * They are also the rules a previous revision of this project silently dropped, which produced two
 * user-visible failures: identical coordinates re-posted on every heartbeat, and a failed send
 * suppressing the next perfectly good fix. Keep the ordering - a fix that is both a duplicate and
 * stale must be reported as stale.
 */
internal object LocationSendGate {
    /** Clock skew tolerance: a fix this far ahead of the device clock is not trusted. */
    const val FUTURE_TOLERANCE_MS = 5000L

    /** Coordinates unchanged for this long are re-sent anyway, so HA sees the device is still alive. */
    const val DUPLICATE_WINDOW_MS = 15 * 60 * 1000L

    /** Minimum spacing between two non-geofence-triggered sends to the same server. */
    const val DEBOUNCE_MS = 5000L

    /** A fix older than this is a leftover from a cached provider, not a current position. */
    const val FRESH_WINDOW_MS = 300000L

    /**
     * Returns the reason to skip the update, or `null` when it should be sent.
     *
     * @param now wall-clock time at the moment the update is evaluated
     * @param locationTime timestamp carried by the fix itself
     * @param lastSend when the last successful send to this server happened, 0 if never
     * @param lastUpdateString the payload string of the last successful send, null if never
     * @param updateLocationString the payload string about to be sent
     * @param isGeofence true when a zone boundary provoked this update; boundary events are never
     *   debounced, because "just crossed into home" is exactly what the user automates on
     */
    fun skipReason(
        now: Long,
        locationTime: Long,
        lastSend: Long,
        lastUpdateString: String?,
        updateLocationString: String,
        isGeofence: Boolean,
    ): Skip? = when {
        now + FUTURE_TOLERANCE_MS < locationTime -> Skip.FUTURE
        locationTime < lastSend -> Skip.NOT_LATEST
        now - locationTime > FRESH_WINDOW_MS -> Skip.OLD
        updateLocationString == lastUpdateString && now < lastSend + DUPLICATE_WINDOW_MS -> Skip.DUPLICATE
        !isGeofence && now < lastSend + DEBOUNCE_MS -> Skip.DEBOUNCE
        else -> null
    }

    enum class Skip {
        FUTURE,
        NOT_LATEST,
        OLD,
        DUPLICATE,
        DEBOUNCE,
    }
}
