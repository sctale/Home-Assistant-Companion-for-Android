# Android 16 耗电复查（v0.1.2）

本次复查的约束：**不靠降低检测频率或牺牲精度来省电**。这份文档因此把每个后台唤醒点分成两类：

- **检测节奏**：用户设定的上报间隔、最小位移、精度阈值、心跳保活 —— 一律不动；
- **浪费**：同样多的检测次数里多花的唤醒、重复的网络往返、值没变也做的写入、被系统掐断后的重试 —— 全部消灭。

判定基线：`compileSdk 37` / `targetSdk 36` / `minSdk 23`（`gradle/libs.versions.toml`），只发布 `minimal`
flavor（无 Play 服务、无 FCM，见 `README.md`）。所有 Play 服务相关代码路径在 `full` 里，本 flavor 不产生唤醒。

## 1. 唤醒源清单

| 唤醒源 | 位置 | 周期 / 触发 | 单次成本 | 判定 |
| --- | --- | --- | --- | --- |
| 后台定位订阅 | `app/src/minimal/.../LocationSensorManager.kt` `requestLocationUpdates()` | 用户设定，默认 300s（可调下限 60s）+ 批处理上限 300s + 最小位移 100m | 一次定位 | **v0.1.2 调快了**：见 §3 |
| 每帧定位的上报 | 同上 `sendLocationUpdate()` | 每帧 × 每服务器 | 1 次 webhook POST（多 URL 轮试） | **已修**：门控此前跨实例失效，静止时每帧都发 |
| 区域列表拉取 | 同上 `getZones()` | 原本每帧 × 每服务器 | 1 次全量 GET | **已修**：4 小时缓存此前永不命中 |
| 区域归属判定 | 同上 `handleZoneEvaluation()` | 每帧 | 本地计算 | 保留；顺带修好"区域进入/离开事件从未发出" |
| 系统围栏 | 同上 `requestZoneProximityAlerts()` | 每次 setup | 撤销+重挂 N 个告警 | **已修**：按指纹去重，集合不变一个系统调用都不发 |
| 重新注册订阅 | 同上 `setupLocationTracking()` | 每次传感器扫描 | 硬件重新评估请求 | **已修**：按 provider/间隔/位移指纹去重 |
| 传感器扫描 | `common/.../SensorReceiverBase.kt` `updateSensors()` | 每次匹配的广播 + 15 分钟周期 | 33 个 manager 采集 + 每服务器一次 sweep | 保留（采集/上报），**限频其中一项** |
| 实体注册表 `get_config` | `SensorReceiverBase.syncSensorsWithServer()` | 原本每次广播 | **大 payload**（components 全表 + 实体注册表） | **已修**：30 分钟限频，且不改状态上报 |
| 广播生命周期 | `SensorReceiverBase.onReceive()` | 每次广播 | 整轮扫描 | **已修**：改用 `goAsync()`，不再被系统掐断后重试 |
| 周期传感器任务重排 | `app/.../SensorWorker.kt` `start()` | 每次进 App | 立刻多跑一轮扫描 | **已修**：`CANCEL_AND_REENQUEUE` → `KEEP` |
| WebSocket 心跳 | `app/.../websocket/WebsocketManager.kt` | 30s/轮 | 每服务器 1 个 ping | 保留（保活），**每轮的重复判定减半** |
| WebSocket 重连 | `common/.../WebSocketCoreImpl.kt` | 原本固定 10s，失败无上限 | 一次 TLS 握手 + 认证 + 重新订阅 | **已修**：10→20→40→60s 封顶，连上即复位 |
| 通知/传感器上报的 WakeLock | `LocationSensorManager.requestSingleAccurateLocation()` | 按需（服务器请求精确位置） | PARTIAL_WAKE_LOCK，最长 10 分钟 | 保留超时（工作真需要），见 §4 待办的自激风险 |
| 灭屏/省电模式/网络状态广播 | `HomeAssistantApplication.kt` 121–334 | 系统事件 | 每类 action 一次注册，**未见重复投递** | 保留（事件驱动本身就是省电做法） |
| `ACTION_TIME_TICK` 每分钟全量扫描 | `HomeAssistantApplication.kt` 318–333 | 仅当用户选择 FAST_* | 整轮 sweep | 不动（用户显式选项） |
| BLE 信标扫描 | altbeacon `BeaconService`（合并清单里 type=location） | 1100ms 扫 / 500ms 停 | 持续射频 | 不动：只在用户启用信标传感器时启动 |
| 桌面小部件周期刷新 | `app/src/main/res/xml/*_widget_info.xml` 的 `updatePeriodMillis` | 实体/模板/媒体 30 分钟、相机 1 小时、按钮 24 小时 | 每个已放置的小部件一次 HTTP | 不动：只在用户真的放了小部件时才存在，且 30 分钟已是该系统 API 的下限，再压就会让小部件显示过期状态 |
| 唤醒词监听 | `app/.../assist/service/AssistVoiceInteractionService.kt` | 启用后持续听 | 麦克风 + 本机推理 | 不动：用户显式启用，设置里已写明"会增加耗电量"，并有独立的测试/关闭入口 |
| 通知命令的 `FULL_WAKE_LOCK` | `MessagingManager.kt` `COMMAND_SCREEN_ON` | 仅在自动化下发时 | 亮屏 30s 意图 | 见 §4 |

## 2. 这次真正修掉的浪费

1. **定位门控与区域缓存跨实例存活**（最大一处）。Android 上 manifest 注册的 `BroadcastReceiver`
   **每次广播都是新实例**，而后台定位正是以 `PendingIntent` 广播送达的。上一版把"上次发送时间/坐标"、
   区域列表缓存、区域归属基线放在实例字段里，于是每帧读到的都是空 map：去重/防抖/非最新三条永远为假
   ⇒ 静止不动也在每帧对每个服务器发一次 POST；4 小时区域缓存永不命中 ⇒ 每帧再多一次全量 GET；
   基线恒空 ⇒ `zone_entered` / `zone_exited` 一次都没发出去过。新增 `LocationRuntimeState`
   （进程内共享 + 写穿 SharedPreferences），门控与基线跨进程复活仍有效。
   单测 `LocationRuntimeStateTest` 8 例覆盖"活得过实例、活得过进程复活、区分从没记过与记成空集"。
2. **区域列表不再"取不到就当没有"**。`getZones()` 改为 null-on-unknown：先沿用过期缓存，两者都无则返回
   null，调用方跳过本轮区域判断。原实现把空列表缓存 4 小时，等于宣布"离开了所有区域"——既触发错的
   自动化，又要多发一次纠正用的上报。「仅上报区域」的隐私档在区域不可知时不发这一帧（而不是发一个错的
   `not_home`），下一帧照发。
3. **围栏与订阅按指纹去重**。`requestZoneProximityAlerts` 原来"先全删再重建"；`requestLocationUpdates`
   每次扫描都重发一遍同样的请求。现在配置/区域集合没变就一个系统调用都不发；拿不到区域列表时不再清空
   已有告警（那是白丢一段围栏唤醒）。指纹按"数字在前、键在后"编码，键里出现分隔符也拆不开。
4. **`get_config` 限频 30 分钟**。它是 sweep 里唯一的大 payload，却挂在每一次亮/灭屏后面。它的唯一用途是
   `coreSensorStatus`（核心侧启用/停用），不参与"值有没有变"的比较（那用本地 `lastSentState`），所以限频
   不推迟任何状态上报；未注册传感器的立刻注册这条逃生门保留。
5. **广播 `goAsync()`**：整轮扫描现在活在自己的生命周期里，不再被广播超时掐断成"失败—下轮重试"。
6. **WebSocket**：重连指数退避（服务器长时间不可达时不再每 10 秒一次 TLS 握手，恢复后最长等 60 秒）；
   每轮心跳不再把 `settingsDao.get` + `hasActiveConnection()` 跑三遍（后者还会触发连接性隐私指示）。
7. **周期任务不被 App 打开重排**：`KEEP` 取代 `CANCEL_AND_REENQUEUE`，15 分钟节奏不变但少掉每次进 App
   的额外一轮全套扫描。

7. **精确位置请求合流**（v0.1.3）：一次精确抓取最多是 5 × 30 秒的全速 GPS，并且期间持有 partial wake
   lock。相邻的进入+离开（边界抖动）、或服务器要求与围栏触发叠在一起时，原来会**各跑一轮**。现在同一
   进程内串成一个会话、15 秒窗口内直接复用刚测到的结果（失败的会话同样不立即重来——它一样烧满了射频）。
   持锁时长也从写死的 10 分钟改为"重试次数 × 单次超时 + 余量"，对齐实际工作量。3 例
   `AccurateFixCoalescingTest` 覆盖合流语义。合流不降低精度：拿到的仍是几秒前实测的高精度定位。

## 3. 频率旋钮：这次反而是往回调快的

v0.1.0 为了压住泄漏，把默认上报间隔压到 15 分钟、批处理上限 20 分钟、可调下限 5 分钟——这属于"少测来
省电"，v0.1.2 把它反过来：

| 旋钮 | v0.1.0/0.1.1 | v0.1.2 | 说明 |
| --- | --- | --- | --- |
| 默认定位间隔 | 900s | **300s** | 恢复成更密的轨迹 |
| 可调下限 | 300s | **60s** | 想设 1 分钟现在允许 |
| 批处理上限 | 1200s | **300s** | 只合并同一周期内已到期的结果，不再额外拖 20 分钟 |
| 最小位移 | 100m | 100m | 未动：静止时平台本来就没有新位置可给 |
| 精度阈值 | 200m | 200m | 未动，且用户可调 |
| 去重/防抖窗口 | 15min / 5s | 15min / 5s | 未动：只在"坐标与上次完全一样"时省掉一次上报 |
| WebSocket 保活 | 30s | 30s | 未动：那是连接保活，不是检测 |
| 传感器周期任务 | 15min | 15min | 未动 |
| 区域列表缓存 | 4h | 4h | 未动（v0.1.2 才真正开始生效） |

区域边界不再靠轮询：系统围栏（`addProximityAlert`）在 v0.1.2 才真的工作起来，进入/离开是事件驱动的，
与上面的间隔无关。代价核算的立足点是：**每一帧的实际成本降下来了，所以同样的频率不再等于同样的耗电。**

### 试过去但退回去的一项

`onSensorUpdated`（`common/.../SensorManager.kt`）确实是无条件 `update` + `replaceAllAttributes`。试着在
共享层加"值没变就跳过"之后，`AudioSensorManagerTest` 的
`Given mismatched attributes when requesting update three times then cache kicks in after force update succeeds`
暴露出上游**已经**在部分 manager 里用 `getFull(sensorId)` 做了同样的比对——共享层再加一遍就变成每轮两次
查询（断言从 3 次变 6 次）。正确做法是让还没做缓存的 manager 各自照 `AudioSensorManager` 的模式补，而不是
在共用函数里加一层。已回退，记在这里免得有人再走一遍。

## 4. 待办（诚实记录，已量化但本次没改）

| 项 | 位置 | 为什么本次没改 |
| --- | --- | --- |
| 部分 manager 每轮重写属性表（值没变也写） | `SensorManager.onSensorUpdated` + 各 manager | 见 §3 末尾的"试过去但退回去"：共享层加跳过会与上游 manager 级缓存重复，正确做法是按 `AudioSensorManager` 的模式逐个补 |
| sweep 里每 (传感器 × 服务器) 一次 `getFull`（LEFT JOIN 全部属性，约 128 次/轮） | `SensorReceiverBase.kt` 240 | 本地查询，单次微秒级，与射频唤醒不在一个量级；批量化要改 DAO 返回形状 |
| `COMMAND_SCREEN_ON` 用已废弃的 `FULL_WAKE_LOCK` 且 `acquire(30s)` 后立刻 `release()`（超时形参被忽略） | `MessagingManager.kt` 822–829 | 只在自动化显式下发时触发，不是后台常驻唤醒；换 `FLAG_TURN_SCREEN_ON` 会改变用户可见行为 |
| 多处主线程 `runBlocking` 做网络 | `ServerSettingsPresenterImpl.kt` 32/54/128 等 | ANR/卡顿问题，不是耗电源，另开一轮 |
| `minimal` 仍打包 `cronet-embedded` native 库，未见 16KB 页对齐配置 | `AndroidApplicationDependenciesConventionPlugin.kt` 73–76 | 影响安装/加载而非续航；需要真机确认该设备是否 16KB 模式 |

## 5. 真机上怎么验证这些改动有效

```
adb shell dumpsys batterystats --reset          # 装完新版先清零
# 正常使用 12–24 小时后：
adb shell dumpsys batterystats --charged        # 人读版：按 wake lock / 射频 / 定位归因
adb shell dumpsys batterystats --checkin | grep -E "wakeup|AlarmManager|WifiManager|LocationManager"
```

要看到的预期变化：

- **定位上报**：App 内 设置 → 传感器 → 后台位置 → 开发者选项里的「位置跟踪」应出现
  `已跳过（重复 / 早于上次上报 / 更新过于频繁）` 这类条目。静止一晚上，`已发送` 应该很少而跳过很多——
  这正是门控生效的表现；v0.1.1 及之前全是 `已发送`。
- **区域事件**：到家/离家应真的产生 `android.zone_entered` / `zone_exited`（此前一次都没有）。
- **网络请求数**：`batterystats` 里本包的 `Network stats` / wifi radio 时间应明显下降；`get_config` 从
  "每次亮屏一次"变成每 30 分钟最多一次。
- **仍在的量**：定位上报频率等于你设定的间隔，WebSocket 保活每 30 秒一次 —— 这两项**没变**，是按你的
  要求保住的。

如果装上后仍然掉电快，请开一个 issue 并附上 `dumpsys batterystats` 里本包那一节——继续排查的顺序应按
"哪个 kernel wake-up / 哪个
wakelock 次数最多"继续查；§4 那几条就是按数据排序的下一步。
