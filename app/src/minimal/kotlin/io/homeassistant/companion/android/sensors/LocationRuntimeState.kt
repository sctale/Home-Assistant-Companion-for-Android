package io.homeassistant.companion.android.sensors

import android.location.Location
import io.homeassistant.companion.android.common.data.integration.Entity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * The location feature's state, deliberately kept outside [LocationSensorManager].
 *
 * A manifest-registered `BroadcastReceiver` is instantiated fresh for **every** broadcast, and that is
 * exactly how a background fix arrives (`PendingIntent.getBroadcast` → `onReceive`). Any state stored in
 * instance fields is therefore empty again before each fix — which silently disabled every guard here:
 *
 * - `lastSend = 0` and no previous coordinate string ⇒ the duplicate / debounce / not-latest gates in
 *   [LocationSendGate] can never fire, so **every** fix costs one webhook POST per server;
 * - the four-hour zone cache never hits, so every fix *additionally* fetches the complete zone list per
 *   server (two HTTP round trips per fix per server, forever);
 * - no zone baseline ⇒ `android.zone_entered` / `zone_exited` were never reported at all.
 *
 * That makes this the largest remaining power cost in the shipped flavour *and* the reason the zone
 * feature did not work. Fixing it costs nothing in timeliness or accuracy: the guards decide whether
 * the *content* changed, not how often the device measures.
 *
 * Durability is split on purpose:
 * - the zone **payload** cache and the fetch timestamp live in memory only — losing them costs a single
 *   GET, and persisting a zone list would risk acting on a week-old one;
 * - the **dedupe gate** and the **last known zone membership** are written through [Store], because a
 *   process Android froze and later revived must not re-send a position the server already has, and must
 *   not fabricate `zone_entered` for a zone the phone has been inside the whole time.
 */
internal object LocationRuntimeState {

    /**
     * Write-through target for the durable facts. Implemented on SharedPreferences in the app and on a
     * plain map in unit tests, so the gate's behaviour across process death is testable without Robolectric.
     */
    interface Store {
        fun lastSendMillis(serverId: Int): Long

        fun lastCoordinates(serverId: Int): String?

        fun writeLastSend(serverId: Int, whenMillis: Long, coordinates: String)

        /** Null means "never recorded" — distinct from a recorded empty set. */
        fun zoneMembership(serverId: Int): Set<String>?

        fun writeZoneMembership(serverId: Int, entityIds: Set<String>)
    }

    private val lock = Any()
    private val primed = mutableSetOf<Int>()
    private val lastSend = mutableMapOf<Int, Long>()
    private val lastCoordinates = mutableMapOf<Int, String?>()
    private val membership = mutableMapOf<Int, Set<String>>()
    private val zonePayload = mutableMapOf<Int, List<Entity>>()
    private val zoneFetchedAt = mutableMapOf<Int, Long>()

    /**
     * Fingerprint of the background subscription currently registered with the platform (provider,
     * cadence, minimum distance and the flags that enabled it).
     *
     * Every sensor sweep ends up in `setupLocationTracking`, and re-issuing an identical
     * `requestLocationUpdates` makes the location hardware re-evaluate the request for nothing - the
     * detection cadence is unchanged either way. In-process only on purpose: after a process revival the
     * app re-asserts the subscription once, which is the safe behaviour if the platform dropped it.
     */
    @Volatile
    var subscriptionSignature: String? = null

    /** Loads the durable part for [serverId] once per process. Cheap to call repeatedly. */
    fun prime(serverId: Int, store: Store) {
        synchronized(lock) {
            if (serverId in primed) return
            primed.add(serverId)
            lastSend[serverId] = store.lastSendMillis(serverId)
            store.lastCoordinates(serverId)?.let { lastCoordinates[serverId] = it }
            store.zoneMembership(serverId)?.let { membership[serverId] = it }
        }
    }

    fun lastSendMillis(serverId: Int): Long = synchronized(lock) { lastSend[serverId] ?: 0L }

    fun lastCoordinates(serverId: Int): String? = synchronized(lock) { lastCoordinates[serverId] }

    /** Only call this after the server accepted the update; advancing early would swallow the next fix. */
    fun recordSent(serverId: Int, whenMillis: Long, coordinates: String, store: Store) {
        synchronized(lock) {
            lastSend[serverId] = whenMillis
            lastCoordinates[serverId] = coordinates
        }
        store.writeLastSend(serverId, whenMillis, coordinates)
    }

    /** Null means "no baseline yet", in which case the caller must not report any transition. */
    fun zoneBaseline(serverId: Int): Set<String>? = synchronized(lock) { membership[serverId] }

    fun setZoneBaseline(serverId: Int, inside: Set<String>, store: Store) {
        synchronized(lock) { membership[serverId] = inside }
        store.writeZoneMembership(serverId, inside)
    }

    /** The cached zone list, or null when nothing is cached / the entry is older than [maxAgeMillis]. */
    fun cachedZones(serverId: Int, nowMillis: Long, maxAgeMillis: Long): List<Entity>? = synchronized(lock) {
        val fetchedAt = zoneFetchedAt[serverId] ?: return@synchronized null
        if (nowMillis - fetchedAt > maxAgeMillis) return@synchronized null
        zonePayload[serverId]
    }

    fun storeZones(serverId: Int, zones: List<Entity>, nowMillis: Long) {
        synchronized(lock) {
            zonePayload[serverId] = zones
            zoneFetchedAt[serverId] = nowMillis
        }
    }

    /**
     * Called when a server is deleted or its location sensor is switched off: without this, the maps and
     * the written-through SharedPreferences entries of a removed server would live on forever, and the
     * next re-added server id could inherit its stale gate.
     */
    fun drop(serverId: Int) {
        synchronized(lock) {
            primed.remove(serverId)
            lastSend.remove(serverId)
            lastCoordinates.remove(serverId)
            membership.remove(serverId)
            zonePayload.remove(serverId)
            zoneFetchedAt.remove(serverId)
            // 订阅指纹与服务器有关（少一台就可能不再需要订阅），保守地作废它。
            subscriptionSignature = null
        }
    }

    /** Test seam. Production code reaches this only through [drop]. */
    fun reset() {
        synchronized(lock) {
            primed.clear()
            lastSend.clear()
            lastCoordinates.clear()
            membership.clear()
            zonePayload.clear()
            zoneFetchedAt.clear()
            subscriptionSignature = null
            lastAccurateFixAt = 0L
            lastAccurateFix = null
        }
    }

    /**
     * 精确位置请求的**合流**。
     *
     * 一次精确抓取最多是 `MAX_ACCURATE_FIX_ATTEMPTS × ONE_FIX_TIMEOUT` 的全速 GPS（服务器要求精确位置、
     * 围栏进入/离开都会触发）。相邻的 enter+exit、或者服务器连发两次要求，如果各跑一轮，就是两倍射频
     * 时间换同一个答案。这里用一把锁把它们串起来：后到的请求在结果还"新鲜"的窗口里直接复用前一个的
     * 结果，不再自己跑一轮。
     *
     * 这不是降低精度——拿到的仍是刚测出来的高精度定位；省的是重复的抓取。
     */
    private val accurateMutex = Mutex()
    private var lastAccurateFixAt = 0L
    private var lastAccurateFix: Location? = null

    suspend fun accurateFix(freshWindowMillis: Long, session: suspend () -> Location?): Location? =
        accurateMutex.withLock {
            val now = System.currentTimeMillis()
            // 只看"上一次抓取发生在什么时候"，不看结果成不成功：一次没抓到位置的 GPS 会话同样烧满了
            // 射频时间，紧接着再来一轮才是最该避免的事。
            if (lastAccurateFixAt != 0L && now - lastAccurateFixAt < freshWindowMillis) {
                Timber.d("Reusing the accurate fix another request just chased (${now - lastAccurateFixAt}ms old)")
                return@withLock lastAccurateFix
            }
            session().also {
                lastAccurateFix = it
                lastAccurateFixAt = now
            }
        }
}

/**
 * One platform proximity alert the app wants registered, and the fingerprint it is stored by.
 *
 * The fingerprint (key + centre + radius) is what lets the manager notice that nothing changed and skip
 * the whole teardown-and-rebuild. Re-registering identical alerts on every broadcast was pure waste: it
 * costs system geofence operations without making a single boundary get detected any sooner.
 */
internal data class ProximityAlert(val key: String, val latitude: Double, val longitude: Double, val radius: Float) {
    /**
     * 数字在前、键在最后，解析用 `limit = 4`：区域实体名今天不带 `|`，但万一带上也不会把指纹拆坏——
     * 指纹拆不开就等于每次广播都重挂一遍系统告警，正是要避免的那种浪费。
     */
    fun spec(): String = "$latitude|$longitude|$radius|$key"

    companion object {
        fun fromSpec(spec: String): ProximityAlert? {
            val parts = spec.split('|', limit = 4)
            if (parts.size != 4) return null
            val latitude = parts[0].toDoubleOrNull() ?: return null
            val longitude = parts[1].toDoubleOrNull() ?: return null
            val radius = parts[2].toFloatOrNull() ?: return null
            return ProximityAlert(parts[3], latitude, longitude, radius)
        }

        /** 撤注册只需要当初那个 PendingIntent 的键，不必把整条指纹解析出来。 */
        fun keyFromSpec(spec: String): String? = spec.split('|', limit = 4).getOrNull(3)
    }
}
