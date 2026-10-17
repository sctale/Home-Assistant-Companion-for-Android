package io.homeassistant.companion.android.websocket

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.annotation.VisibleForTesting
import androidx.concurrent.futures.await
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.BuildConfig
import io.homeassistant.companion.android.common.R
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.util.CHANNEL_WEBSOCKET
import io.homeassistant.companion.android.common.util.CHANNEL_WEBSOCKET_ISSUES
import io.homeassistant.companion.android.common.util.CheckLocalNetworkPermissionUseCase
import io.homeassistant.companion.android.common.util.SdkVersion
import io.homeassistant.companion.android.database.settings.SettingsDao
import io.homeassistant.companion.android.database.settings.WebsocketSetting
import io.homeassistant.companion.android.notifications.MessagingManager
import io.homeassistant.companion.android.settings.SettingsActivity
import io.homeassistant.companion.android.util.hasActiveConnection
import io.homeassistant.companion.android.webview.WebViewActivity
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class WebsocketManager(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val UNIQUE_WORK_NAME = "WebSocketManager"
        private const val OLD_UNIQUE_WORK_NAME = "WebSockManager"
        private const val SOURCE = "Websocket"
        private const val NOTIFICATION_ID = 65423
        private const val NOTIFICATION_RESTRICTED_ID = 65424
        private val DEFAULT_WEBSOCKET_SETTING = if (BuildConfig.FLAVOR ==
            "full"
        ) {
            WebsocketSetting.NEVER
        } else {
            WebsocketSetting.ALWAYS
        }
        private val ACTION_EXTRA_KEYS = listOf("uri", "behavior", "authenticationRequired")

        /** 老 unique work 名的一次性清理；每个广播都调 start() 时不必反复写 WorkManager。 */
        @VisibleForTesting
        internal var migratedLegacyWorkName = false

        /**
         * @param restartNow 调用方刚刚改变了该功能的配置（例如用户在设置里切换了 WebSocket 模式），
         * 需要立刻按新配置起跑。默认的 false 表示"只是例行报到"：此时**不要**把已经在排的周期任务推倒
         * 重来——`ENQUEUED` 对周期任务来说是等下一轮的正常状态，以前的无条件重排等于每次亮屏、每次
         * 网络状态变化都重置 15 分钟排期并重启 worker，而每次重启都是一次新的连接、订阅恢复和前台
         * 通知刷新。收通知的时效不受影响：worker 每 30 秒自己重读一次配置。
         */
        suspend fun start(context: Context, restartNow: Boolean = false) {
            val websocketNotifications =
                PeriodicWorkRequestBuilder<WebsocketManager>(15, TimeUnit.MINUTES)
                    .build()

            val workManager = WorkManager.getInstance(context)

            // Renaming the unique work name created two periodic workers and caused duplicate WebSocket notifications.
            // See: https://github.com/home-assistant/android/issues/6066#issuecomment-3608649429
            // Please don't remove before December 2026 to allow enough time for users to upgrade.
            if (!migratedLegacyWorkName) {
                workManager.cancelUniqueWork(OLD_UNIQUE_WORK_NAME)
                migratedLegacyWorkName = true
            }

            val workInfo = workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).await().firstOrNull()

            val policy = when {
                restartNow -> ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
                workInfo == null || workInfo.state.isFinished -> ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
                else -> ExistingPeriodicWorkPolicy.KEEP
            }
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                policy,
                websocketNotifications,
            )
        }

        /** 用户刚改完 WebSocket 配置时用：立刻按新配置起跑，不等下一轮排期。 */
        suspend fun startImmediately(context: Context) = start(context, restartNow = true)
    }

    private val notificationManager = applicationContext.getSystemService<NotificationManager>()!!

    @VisibleForTesting
    val entryPoint = EntryPointAccessors
        .fromApplication(applicationContext, WebsocketManagerEntryPoint::class.java)

    private val serverManager: ServerManager = entryPoint.serverManager()
    private val messagingManager: MessagingManager = entryPoint.messagingManager()
    private val settingsDao: SettingsDao = entryPoint.settingsDao()
    private val checkLocalNetworkPermission: CheckLocalNetworkPermissionUseCase =
        entryPoint.checkLocalNetworkPermission()

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WebsocketManagerEntryPoint {
        fun serverManager(): ServerManager
        fun messagingManager(): MessagingManager
        fun settingsDao(): SettingsDao
        fun checkLocalNetworkPermission(): CheckLocalNetworkPermissionUseCase
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!checkLocalNetworkPermission()) {
            Timber.d("Skipping websocket work: ACCESS_LOCAL_NETWORK permission missing")
            return@withContext Result.success()
        }
        if (!shouldWeRun()) {
            return@withContext Result.success()
        }

        if (!createNotification()) {
            return@withContext Result.success()
        }

        // Start listening for notifications
        Timber.d("Starting to listen to Websocket")
        val jobs = mutableMapOf<Int, Job>()
        if (!manageServerJobs(jobs, this)) return@withContext Result.success()

        // play ping pong to ensure we have a connection and server changes are handled.
        //
        // 一轮只评估一次该不该跑：原来 `shouldWeRun()` 和 `manageServerJobs()` 各自把每台服务器过一遍
        // `settingsDao.get` + `hasActiveConnection()`（后者还会触发连接性隐私指示），等于每 30 秒白做
        // 双份。心跳节奏本身保持 30 秒不动——那是连接保活，改了会漏通知。
        var keepRunning = true
        while (keepRunning && isActive) {
            delay(30000)
            keepRunning = manageServerJobs(jobs, this)
        }

        jobs.forEach { it.value.cancel() }
        jobs.clear()

        Timber.d("Done listening to Websocket")

        return@withContext Result.success()
    }

    private suspend fun shouldWeRun(): Boolean = serverManager.servers().any { shouldRunForServer(it.id) }

    private suspend fun shouldRunForServer(serverId: Int): Boolean {
        // This function is frequently called, so should return false early if possible and avoid doing checks for
        // specific settings that might trigger privacy indicators if the setting isn't used.

        val setting = settingsDao.get(serverId)?.websocketSetting ?: DEFAULT_WEBSOCKET_SETTING
        if (setting == WebsocketSetting.NEVER) return false

        if (!applicationContext.hasActiveConnection() || !serverManager.isRegistered()) {
            return false
        }

        if (setting == WebsocketSetting.SCREEN_ON) {
            val displayOff = applicationContext.getSystemService<PowerManager>()?.isInteractive == false
            if (displayOff) return false
        }

        if (setting == WebsocketSetting.HOME_WIFI) {
            val isHome = serverManager.connectionStateProvider(serverId).isInternal(requiresUrl = false)
            if (!isHome) return false
        }

        return true
    }

    /**
     * Brings the per-server notification jobs in line with the settings, and pings what is already
     * connected.
     *
     * @return `false` when this worker has nothing left to keep alive, which ends the 30s loop and lets
     * WorkManager's next run re-evaluate.
     *
     * 每台服务器每轮只做一次 `shouldRunForServer`（原来清理和新建各做一遍，加上外层 `shouldWeRun()`
     * 一共三轮 `settingsDao.get` + `hasActiveConnection()`）。判断结果不变，只是不再重复花钱。
     */
    private suspend fun manageServerJobs(jobs: MutableMap<Int, Job>, coroutineScope: CoroutineScope): Boolean {
        val runnable = serverManager.servers().filter { shouldRunForServer(it.id) }.map { it.id }

        // Clean up...
        jobs.filter { (serverId, _) -> serverId !in runnable }
            .forEach { (serverId, job) ->
                job.cancel()
                jobs.remove(serverId)
            }
        // Keep up existing connections...
        jobs.keys.forEach { serverId ->
            coroutineScope.launch { serverManager.webSocketRepository(serverId).sendPing() }
        }
        // Start new connections...
        runnable.filter { it !in jobs.keys }
            .forEach { serverId ->
                jobs[serverId] = coroutineScope.launch { collectNotifications(serverId) }
            }

        if (runnable.isEmpty()) return false
        return jobs.values.any { it.isActive }
    }

    private suspend fun collectNotifications(serverId: Int) {
        serverManager.webSocketRepository(serverId).getNotifications()?.collect {
            if (it.containsKey("hass_confirm_id")) {
                try {
                    serverManager.webSocketRepository(serverId).ackNotification(it["hass_confirm_id"].toString())
                } catch (e: Exception) {
                    Timber.e(e, "Unable to confirm received notification")
                }
            }
            val flattened = mutableMapOf<String, String>()
            if (it.containsKey("data")) {
                for ((key, value) in it["data"] as Map<*, *>) {
                    if (key == "actions" && value is List<*>) {
                        value.forEachIndexed { i, action ->
                            if (action is Map<*, *>) {
                                flattened["action_${i + 1}_key"] = action["action"].toString()
                                flattened["action_${i + 1}_title"] = action["title"].toString()
                                for (key in ACTION_EXTRA_KEYS) {
                                    action[key]?.let { value -> flattened["action_${i + 1}_$key"] = value.toString() }
                                }
                            }
                        }
                    } else {
                        flattened[key.toString()] = value.toString()
                    }
                }
            }
            // Message and title are in the root unlike all the others.
            listOf("message", "title").forEach { key ->
                if (it.containsKey(key)) {
                    flattened[key] = it[key].toString()
                }
            }
            serverManager.getServer(serverId)?.let { server ->
                flattened["webhook_id"] = server.connection.webhookId.toString()
            }
            messagingManager.handleMessage(flattened, SOURCE)
        }
    }

    /**
     * Create a notification to start the service as a foreground service.
     *
     * @return `true` if the foreground service was started
     */
    private suspend fun createNotification(): Boolean {
        if (SdkVersion.isAtLeast(Build.VERSION_CODES.O)) {
            val notificationChannel = NotificationChannel(
                CHANNEL_WEBSOCKET,
                applicationContext.getString(R.string.websocket_setting_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            notificationManager.createNotificationChannel(notificationChannel)
        }

        val intent = WebViewActivity.newInstance(applicationContext)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        val settingIntent = SettingsActivity.newInstance(applicationContext, SettingsActivity.Deeplink.Websocket)
        settingIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        settingIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        settingIntent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        val settingPendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            settingIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_WEBSOCKET)
            .setSmallIcon(R.drawable.ic_stat_ic_notification)
            .setContentTitle(applicationContext.getString(R.string.websocket_listening))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setGroup(CHANNEL_WEBSOCKET)
            .addAction(
                io.homeassistant.companion.android.R.drawable.ic_websocket,
                applicationContext.getString(R.string.settings),
                settingPendingIntent,
            )
            .build()
        return try {
            val type = if (SdkVersion.isAtLeast(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            } else {
                0
            }
            setForeground(ForegroundInfo(NOTIFICATION_ID, notification, type))
            true
        } catch (e: IllegalStateException) {
            if (e is CancellationException) return false

            Timber.e(e, "Unable to setForeground due to restrictions")
            if (SdkVersion.isAtLeast(Build.VERSION_CODES.O)) {
                if (notificationManager.getNotificationChannel(CHANNEL_WEBSOCKET_ISSUES) == null) {
                    val restrictedNotificationChannel = NotificationChannel(
                        CHANNEL_WEBSOCKET_ISSUES,
                        applicationContext.getString(R.string.websocket_notification_issues),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                    notificationManager.createNotificationChannel(restrictedNotificationChannel)
                }
            }
            val restrictedNotification = NotificationCompat.Builder(applicationContext, CHANNEL_WEBSOCKET_ISSUES)
                .setSmallIcon(R.drawable.ic_stat_ic_notification)
                .setContentTitle(applicationContext.getString(R.string.websocket_restricted_title))
                .setContentText(applicationContext.getString(R.string.websocket_restricted_fix))
                .setContentIntent(settingPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(NOTIFICATION_RESTRICTED_ID, restrictedNotification)
            false
        }
    }
}
