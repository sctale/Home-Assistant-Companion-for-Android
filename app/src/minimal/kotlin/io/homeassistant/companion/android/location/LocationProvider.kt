package io.homeassistant.companion.android.location

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import io.homeassistant.companion.android.common.util.SdkVersion
import io.homeassistant.companion.android.common.util.instant
import io.homeassistant.companion.android.sensors.GeocodeSensorManager.Companion.LOCATION_OUTDATED_THRESHOLD
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

private val provider: String = if (SdkVersion.isAtLeast(Build.VERSION_CODES.S)) {
    LocationManager.FUSED_PROVIDER
} else {
    LocationManager.GPS_PROVIDER
}

/**
 * 平台在这个一次性调用上没有任何超时约定：provider 不给回调，调用方就永远挂着。挂着的协程既吊住整轮
 * 传感器扫描（扫描是逐个 manager 顺序跑的），也让进程无法回到休眠——所以必须自己封顶并取消信号。
 */
private const val CURRENT_LOCATION_TIMEOUT_MS = 30_000L

@OptIn(ExperimentalTime::class)
@RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
suspend fun getLastLocation(context: Context): Location? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    if (locationManager == null) {
        Timber.w("Couldn't get LocationManager from context")
        return null
    }

    val lastKnownLocation = locationManager.getLastKnownLocation(provider)
    /**
     * If the last known location is null or too old, get the current location.
     *
     * Note: In systems where no application or service actively requests location updates,
     * the last known location may never gets updated.
     */
    return if (lastKnownLocation == null ||
        Clock.System.now() - lastKnownLocation.instant() > LOCATION_OUTDATED_THRESHOLD
    ) {
        Timber.d("Last known location is null or too old, getting current location.")
        locationManager.getCurrentLocation(context)
    } else {
        lastKnownLocation
    }
}

@RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
private suspend fun LocationManager.getCurrentLocation(context: Context): Location? =
    withTimeoutOrNull(CURRENT_LOCATION_TIMEOUT_MS.milliseconds) {
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            try {
                LocationManagerCompat.getCurrentLocation(
                    this@getCurrentLocation,
                    provider,
                    cancellationSignal,
                    ContextCompat.getMainExecutor(context),
                    androidx.core.util.Consumer<Location> { location ->
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    },
                )
            } catch (e: Exception) {
                Timber.e(e, "Unable to request a one-off location from '$provider'")
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    } ?: run {
        Timber.w("One-off '$provider' location did not arrive within ${CURRENT_LOCATION_TIMEOUT_MS}ms")
        null
    }
