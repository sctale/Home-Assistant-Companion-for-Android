package io.homeassistant.companion.android.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.core.content.getSystemService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.common.data.integration.UpdateLocation
import io.homeassistant.companion.android.common.data.integration.containsWithAccuracy
import io.homeassistant.companion.android.common.data.prefs.PrefsRepository
import io.homeassistant.companion.android.common.sensors.SensorManager
import io.homeassistant.companion.android.common.sensors.SensorReceiverBase
import io.homeassistant.companion.android.common.util.DisabledLocationHandler
import io.homeassistant.companion.android.common.util.SdkVersion
import io.homeassistant.companion.android.database.DatabaseEntryPoint
import io.homeassistant.companion.android.database.location.LocationHistoryDao
import io.homeassistant.companion.android.database.location.LocationHistoryItem
import io.homeassistant.companion.android.database.location.LocationHistoryItemResult
import io.homeassistant.companion.android.database.location.LocationHistoryItemTrigger
import io.homeassistant.companion.android.database.sensor.Attribute
import io.homeassistant.companion.android.database.sensor.SensorSetting
import io.homeassistant.companion.android.database.sensor.SensorSettingType
import io.homeassistant.companion.android.database.sensor.toSensorWithAttributes
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Location reporting for the **minimal** build, which links no Google Play services at all.
 *
 * It matches the `full` flavour in everything a Home Assistant user can observe: the same sensor ids,
 * the same persisted setting keys, the same `zone_entered` / `zone_exited` event names and payloads, and
 * the same "send location as" privacy switch. Only the location *source* differs - this file uses plain
 * `android.location`, so it works on hardware with no Play services.
 *
 * ### Why there is no OS geofencing here
 *
 * Platform geofencing (`Geofence`, `GeofencingRequest`, `LocationManager.addGeofences`) is **not** in the
 * public Android SDK: it lives only in Play Services, and the framework APIs that once existed were
 * hidden and removed. A services-free build therefore cannot register a real geofence. Instead:
 *
 *  - zone membership is *computed* from every background fix by [handleZoneEvaluation], which yields the
 *    `zone_background` sensor plus enter/exit events with the same payloads as `full`; and
 *  - [LocationManager.addProximityAlert] is available as an opt-in wake source, so a boundary crossing can
 *    be acted on between scheduled fixes. It is opt-in because whether it is actually serviced is
 *    device-dependent; where it is not, only promptness is lost, never correctness.
 *
 * ### Battery
 *
 * Subscriptions use a **stable [PendingIntent]** rather than a fresh [LocationListener] object per call.
 * `LocationManager` keys both registration and removal by that handle, so re-subscribing *replaces* the
 * previous subscription instead of stacking another one, and [removeBackgroundUpdateRequests] genuinely
 * undoes it. On API 31+ requests also set `maxUpdateDelayMillis` so the platform batches fixes rather
 * than waking this app for each one, and the default cadence is 15 minutes at `BALANCED` power quality
 * rather than a continuously hot GPS chip.
 */
@AndroidEntryPoint
class LocationSensorManager :
    BroadcastReceiver(),
    SensorManager {

    companion object {
        // Kept identical to the `full` flavour: these are persisted setting keys and surface in the
        // sensor settings UI.
        private const val SETTING_SEND_LOCATION_AS = "location_send_as"
        private const val SETTING_ACCURACY = "location_minimum_accuracy"
        private const val SETTING_ACCURATE_UPDATE_TIME = "location_minimum_time_updates"
        private const val SETTING_INCLUDE_SENSOR_UPDATE = "location_include_sensor_update"

        // Owned by the `full` flavour's high accuracy mode. Stored disabled here so shared code that
        // writes them does not fail, and so the UI does not offer something this build cannot do.
        private const val SETTING_HIGH_ACCURACY_MODE = "location_ham_enabled"
        private const val SETTING_HIGH_ACCURACY_MODE_UPDATE_INTERVAL = "location_ham_update_interval"

        // Minimal-specific: the polling knobs Play's fused provider used to decide for us.
        private const val SETTING_PROVIDER = "location_provider"
        private const val SETTING_UPDATE_INTERVAL = "location_update_interval"
        private const val SETTING_MIN_DISTANCE = "location_minimum_distance"
        private const val SETTING_ZONE_ALERTS = "location_zone_proximity_alerts"

        private const val SEND_LOCATION_AS_EXACT = "exact"
        private const val SEND_LOCATION_AS_ZONE_ONLY = "zone_only"

        private const val PROVIDER_AUTO = "auto"
        private const val PROVIDER_NETWORK = "network"
        private const val PROVIDER_GPS = "gps"

        const val MINIMUM_ACCURACY = 200
        private const val DEFAULT_MINIMUM_ACCURACY = 200

        /**
         * Background cadence for a hand-rolled subscription.
         *
         * v0.1.0 曾把默认压到 15 分钟、批处理 20 分钟，那是"用少测来省电"——v0.1.2 的复查结论是这条
         * 路子本身不该走：真正浪费的是每帧重复上报、每帧重拉区域列表、每次扫描重挂围栏。默认因此回到
         * 5 分钟，并把可调下限从 5 分钟放到 1 分钟：想更密的轨迹现在可以放心设，因为每一帧的实际成本
         * 已经降下来了。
         *
         * 批处理上限与间隔取齐（不再额外压 20 分钟）：它只允许平台把同一轮里已经到期的结果合并投递，
         * 不会让定位结果迟到超过一个周期。区域边界不再依赖轮询——系统围栏（`addProximityAlert`）在
         * v0.1.2 才真正开始工作，所以进入/离开是事件驱动的，与这里的间隔无关。
         */
        private const val DEFAULT_UPDATE_INTERVAL_SECONDS = 5 * 60L
        private const val DEFAULT_MAX_UPDATE_DELAY_SECONDS = 5 * 60L
        private const val DEFAULT_MINIMUM_DISTANCE_METERS = 100f
        private const val MINIMUM_UPDATE_INTERVAL_SECONDS = 60L
        private const val DEFAULT_TRIGGER_RANGE_METERS = 300
        private const val DEFAULT_UPDATE_INTERVAL_HA_SECONDS = 5

        private const val ZONE_NAME_NOT_HOME = "not_home"

        private const val HISTORY_DURATION = 60 * 60 * 48 * 1000L // 48 hours, as in the `full` flavour

        private val zoneCacheDurationMs = TimeUnit.HOURS.toMillis(4)
        private const val ONE_FIX_TIMEOUT_MS = 30_000L
        private const val MAX_ACCURATE_FIX_ATTEMPTS = 5

        /** 持锁上限 = 重试次数 × 单次超时 + 这点余量（对齐实际工作量，不再凭空多占 7 分钟）。 */
        private const val SESSION_TIMEOUT_MARGIN_MS = 30_000L

        /** 刚抓到的精确位置在这个窗口内可以被另一个并发请求直接复用，不再重复跑一轮 GPS。 */
        private const val ACCURATE_FIX_REUSE_MS = 15_000L

        private const val WAKE_LOCK_TAG = "HomeAssistant::AccurateLocation"

        /** Bounds proximity alerts: they are a shared system resource, and this is a polite ceiling. */
        private const val MAX_PROXIMITY_ALERTS = 10

        private const val PREFERENCES_NAME = "location_sensor_manager"
        private const val PREF_REGISTERED_ALERTS = "registered_proximity_alerts"

        // SharedPreferences 里"已经上报过什么"的键前缀，按 serverId 分键（见 LocationRuntimeState 的说明）。
        private const val KEY_LAST_SEND = "last_send_at"
        private const val KEY_LAST_COORDS = "last_sent_coords"
        private const val KEY_ZONES_INSIDE = "zones_inside"

        const val ACTION_REQUEST_LOCATION_UPDATES =
            "io.homeassistant.companion.android.background.REQUEST_UPDATES"
        const val ACTION_REQUEST_ACCURATE_LOCATION_UPDATE =
            "io.homeassistant.companion.android.background.REQUEST_ACCURATE_UPDATE"
        const val ACTION_PROCESS_LOCATION =
            "io.homeassistant.companion.android.background.PROCESS_UPDATES"
        const val ACTION_PROCESS_GEO =
            "io.homeassistant.companion.android.background.PROCESS_GEOFENCE"
        const val ACTION_FORCE_HIGH_ACCURACY =
            "io.homeassistant.companion.android.background.FORCE_HIGH_ACCURACY"

        private const val EXTRA_PROXIMITY_ZONE = "proximity_zone"

        /** `addProximityAlert` uses -1 for "never expires"; there is no public constant. */
        private const val PROXIMITY_ALERT_NEVER_EXPIRES = -1L

        val backgroundLocation = SensorManager.BasicSensor(
            "location_background",
            "",
            commonR.string.basic_sensor_name_location_background,
            commonR.string.sensor_description_location_background,
            "mdi:map-marker-multiple",
            updateType = SensorManager.BasicSensor.UpdateType.LOCATION,
        )
        val zoneLocation = SensorManager.BasicSensor(
            "zone_background",
            "",
            commonR.string.basic_sensor_name_location_zone,
            commonR.string.sensor_description_location_zone,
            "mdi:map-marker-radius",
            updateType = SensorManager.BasicSensor.UpdateType.LOCATION,
        )
        val singleAccurateLocation = SensorManager.BasicSensor(
            "accurate_location",
            "",
            commonR.string.basic_sensor_name_location_accurate,
            commonR.string.sensor_description_location_accurate,
            "mdi:crosshairs-gps",
            updateType = SensorManager.BasicSensor.UpdateType.LOCATION,
        )

        /** Why a location is being requested, so cost and accuracy can be traded differently. */
        enum class Purpose {
            BACKGROUND,
            ACCURATE,
        }

        enum class LocationUpdateTrigger(val isGeofence: Boolean = false) {
            BACKGROUND_LOCATION,
            PROXIMITY_ENTER(isGeofence = true),
            PROXIMITY_EXIT(isGeofence = true),
            SINGLE_ACCURATE_LOCATION,
        }

        /**
         * Builds an explicit-component [Intent] addressed to this receiver that triggers a single
         * accurate location update via [ACTION_REQUEST_ACCURATE_LOCATION_UPDATE].
         */
        fun createRequestAccurateLocationUpdateIntent(context: Context): Intent = Intent(
            context,
            LocationSensorManager::class.java,
        ).apply {
            action = ACTION_REQUEST_ACCURATE_LOCATION_UPDATE
        }

        /**
         * High accuracy mode is a Play-services foreground service and does not exist in this flavour, so
         * the setting is stored disabled. The shared command handler can still call this without failing.
         */
        suspend fun setHighAccuracyModeSetting(context: Context, enabled: Boolean) {
            DatabaseEntryPoint.resolve(context).sensorDao().add(
                SensorSetting(
                    backgroundLocation.id,
                    SETTING_HIGH_ACCURACY_MODE,
                    enabled.toString(),
                    SensorSettingType.TOGGLE,
                    enabled = false,
                ),
            )
        }

        suspend fun setHighAccuracyModeIntervalSetting(context: Context, updateInterval: Int) {
            DatabaseEntryPoint.resolve(context).sensorDao().add(
                SensorSetting(
                    backgroundLocation.id,
                    SETTING_HIGH_ACCURACY_MODE_UPDATE_INTERVAL,
                    updateInterval.toString(),
                    SensorSettingType.NUMBER,
                    enabled = false,
                ),
            )
        }

        suspend fun getHighAccuracyModeIntervalSetting(context: Context): Int = DEFAULT_UPDATE_INTERVAL_HA_SECONDS

        /**
         * A proximity alert is identified by the [PendingIntent] it was registered with, and
         * `PendingIntent` identity includes the request code but *not* the extras. The request code is
         * therefore derived from the zone key, and the zone key is persisted so alerts can be removed
         * after a process restart.
         */
        private fun proximityRequestCode(zoneKey: String): Int = zoneKey.hashCode()

        private fun zoneKey(serverId: Int, entityId: String): String = "${serverId}_$entityId"
    }

    @Inject
    lateinit var prefsRepository: PrefsRepository

    private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())

    private lateinit var latestContext: Context

    /**
     * What has already been reported, kept in [LocationRuntimeState] rather than in fields: a
     * manifest-registered receiver is re-instantiated for every broadcast (and background fixes arrive as
     * broadcasts), so instance state is empty before each fix and every guard would silently never fire.
     * The two facts that decide "did anything change" are written through to SharedPreferences so they
     * also survive the process being frozen and revived.
     */
    private val stateStore = object : LocationRuntimeState.Store {
        override fun lastSendMillis(serverId: Int): Long = preferences().getLong(gateKey(KEY_LAST_SEND, serverId), 0L)

        override fun lastCoordinates(serverId: Int): String? =
            preferences().getString(gateKey(KEY_LAST_COORDS, serverId), null)

        override fun writeLastSend(serverId: Int, whenMillis: Long, coordinates: String) {
            preferences().edit {
                putLong(gateKey(KEY_LAST_SEND, serverId), whenMillis)
                putString(gateKey(KEY_LAST_COORDS, serverId), coordinates)
            }
        }

        // 键不存在 = 从没记过；键存在但为空串 = 记过，且当时不在任何区域。两者必须区分，否则每次
        // 进程复活都会把"一直待在家"当成刚刚进入，凭空触发一次 zone_entered 自动化。
        override fun zoneMembership(serverId: Int): Set<String>? =
            preferences().getString(gateKey(KEY_ZONES_INSIDE, serverId), null)
                ?.split(',')
                ?.filter { it.isNotBlank() }
                ?.toSet()

        override fun writeZoneMembership(serverId: Int, entityIds: Set<String>) {
            preferences().edit {
                putString(gateKey(KEY_ZONES_INSIDE, serverId), entityIds.sorted().joinToString(","))
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        latestContext = context

        ioScope.launch {
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED,
                ACTION_REQUEST_LOCATION_UPDATES,
                -> setupLocationTracking()

                ACTION_PROCESS_LOCATION -> handleLocationUpdate(intent)

                ACTION_PROCESS_GEO -> handleProximityAlert(intent)

                ACTION_REQUEST_ACCURATE_LOCATION_UPDATE -> requestSingleAccurateLocation()

                ACTION_FORCE_HIGH_ACCURACY -> Timber.w(
                    "High accuracy mode is unavailable without Google Play services; ignoring the command.",
                )

                else -> Timber.w("Unknown intent action: ${intent.action}!")
            }
        }
    }

    private suspend fun setupLocationTracking() {
        if (!checkPermission(latestContext, backgroundLocation.id)) {
            Timber.w("Not starting location reporting because of permissions.")
            return
        }

        val backgroundEnabled = isEnabled(latestContext, backgroundLocation)
        val zoneEnabled = isEnabled(latestContext, zoneLocation)

        try {
            if (!backgroundEnabled && !zoneEnabled) {
                removeAllLocationUpdateRequests()
                return
            }

            if (!zoneEnabled) {
                removeProximityAlerts()
            }

            // Zone membership is computed from the background fix stream, so the subscription backs both
            // sensors. requestLocationUpdates() re-registers with an identical PendingIntent, which the
            // platform treats as a replacement rather than an additional subscription.
            val reconfigured = requestLocationUpdates()

            if (zoneEnabled) {
                // 内部按告警指纹去重：集合没变就一次系统调用都不发。
                requestZoneProximityAlerts()
            }

            if (reconfigured) {
                // 只有真的重挂订阅时才补设置行：这一段每个服务器一次 getServer + 7 次 getSetting，
                // 而它每次都把同样的值再写一遍，对检测时效没有任何帮助。
                serverManager(latestContext).servers().forEach {
                    getSendLocationAsSetting(it.id) // Creates the setting so it appears in the sensor UI.
                }
                ensureSettingsExist()
            }
        } catch (e: Exception) {
            Timber.e(e, "Issue setting up location tracking")
        }
    }

    /**
     * Writes the settings rows so they exist before they are read. Reading with
     * `getSettings().firstOrNull()` instead would leave the thresholds absent and permanently defaulted,
     * and would hide them from the sensor settings screen.
     */
    private suspend fun ensureSettingsExist() {
        listOf(backgroundLocation, zoneLocation, singleAccurateLocation).forEach { sensor ->
            getSetting(
                latestContext,
                sensor,
                SETTING_ACCURACY,
                SensorSettingType.NUMBER,
                DEFAULT_MINIMUM_ACCURACY.toString(),
            )
        }
        getSetting(
            latestContext,
            backgroundLocation,
            SETTING_PROVIDER,
            SensorSettingType.LIST,
            PROVIDER_AUTO,
            entries = listOf(PROVIDER_AUTO, PROVIDER_NETWORK, PROVIDER_GPS),
        )
        getSetting(
            latestContext,
            backgroundLocation,
            SETTING_UPDATE_INTERVAL,
            SensorSettingType.NUMBER,
            DEFAULT_UPDATE_INTERVAL_SECONDS.toString(),
        )
        getSetting(
            latestContext,
            backgroundLocation,
            SETTING_MIN_DISTANCE,
            SensorSettingType.NUMBER,
            DEFAULT_MINIMUM_DISTANCE_METERS.toInt().toString(),
        )
        getSetting(
            latestContext,
            zoneLocation,
            SETTING_ZONE_ALERTS,
            SensorSettingType.TOGGLE,
            "false",
        )
    }

    /**
     * Chooses a provider.
     *
     * [Purpose.BACKGROUND] optimises for cost: the platform fused provider, then network, and GPS
     * only as a last resort - a continuously hot GPS chip is what drained users' batteries before.
     *
     * [Purpose.ACCURATE] deliberately reorders the candidates to put GPS ahead of network. The
     * one-off "accurate location" request is the opposite trade: it runs briefly, and returning a
     * ~100 m network fix would sail straight through the minimum-accuracy gate and make the
     * `accurate_location` sensor indistinguishable from background reporting.
     *
     * The provider choice is a single policy owned by `location_background` and applied to every
     * location request, rather than a per-sensor setting the user would have to keep in sync.
     */
    @SuppressLint("InlinedApi") // FUSED_PROVIDER is an inlined String constant; gated on API 31 below.
    private suspend fun selectProvider(manager: LocationManager, purpose: Purpose): String? {
        val preference = getSetting(
            latestContext,
            backgroundLocation,
            SETTING_PROVIDER,
            SensorSettingType.LIST,
            PROVIDER_AUTO,
            entries = listOf(PROVIDER_AUTO, PROVIDER_NETWORK, PROVIDER_GPS),
        )

        val available = manager.allProviders
        val fused = LocationManager.FUSED_PROVIDER.takeIf { SdkVersion.isAtLeast(Build.VERSION_CODES.S) }
        val candidates = when (preference) {
            PROVIDER_GPS -> listOf(LocationManager.GPS_PROVIDER)
            PROVIDER_NETWORK ->
                if (purpose == Purpose.ACCURATE) {
                    listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                } else {
                    listOfNotNull(fused, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                }

            else -> if (purpose == Purpose.ACCURATE) {
                listOfNotNull(fused, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            } else {
                listOfNotNull(fused, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            }
        }
        return candidates.firstOrNull { available.contains(it) }
            .also { selected ->
                if (selected == null) {
                    Timber.w("No usable location provider; device offers $available")
                } else {
                    Timber.d("Using location provider '$selected' (preference '$preference')")
                }
            }
    }

    /**
     * Registers the background subscription.
     *
     * @return true when the subscription was (re)registered with the platform, false when the desired
     * fingerprint is identical to what is already registered. Callers use that to skip the setup work
     * that only matters after a real change.
     */
    private suspend fun requestLocationUpdates(): Boolean {
        if (!checkPermission(latestContext, backgroundLocation.id)) {
            Timber.w("Not registering for location updates because of permissions.")
            return false
        }

        val manager = latestContext.getSystemService<LocationManager>() ?: run {
            Timber.w("LocationManager is unavailable on this device")
            return false
        }
        val provider = selectProvider(manager, Purpose.BACKGROUND) ?: return false

        val intervalSeconds = getSetting(
            latestContext,
            backgroundLocation,
            SETTING_UPDATE_INTERVAL,
            SensorSettingType.NUMBER,
            DEFAULT_UPDATE_INTERVAL_SECONDS.toString(),
        ).toLongOrNull()?.coerceAtLeast(MINIMUM_UPDATE_INTERVAL_SECONDS) ?: DEFAULT_UPDATE_INTERVAL_SECONDS

        val minDistance = getSetting(
            latestContext,
            backgroundLocation,
            SETTING_MIN_DISTANCE,
            SensorSettingType.NUMBER,
            DEFAULT_MINIMUM_DISTANCE_METERS.toInt().toString(),
        ).toFloatOrNull()?.coerceAtLeast(0f) ?: DEFAULT_MINIMUM_DISTANCE_METERS

        // 同一份配置重复注册并不会让任何一次定位更早到，只是让硬件重新评估一遍请求；每次传感器扫描
        // 都会走到这里，所以按指纹去重。参数一变（含用户在设置里改节奏）指纹就不同，照旧重挂。
        val signature = "$provider|$intervalSeconds|$minDistance"
        if (signature == LocationRuntimeState.subscriptionSignature) {
            Timber.d("Background subscription unchanged ($signature), nothing to re-register")
            return false
        }

        Timber.d("Registering '$provider' location updates every ~${intervalSeconds}s")
        try {
            if (SdkVersion.isAtLeast(Build.VERSION_CODES.S)) {
                manager.requestLocationUpdates(
                    provider,
                    LocationRequest.Builder(intervalSeconds * 1000L).apply {
                        // The single most important battery knob: let the platform deliver fixes in one
                        // batch instead of waking this app for each one.
                        setMaxUpdateDelayMillis(DEFAULT_MAX_UPDATE_DELAY_SECONDS * 1000L)
                        setMinUpdateDistanceMeters(minDistance)
                        setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
                    }.build(),
                    getLocationUpdateIntent(ACTION_PROCESS_LOCATION),
                )
            } else {
                manager.requestLocationUpdates(
                    provider,
                    intervalSeconds * 1000L,
                    minDistance,
                    getLocationUpdateIntent(ACTION_PROCESS_LOCATION),
                )
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Missing permission to request location updates")
            return false
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Provider '$provider' is not available on this device")
            return false
        } catch (e: Exception) {
            Timber.e(e, "Issue requesting location updates")
            return false
        }
        LocationRuntimeState.subscriptionSignature = signature
        return true
    }

    private fun removeBackgroundUpdateRequests() {
        val manager = latestContext.getSystemService<LocationManager>() ?: return
        Timber.d("Removing background location requests.")
        // 订阅已经不在了，指纹必须一起作废：否则下一次 setup 会以为"配置没变"而跳过重新注册。
        LocationRuntimeState.subscriptionSignature = null
        try {
            manager.removeUpdates(getLocationUpdateIntent(ACTION_PROCESS_LOCATION))
        } catch (e: Exception) {
            Timber.e(e, "Issue removing background location requests")
        }
    }

    private fun removeAllLocationUpdateRequests() {
        Timber.d("Removing all location requests.")
        removeBackgroundUpdateRequests()
        removeProximityAlerts()
        // 两个定位传感器都关了：丢掉内存态。持久化的门控留着没关系——重新开启后第一次评估会拿它当基线，
        // 而那正是想要的语义（期间真走开了，就该报一次离开）。
        LocationRuntimeState.reset()
    }

    /**
     * Optional wake source so a zone boundary can be acted on between background fixes.
     *
     * `addProximityAlert` is serviced by whatever geofence backend the device has. On services-free
     * hardware that ranges from a real hardware geofence to nothing at all, so failures here are logged
     * and ignored: correctness never depends on it.
     */
    private suspend fun requestZoneProximityAlerts() {
        if (!getSetting(
                latestContext,
                zoneLocation,
                SETTING_ZONE_ALERTS,
                SensorSettingType.TOGGLE,
                "false",
            ).toBoolean()
        ) {
            removeProximityAlerts()
            return
        }

        if (!checkPermission(latestContext, zoneLocation.id)) {
            Timber.w("Not registering proximity alerts because of permissions.")
            return
        }

        val manager = latestContext.getSystemService<LocationManager>() ?: return

        val servers = getEnabledServers(latestContext, zoneLocation)
        val alertsWanted = mutableListOf<ProximityAlert>()
        servers.forEach { serverId ->
            val zoneList = getZones(serverId)
            if (zoneList == null) {
                Timber.w("Zone list unavailable for server $serverId")
                return@forEach
            }
            alertsWanted += zoneList.mapNotNull { zone ->
                val latitude = (zone.attributes["latitude"] as? Number)?.toDouble() ?: return@mapNotNull null
                val longitude = (zone.attributes["longitude"] as? Number)?.toDouble() ?: return@mapNotNull null
                val radius = (zone.attributes["radius"] as? Number)?.toFloat() ?: return@mapNotNull null
                ProximityAlert(zoneKey(serverId, zone.entityId), latitude, longitude, radius)
            }
        }

        if (servers.isNotEmpty() && alertsWanted.isEmpty()) {
            // 一个区域都没拿到（多半是所有服务器都取列表失败）时什么都不动：原先这里是"先全删再重建"，
            // 拿不到数据就等于把仍然有效的系统告警清空，白丢一段围栏唤醒。
            Timber.w("No proximity alerts could be derived, leaving the registered set untouched")
            return
        }

        // 小区域优先：半径小的边界更需要及时的围栏唤醒，超出上限被截断的应该是最"松"的那些。
        val desired = alertsWanted
            .sortedWith(compareBy({ it.radius }, { it.key }))
            .take(MAX_PROXIMITY_ALERTS)

        // 指纹一致就一个系统调用都不发。每次广播都会走到这里，而全删全建意味着撤销并重挂系统级告警、
        // 重写持久化列表——纯粹的浪费唤醒，对检测时效毫无帮助。
        val persisted = persistedRegisteredAlerts().mapNotNull { ProximityAlert.fromSpec(it) }
        if (persisted == desired) return

        removeProximityAlerts()

        val registered = mutableListOf<String>()
        desired.forEach { alert ->
            try {
                manager.addProximityAlert(
                    alert.latitude,
                    alert.longitude,
                    alert.radius + DEFAULT_TRIGGER_RANGE_METERS,
                    PROXIMITY_ALERT_NEVER_EXPIRES,
                    getProximityAlertIntent(alert.key),
                )
                registered.add(alert.spec())
            } catch (e: Exception) {
                Timber.e(e, "Platform refused a proximity alert for '${alert.key}'")
            }
        }

        persistRegisteredAlerts(registered)
        Timber.d("Registered ${registered.size} proximity alerts")
    }

    private fun removeProximityAlerts() {
        val manager = latestContext.getSystemService<LocationManager>() ?: return
        val registered = persistedRegisteredAlerts()
        if (registered.isEmpty()) return

        Timber.d("Removing ${registered.size} proximity alerts")
        registered.forEach { spec ->
            // 持久化的是整条指纹（纬度|经度|半径|键），撤注册只需要当初那个 PendingIntent 的键。
            val key = ProximityAlert.keyFromSpec(spec) ?: return@forEach
            try {
                manager.removeProximityAlert(getProximityAlertIntent(key))
            } catch (e: Exception) {
                Timber.e(e, "Issue removing proximity alert for '$key'")
            }
        }
        persistRegisteredAlerts(emptyList())
    }

    private fun getProximityAlertIntent(zoneKey: String): PendingIntent {
        val intent = Intent(latestContext, LocationSensorManager::class.java).apply {
            action = ACTION_PROCESS_GEO
            putExtra(EXTRA_PROXIMITY_ZONE, zoneKey)
        }
        return PendingIntent.getBroadcast(
            latestContext,
            proximityRequestCode(zoneKey),
            intent,
            // FLAG_MUTABLE is required, not incidental: the platform injects the
            // KEY_PROXIMITY_ENTERING extra into this intent when the alert fires. An immutable intent
            // would silently deliver entering=false forever.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun getLocationUpdateIntent(action: String): PendingIntent =
        LocationSubscriptions.pendingIntent(latestContext, action)

    private fun preferences() = latestContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun gateKey(prefix: String, serverId: Int) = "${prefix}_$serverId"

    private fun persistedRegisteredAlerts(): List<String> = preferences().getString(PREF_REGISTERED_ALERTS, "")
        .orEmpty()
        .split("\n")
        .filter { it.isNotBlank() }

    private fun persistRegisteredAlerts(keys: List<String>) {
        preferences().edit { putString(PREF_REGISTERED_ALERTS, keys.joinToString("\n")) }
    }

    private suspend fun handleLocationUpdate(intent: Intent) {
        Timber.d("Received location update.")
        val serverIds = getEnabledServers(latestContext, backgroundLocation)
        serverIds.forEach { LocationRuntimeState.prime(it, stateStore) }

        // A PendingIntent subscription delivers the fix as an extra, not as a LocationResult.
        val location = IntentCompat.getParcelableExtra(
            intent,
            LocationManager.KEY_LOCATION_CHANGED,
            Location::class.java,
        ) ?: run {
            Timber.w("Location update carried no location")
            return
        }

        val minAccuracy = getMinimumAccuracy(backgroundLocation)
        if (location.accuracy > minAccuracy) {
            Timber.w("Location accuracy didn't meet requirements, disregarding: $location")
            logLocationUpdate(
                location,
                null,
                null,
                LocationUpdateTrigger.BACKGROUND_LOCATION,
                LocationHistoryItemResult.SKIPPED_ACCURACY,
            )
            return
        }

        serverIds.forEach { serverId ->
            sendLocationUpdate(location, serverId, LocationUpdateTrigger.BACKGROUND_LOCATION)
        }
        handleZoneEvaluation(location)
    }

    private suspend fun handleProximityAlert(intent: Intent) {
        if (!isEnabled(latestContext, zoneLocation)) {
            Timber.w("Zone tracking is disabled but an alert fired; unregistering alerts")
            removeProximityAlerts()
            return
        }

        val zoneKey = intent.getStringExtra(EXTRA_PROXIMITY_ZONE)
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        Timber.d("Proximity alert for '$zoneKey', entering=$entering")

        // The alert only says a boundary was crossed. Re-read where we actually are instead of trusting
        // the alert payload, then let the computed diff decide what to report. 相邻的进入+离开会各自
        // 触发一次抓取，走同一个合流点就不会跑两轮全速 GPS。
        val location = LocationRuntimeState.accurateFix(ACCURATE_FIX_REUSE_MS) { currentAccurateLocation() }
        if (location == null) {
            Timber.w("No location available after proximity alert")
            return
        }

        val trigger = if (entering) {
            LocationUpdateTrigger.PROXIMITY_ENTER
        } else {
            LocationUpdateTrigger.PROXIMITY_EXIT
        }

        handleZoneEvaluation(location, trigger = trigger)

        val minAccuracy = getMinimumAccuracy(zoneLocation)
        if (location.accuracy > minAccuracy) {
            Timber.w("Proximity alert location accuracy didn't meet requirements: ${location.accuracy}m")
            logLocationUpdate(location, null, null, trigger, LocationHistoryItemResult.SKIPPED_ACCURACY)
            return
        }

        getEnabledServers(latestContext, zoneLocation).forEach { serverId ->
            sendLocationUpdate(location, serverId, trigger)
        }
    }

    /**
     * Computes which zones [location] falls inside, and reports each change to Home Assistant exactly the
     * way the `full` flavour's geofence callback does.
     */
    private suspend fun handleZoneEvaluation(location: Location, trigger: LocationUpdateTrigger? = null) {
        val enabledServers = getEnabledServers(latestContext, zoneLocation)
        if (enabledServers.isEmpty()) return

        val changed = mutableListOf<Pair<Int, String>>() // serverId to entityId
        val previousZones = mutableMapOf<Int, Set<String>>()
        val currentZones = mutableMapOf<Int, Set<String>>()
        // Servers with no baseline yet (first evaluation after install or process death). Diffing
        // against "nothing" would report every zone we happen to be inside as just-entered, and a
        // spurious android.zone_entered fires the user's "arrived home" automations.
        val unbaselined = mutableSetOf<Int>()

        enabledServers.forEach { serverId ->
            val zoneList = getZones(serverId) ?: run {
                Timber.w("No zone list for server $serverId, skipping zone evaluation this round")
                return@forEach
            }

            val inside = zoneList
                .filter { zone ->
                    val radius = zone.attributes["radius"] as? Number
                    radius != null && runCatching { zone.containsWithAccuracy(location) }.getOrDefault(false)
                }.sortedWith(
                    // Smallest zone first; break radius ties by closeness to the zone center.
                    compareBy<Entity> { (it.attributes["radius"] as? Number ?: Int.MAX_VALUE).toFloat() }
                        .thenBy { distanceToZoneCenter(location, it) },
                ).mapTo(mutableSetOf()) { it.entityId }

            LocationRuntimeState.prime(serverId, stateStore)
            val baseline = LocationRuntimeState.zoneBaseline(serverId)
            if (baseline == null || baseline != inside) {
                LocationRuntimeState.setZoneBaseline(serverId, inside, stateStore)
            }
            currentZones[serverId] = inside

            if (baseline == null) {
                unbaselined.add(serverId)
                return@forEach
            }

            (inside - baseline).forEach { changed.add(serverId to it) }
            (baseline - inside).forEach { changed.add(serverId to it) }
        }

        if (unbaselined.isNotEmpty()) {
            Timber.d("Seeding zone baseline for ${unbaselined.joinToString()}, no events reported")
        }

        changed.forEach { (serverId, entityId) ->
            fireZoneEvent(
                serverId = serverId,
                entityId = entityId,
                entering = currentZones[serverId]?.contains(entityId) == true,
                location = location,
                trigger = trigger,
            )
        }

        // No `onSensorUpdated` here, deliberately: the `full` flavour never writes an entity for
        // `zone_background` either. Zone state reaches Home Assistant through the location payload
        // (`location_name` / `in_zones`) and the enter/exit events above, so writing a sensor entity
        // would invent an entity the official app does not have.
    }

    private suspend fun fireZoneEvent(
        serverId: Int,
        entityId: String,
        entering: Boolean,
        location: Location,
        trigger: LocationUpdateTrigger?,
    ) {
        if (!isEnabled(latestContext, zoneLocation, serverId)) return

        val zoneStatusEvent = if (entering) "android.zone_entered" else "android.zone_exited"
        // Typed as Map<String, Any> so the map can be handed to fireEvent without an unchecked cast;
        // `provider` is the one field the platform declares as nullable.
        val zoneAttr: Map<String, Any> = mapOf(
            "accuracy" to location.accuracy,
            "altitude" to location.altitude,
            "bearing" to location.bearing,
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "provider" to (location.provider ?: "unknown"),
            "time" to location.time,
            "vertical_accuracy" to if (SdkVersion.isAtLeast(Build.VERSION_CODES.O)) {
                location.verticalAccuracyMeters.toInt()
            } else {
                0
            },
            "zone" to entityId.substringAfterLast('.'),
        )

        try {
            serverManager(latestContext).integrationRepository(serverId)
                .fireEvent(zoneStatusEvent, zoneAttr)
            Timber.d("Zone event sent to Home Assistant: $zoneStatusEvent for $entityId")
        } catch (e: Exception) {
            Timber.e(e, "Unable to send zone event to Home Assistant")
        }
    }

    /**
     * Distance in meters between [location] and the center of [zone], used as a tiebreaker when several
     * zones share a radius. Returns [Float.MAX_VALUE] for a zone without coordinates so it sorts last.
     */
    private fun distanceToZoneCenter(location: Location, zone: Entity): Float {
        val zoneLatitude = (zone.attributes["latitude"] as? Number)?.toDouble()
        val zoneLongitude = (zone.attributes["longitude"] as? Number)?.toDouble()
        if (zoneLatitude == null || zoneLongitude == null) return Float.MAX_VALUE

        val results = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, zoneLatitude, zoneLongitude, results)
        return results[0]
    }

    private suspend fun getMinimumAccuracy(sensor: SensorManager.BasicSensor): Int = getSetting(
        latestContext,
        sensor,
        SETTING_ACCURACY,
        SensorSettingType.NUMBER,
        DEFAULT_MINIMUM_ACCURACY.toString(),
    ).toIntOrNull() ?: DEFAULT_MINIMUM_ACCURACY

    private suspend fun sendLocationUpdate(location: Location, serverId: Int, trigger: LocationUpdateTrigger?) {
        var accuracy = 0
        if (location.accuracy.toInt() >= 0) {
            accuracy = location.accuracy.toInt()
        }

        val updateLocation: UpdateLocation
        val updateLocationString: String
        val updateLocationAs = getSendLocationAsSetting(serverId)
        if (updateLocationAs == SEND_LOCATION_AS_ZONE_ONLY) {
            // The user asked for zone names only, so no raw coordinate may leave the device on this path.
            val zonesForName = getZones(serverId)
            if (zonesForName == null) {
                // 区域拿不到时不上报：按"没有区域"发出去的 `not_home` 是错的，会白白触发用户的
                // "我到家了/我走了"自动化；下一轮（定位或 15 分钟兜底）再补，节奏不受影响。
                Timber.w("No zone list for server $serverId, withholding zone-only update")
                return
            }
            val insideZones = zonesForName
                .filter { zone ->
                    val radius = zone.attributes["radius"] as? Number
                    radius != null && runCatching { zone.containsWithAccuracy(location) }.getOrDefault(false)
                }.sortedWith(
                    compareBy<Entity> { (it.attributes["radius"] as? Number ?: Int.MAX_VALUE).toFloat() }
                        .thenBy { distanceToZoneCenter(location, it) },
                )
            val locationZone = insideZones.firstOrNull { it.attributes["passive"] as? Boolean == false }
            val locationName = locationZone?.entityId?.substringAfterLast('.') ?: ZONE_NAME_NOT_HOME

            updateLocation = UpdateLocation(
                gps = null,
                gpsAccuracy = null,
                locationName = locationName,
                // Send both `location_name` (deprecated in Core 2026.6.0) and its `in_zones` replacement so
                // the payload works against servers on either side of that change.
                inZones = if (serverManager(latestContext).getServer(serverId)?.version
                        ?.isAtLeast(2026, 6, 0) == true
                ) {
                    insideZones.map { it.entityId }
                } else {
                    null
                },
                speed = null,
                altitude = null,
                course = null,
                verticalAccuracy = null,
            )
            updateLocationString = locationName
        } else {
            updateLocation = UpdateLocation(
                gps = listOf(location.latitude, location.longitude),
                gpsAccuracy = accuracy,
                locationName = null,
                inZones = null,
                speed = location.speed.toInt(),
                altitude = location.altitude.toInt(),
                course = location.bearing.toInt(),
                verticalAccuracy = if (SdkVersion.isAtLeast(Build.VERSION_CODES.O)) {
                    location.verticalAccuracyMeters.toInt()
                } else {
                    0
                },
            )
            updateLocationString = updateLocation.gps.toString()
        }

        val now = System.currentTimeMillis()

        Timber.d("Evaluating whether to send this location update")
        val lastSend = LocationRuntimeState.lastSendMillis(serverId)
        val lastSentString = LocationRuntimeState.lastCoordinates(serverId)
        val skip = LocationSendGate.skipReason(
            now = now,
            locationTime = location.time,
            lastSend = lastSend,
            lastUpdateString = lastSentString,
            updateLocationString = updateLocationString,
            isGeofence = trigger?.isGeofence == true,
        )
        if (skip != null) {
            Timber.d("Skipping location update: $skip (last send $lastSend, fix time ${location.time})")
            logLocationUpdate(location, updateLocation, serverId, trigger, skip.historyResult())
            return
        }

        val geocodeIncludeLocation = getSetting(
            latestContext,
            GeocodeSensorManager.geocodedLocation,
            GeocodeSensorManager.SETTINGS_INCLUDE_LOCATION,
            SensorSettingType.TOGGLE,
            "false",
        ).toBoolean()

        try {
            serverManager(latestContext).integrationRepository(serverId).updateLocation(updateLocation)
            Timber.d("Location update sent successfully for $serverId as $updateLocationAs")
            // Only advance the dedupe bookkeeping after a confirmed send: advancing it beforehand makes a
            // failed send suppress the next perfectly good fix.
            LocationRuntimeState.recordSent(serverId, now, updateLocationString, stateStore)
            logLocationUpdate(location, updateLocation, serverId, trigger, LocationHistoryItemResult.SENT)

            if (geocodeIncludeLocation) {
                val intent = Intent(latestContext, SensorReceiver::class.java).apply {
                    action = SensorReceiverBase.ACTION_UPDATE_SENSOR
                    putExtra(SensorReceiverBase.EXTRA_SENSOR_ID, GeocodeSensorManager.geocodedLocation.id)
                }
                latestContext.sendBroadcast(intent)
            }
        } catch (e: Exception) {
            Timber.e(e, "Could not update location for $serverId.")
            logLocationUpdate(
                location,
                updateLocation,
                serverId,
                trigger,
                LocationHistoryItemResult.FAILED_SEND,
            )
        }
    }

    /** Maps a gate decision onto the history enum that the developer-settings screen labels. */
    private fun LocationSendGate.Skip.historyResult(): LocationHistoryItemResult = when (this) {
        LocationSendGate.Skip.FUTURE -> LocationHistoryItemResult.SKIPPED_FUTURE
        LocationSendGate.Skip.NOT_LATEST -> LocationHistoryItemResult.SKIPPED_NOT_LATEST
        LocationSendGate.Skip.OLD -> LocationHistoryItemResult.SKIPPED_OLD
        LocationSendGate.Skip.DUPLICATE -> LocationHistoryItemResult.SKIPPED_DUPLICATE
        LocationSendGate.Skip.DEBOUNCE -> LocationHistoryItemResult.SKIPPED_DEBOUNCE
    }

    private suspend fun getSendLocationAsSetting(serverId: Int): String =
        if (serverManager(latestContext).getServer(serverId)?.version?.isAtLeast(2022, 2, 0) == true) {
            getSetting(
                context = latestContext,
                sensor = backgroundLocation,
                settingName = SETTING_SEND_LOCATION_AS,
                settingType = SensorSettingType.LIST,
                entries = listOf(SEND_LOCATION_AS_EXACT, SEND_LOCATION_AS_ZONE_ONLY),
                default = SEND_LOCATION_AS_EXACT,
            )
        } else {
            SEND_LOCATION_AS_EXACT
        }

    /**
     * Zone list for [serverId]: the cached one while it is fresh, otherwise exactly one fetch.
     *
     * Returns null only when the fetch failed **and** nothing is cached. Callers must then skip their
     * zone work instead of treating "unknown" as "no zones": an empty list would fire `zone_exited` for
     * every zone the device is inside — a wrong state in Home Assistant, plus the webhook POST needed to
     * correct it. A server that genuinely has no zones caches its empty list normally, so it is not
     * re-fetched on every fix.
     */
    private suspend fun getZones(serverId: Int): List<Entity>? {
        val now = System.currentTimeMillis()
        LocationRuntimeState.cachedZones(serverId, now, zoneCacheDurationMs)?.let { return it }

        return try {
            val fetched = serverManager(latestContext).integrationRepository(serverId).getZones()
            LocationRuntimeState.storeZones(serverId, fetched, System.currentTimeMillis())
            fetched
        } catch (e: Exception) {
            Timber.e(e, "Error receiving zones from Home Assistant for server $serverId")
            // 取不到就退回过期缓存：区域列表改动很少，按旧列表判断远比按"没有区域"判断准。
            LocationRuntimeState.cachedZones(serverId, now, Long.MAX_VALUE)
        }
    }

    private suspend fun requestSingleAccurateLocation() {
        if (!checkPermission(latestContext, singleAccurateLocation.id)) {
            Timber.w("Not getting single accurate location because of permissions.")
            return
        }
        if (!isEnabled(latestContext, singleAccurateLocation)) {
            Timber.w("Requested single accurate location but it is not enabled.")
            return
        }

        val now = System.currentTimeMillis()
        val dao = sensorDao(latestContext)
        val fullSensor = dao.getFull(singleAccurateLocation.id).toSensorWithAttributes()
        val latestAccurateLocation = fullSensor?.attributes
            ?.firstOrNull { it.name == "lastAccurateLocationRequest" }?.value?.toLongOrNull() ?: 0L

        val minAccuracy = getMinimumAccuracy(singleAccurateLocation)
        val minTimeBetweenUpdates = getSetting(
            latestContext,
            singleAccurateLocation,
            SETTING_ACCURATE_UPDATE_TIME,
            SensorSettingType.NUMBER,
            "60000",
        ).toLongOrNull() ?: 60000L

        if (now < latestAccurateLocation + minTimeBetweenUpdates) {
            Timber.d("Not requesting accurate location, last accurate location was too recent")
            return
        }
        dao.add(
            Attribute(singleAccurateLocation.id, "lastAccurateLocationRequest", now.toString(), "string"),
        )

        val location = LocationRuntimeState.accurateFix(ACCURATE_FIX_REUSE_MS) { currentAccurateLocation(minAccuracy) }
        if (location == null) {
            Timber.w("Failed to get location data for single accurate sensor")
            return
        }

        Timber.d("Got single accurate location update: $location")
        getEnabledServers(latestContext, singleAccurateLocation).forEach { serverId ->
            sendLocationUpdate(location, serverId, LocationUpdateTrigger.SINGLE_ACCURATE_LOCATION)
        }
    }

    /**
     * One high-accuracy fix, with a wake lock held only while it is actually being chased. Retries until
     * the reported accuracy meets [minAccuracy], and accepts the last fix once it is within twice that,
     * mirroring the `full` flavour's retry ladder so a poor indoor signal still reports something.
     */
    private suspend fun currentAccurateLocation(minAccuracy: Int = DEFAULT_MINIMUM_ACCURACY): Location? {
        val manager = latestContext.getSystemService<LocationManager>() ?: return null
        val provider = selectProvider(manager, Purpose.ACCURATE) ?: return null

        val wakeLock = latestContext.getSystemService<PowerManager>()
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        var last: Location? = null

        // 持锁时长与工作量对齐：整个抓取会话的上限就是 重试次数 × 单次超时，再加一点余量。
        // 原来写死 10 分钟，等于一旦有哪条路径卡在锁里，屏幕熄灭的手机也会被 CPU 唤醒多留 7 分钟。
        val sessionBudgetMillis = MAX_ACCURATE_FIX_ATTEMPTS * ONE_FIX_TIMEOUT_MS + SESSION_TIMEOUT_MARGIN_MS

        try {
            wakeLock?.acquire(sessionBudgetMillis)

            val threshold = minAccuracy.coerceAtLeast(1)
            repeat(MAX_ACCURATE_FIX_ATTEMPTS) { attempt ->
                val candidate = withTimeoutOrNull(ONE_FIX_TIMEOUT_MS) { awaitOneFix(manager, provider) }
                if (candidate != null) {
                    last = candidate
                    Timber.d("Accurate fix attempt ${attempt + 1}: accuracy=${candidate.accuracy}m")
                    if (candidate.accuracy <= threshold) return@repeat
                }
            }

            val result = last ?: return null
            return if (result.accuracy <= threshold * 2f) {
                if (result.accuracy > threshold) {
                    Timber.d("No location was accurate enough, sending the closest one we got")
                }
                result
            } else {
                Timber.w("Best fix of ${result.accuracy}m is too inaccurate to report")
                null
            }
        } finally {
            if (wakeLock?.isHeld == true) wakeLock.release()
        }
    }

    /**
     * Requests exactly one fix and *always* unsubscribes again. The listener is local rather than a
     * field, so there is nothing left to leak, and the removal happens on every exit path including
     * cancellation and timeout.
     */
    private suspend fun awaitOneFix(manager: LocationManager, provider: String): Location? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        unsubscribe(this, provider)
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.success(location))
                        }
                    }
                }

                fun resumeEmpty() {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(null))
                    }
                }

                try {
                    manager.requestLocationUpdates(
                        provider,
                        0L,
                        0f,
                        listener,
                        Looper.getMainLooper(),
                    )
                } catch (e: SecurityException) {
                    Timber.e(e, "Missing permission for a one-off location")
                    resumeEmpty()
                } catch (e: IllegalArgumentException) {
                    Timber.e(e, "Provider '$provider' unavailable for a one-off location")
                    resumeEmpty()
                }

                continuation.invokeOnCancellation { unsubscribe(listener, provider) }
            }
        }

    @Suppress("DEPRECATION")
    private fun unsubscribe(listener: LocationListener, provider: String) {
        try {
            latestContext.getSystemService<LocationManager>()?.removeUpdates(listener)
        } catch (e: Exception) {
            Timber.e(e, "Failed to remove the one-off location listener for '$provider'")
        }
    }

    override suspend fun getAvailableSensors(context: Context): List<SensorManager.BasicSensor> =
        if (DisabledLocationHandler.hasGPS(context)) {
            listOf(singleAccurateLocation, backgroundLocation, zoneLocation)
        } else {
            listOf(backgroundLocation, zoneLocation)
        }

    override fun docsLink(): String = "https://companion.home-assistant.io/docs/core/location"

    override val name: Int
        get() = commonR.string.sensor_name_location

    override fun requiredPermissions(context: Context, sensorId: String): Array<String> = when {
        // Background location is separately granted from Android 10 up; Bluetooth is not needed here,
        // unlike the `full` flavour where high accuracy mode keys off connected devices.
        SdkVersion.isAtLeast(Build.VERSION_CODES.Q) -> arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )

        else -> arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    override suspend fun requestSensorUpdate(context: Context) {
        latestContext = context
        if (isEnabled(context, zoneLocation) || isEnabled(context, backgroundLocation)) {
            setupLocationTracking()
        }
        cleanupLocationHistory(context)

        val includeSensorUpdate = sensorDao(latestContext).getSettings(singleAccurateLocation.id)
            .firstOrNull { it.name == SETTING_INCLUDE_SENSOR_UPDATE }?.value ?: "false"
        if (includeSensorUpdate == "true") {
            if (isEnabled(context, singleAccurateLocation)) {
                context.sendBroadcast(createRequestAccurateLocationUpdateIntent(context))
            }
        } else {
            sensorDao(latestContext).add(
                SensorSetting(
                    singleAccurateLocation.id,
                    SETTING_INCLUDE_SENSOR_UPDATE,
                    "false",
                    SensorSettingType.TOGGLE,
                ),
            )
        }
    }

    private fun cleanupLocationHistory(context: Context) = ioScope.launch {
        handleInject(context)
        val historyDao = locationSensorManagerEntryPoint(context).locationHistoryDao()
        val historyEnabled = prefsRepository.isLocationHistoryEnabled()
        if (historyEnabled) {
            historyDao.deleteBefore(System.currentTimeMillis() - HISTORY_DURATION)
        } else {
            historyDao.deleteAll()
        }
    }

    /**
     * Feeds the developer-settings "Location tracking" screen. Without it a stalled location is
     * undiagnosable by the user *and* by whoever is reading the bug report, which is the single most
     * common complaint against a location feature.
     */
    private fun logLocationUpdate(
        location: Location?,
        updateLocation: UpdateLocation?,
        serverId: Int?,
        trigger: LocationUpdateTrigger?,
        result: LocationHistoryItemResult,
    ) = ioScope.launch {
        if (location == null || !prefsRepository.isLocationHistoryEnabled()) return@launch
        try {
            locationSensorManagerEntryPoint(latestContext).locationHistoryDao().add(
                LocationHistoryItem(
                    trigger = historyTrigger(trigger),
                    result = result,
                    latitude = updateLocation?.gps?.getOrNull(0) ?: location.latitude,
                    longitude = updateLocation?.gps?.getOrNull(1) ?: location.longitude,
                    locationName = updateLocation?.locationName,
                    inZones = updateLocation?.inZones.orEmpty(),
                    accuracy = updateLocation?.gpsAccuracy ?: location.accuracy.toInt(),
                    data = updateLocation?.toString(),
                    serverId = serverId,
                ),
            )
        } catch (e: Exception) {
            // Diagnostics must never take the app down.
            Timber.e(e, "Failed to record location history")
        }
    }

    private fun historyTrigger(trigger: LocationUpdateTrigger?) = when (trigger) {
        LocationUpdateTrigger.BACKGROUND_LOCATION -> LocationHistoryItemTrigger.FLP_BACKGROUND
        LocationUpdateTrigger.PROXIMITY_ENTER -> LocationHistoryItemTrigger.GEOFENCE_ENTER
        LocationUpdateTrigger.PROXIMITY_EXIT -> LocationHistoryItemTrigger.GEOFENCE_EXIT
        LocationUpdateTrigger.SINGLE_ACCURATE_LOCATION -> LocationHistoryItemTrigger.SINGLE_ACCURATE_LOCATION
        null -> LocationHistoryItemTrigger.UNKNOWN
    }

    private fun locationSensorManagerEntryPoint(context: Context): LocationSensorManagerEntryPoint =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            LocationSensorManagerEntryPoint::class.java,
        )

    private fun handleInject(context: Context) {
        // requestSensorUpdate is called outside onReceive, which usually handles injection.
        if (!this::prefsRepository.isInitialized) {
            prefsRepository = locationSensorManagerEntryPoint(context).prefsRepository()
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface LocationSensorManagerEntryPoint {
        fun prefsRepository(): PrefsRepository
        fun locationHistoryDao(): LocationHistoryDao
    }
}
