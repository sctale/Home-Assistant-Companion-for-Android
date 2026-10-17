# 与上游的差异清单

给要读代码或要合并上游更新的人。上游 = `home-assistant/android`；本文的对齐基线是该 fork 最后一次同步
上游的提交 `cae8833b9`（对应官方 2026.6.6 周更）。

除下列文件外，本仓库与上游一致。检查方法：

```bash
git fetch upstream main
git diff --stat cae8833b9 -- . ':!app/src/screenshotTestFullDebug'
```

`app/src/screenshotTestFullDebug/` 下 2000 多个参考图与上游一致，只是文件名很长，在 Windows 上检出需要
给该仓库设 `core.longpaths true`。

## 与上游真正不同的文件

| 文件 | 差异 | 上游合并时的处理建议 |
| --- | --- | --- |
| `app/src/minimal/kotlin/.../sensors/LocationSensorManager.kt` | **本项目的主要实现**：上游该文件在 `minimal` flavor 里是空壳（`onReceive` / `getAvailableSensors` 全 Noop），我们把它实现成了纯 `android.location` 版本 | 上游若改的是 `app/src/full/` 那份，几乎无冲突；若上游给 minimal 补了实现，以上游为准并对比本文件的省电策略 |
| `app/src/minimal/google-services.json` | 新增，无凭据占位配置，只为满足 `com.google.gms.google-services` 插件（该插件对所有 flavor 生效）。上游在已删除的 CI step 里生成它 | 保留；上游若新增 applicationId，需要同步往这个文件里加条目 |
| `build-logic/.../AndroidApplicationConventionPlugin.kt` | 拆分 `NAMESPACE`（保持上游包名，源码树与清单里的相对组件名依赖它）与 `APPLICATION_ID`（`dev.sctale.homeassistant.companion`）。其余与上游一致 | 上游若动 `namespace` 或签名配置，手工合并，别覆盖掉这个拆分 |
| `common/src/main/res/values/strings.xml` | 追加 8 条本项目新增的定位设置项文案 | 上游该文件由 Lokalise 管理；只追加，不改不删既有 key |
| `common/src/main/res/values-zh-rCN/strings.xml` | 新增，1246 条简体中文（覆盖上游 84%，v0.1.1 补齐设置界面 233 条）。上游不入库这个文件（`.gitignore` 忽略翻译产物，由已删除的 Lokalise action 生成），我们显式反忽略并跟踪 | 上游新增 key 时这里不会自动补齐，未翻译的回退英文（`node tools/check-translations.js` 会报缺口与占位符错误）。若以后要恢复官方翻译流程，先删掉本文件再接上游流水线 |
| `common/src/main/res/xml/locales_config.xml` | 增加 `zh-CN`（上游只有 `en`） | 上游若改为生成此文件，改为在生成逻辑里加 zh-CN |
| `.gitignore` | 三处反忽略规则：`!app/src/minimal/google-services.json`、`!wear/src/google-services.json`、`!automotive/src/google-services.json`（占位配置需入库）、`!common/src/main/res/values-zh-rCN/strings.xml`；新增 `keystore/` | 直接保留 |
| `README.md` / `CHANGELOG.md` / `NOTICE` / `docs/CHANGES-VS-UPSTREAM.md` | 本项目的说明、溯源与 Apache-2.0 §4(b) 修改声明 | 保留 |
| `app/src/main/res/xml/changelog_master.xml` | 改为记录本项目的版本（上游记录的是 2026.6.6） | 保留 |
| `common/src/main/kotlin/.../sensors/SensorReceiverBase.kt` | **改了上游共享代码**：`onReceive` 改 `goAsync()`（并抽出 `handleIntent`）；`get_config` 按 `REGISTRY_SYNC_INTERVAL`（30 分钟）限频，`coreSensorStatus` 允许为 null | 上游若重构 sweep，务必保住两点：广播要 `goAsync`；限频只能限"注册表对齐"，不能把 SRB 里"值变了才上报"那段一起圈进去（判断依据见 docs/POWER-REVIEW-ANDROID16.md） |
| `common/src/main/kotlin/.../websocket/impl/WebSocketCoreImpl.kt` | 重连由固定 10 秒改为 10→20→40→60 秒封顶、连上即复位；URL 变更仍立即重连 | 上游若改重连策略，保留退避；只在全新连接/URL 变更处复位计数 |
| `app/src/main/kotlin/.../websocket/WebsocketManager.kt` | 每轮心跳只做一次"该不该跑"判断（原来 `shouldWeRun()` 与 `manageServerJobs()` 各跑一遍，清理/新建又各查一遍）；30 秒保活间隔与 `WebsocketSetting` 语义不变 | 上游若调整循环结构，注意不要把每服务器的 `settingsDao.get` + `hasActiveConnection()` 恢复成多轮（后者会触发连接性隐私指示） |
| `app/src/main/kotlin/.../sensors/SensorWorker.kt` | `enqueueUniquePeriodicWork` 的策略由 `CANCEL_AND_REENQUEUE` 改为 `KEEP`（进 App 不再重排周期任务） | 保留；若上游要靠重排刷新请求定义，改成"按 WorkInfo 状态选择策略"（参考 `WebsocketManager.start`） |
| `common/src/main/kotlin/.../sensors/SensorSweepCoalescer.kt` | 新增，并把 `updateSensors` 的整轮扫描包进去：同一时刻只跑一轮 + 至多一轮补跑 | 上游若重构扫描入口，保留 `SensorSweepCoalescerTest` 钉住的两条：顺序请求必须各自执行、异常轮必须释放状态 |
| `app/src/minimal/kotlin/.../sensors/LocationRuntimeState.kt` | 新增：定位去重门控、区域归属基线（写穿 SharedPreferences）与区域列表缓存的进程级存放处，外加围栏指纹 `ProximityAlert`、精确位置抓取合流 | 上游无对应物（其 minimal 是空壳）；若将来上游给 minimal 补实现，以上游为准并对照本文件检查"状态是否活得过 receiver 实例" | 新增：定位去重门控、区域归属基线（写穿 SharedPreferences）与区域列表缓存的进程级存放处，外加围栏指纹 `ProximityAlert` | 上游无对应物（其 minimal 是空壳）；若将来上游给 minimal 补实现，以上游为准并对照本文件检查"状态是否活得过 receiver 实例" |
| `docs/POWER-REVIEW-ANDROID16.md` | 新增：Android 16 耗电复查（唤醒源清单、保留的频率旋钮、待办、真机验证方法） | 保留 |
| `scripts/verify.sh`、`scripts/build-release.sh`、`scripts/release-app.ps1`、`tools/*`（编码/翻译/16KB 对齐自检）、`tools/mock-google-services.json` | 本项目的本地门禁、构建与一键发布入口。上游用 GitHub Actions 承担同样职责 | 保留；本项目的产物一律本地构建，不引入 hosted CI |

## 明确"不做"的上游内容

这几项是有意为之，不是遗漏，合并时不要"顺手补回来"：

- **不恢复上游的 `.github/workflows/*`。** 上游有 27 个文件 / 2209 行的 CI（ktlint、Android Lint、单测、
  instrumentation、emulator.wtf、截图回归、许可证校验、lockfile 校验、发布流水线）。本项目的产物按惯例
  只走本地构建，等价职责由 `scripts/verify.sh` 承担。
  注意这一条的代价：`renovate.json5` 与 `fastlane/` 仍留在仓库里，但它们依赖的 workflow 已不存在，
  属于悬空配置；上游依赖自动升级时 lockfile 不会再生，而 `build.gradle.kts` 开着
  `dependencyLocking { lockAllConfigurations() }`。要恢复 Renovate 就得同时恢复 lockfile 工作流。
- **不内置任何 Firebase 凭据。** `minimal` flavor 不链接 FCM，因此没有推送通知；`full` flavor 需要使用者
  自己提供 `app/google-services.json`。崩溃与分析数据不发往任何第三方项目。
- **不实现 in-app 自更新。** 带外 APK 更新链无法保证完整性校验，安装渠道只有 GitHub Release。
- **不实现 `minimal` flavor 的高精度模式。** 官方实现依赖 Play 服务的融合定位与前台服务；不提供空壳开关。
- **不使用高德 SDK。** 原 fork 的高德链路已经不可达（依赖被注释、转换函数零调用），本项目的定位走系统 API。

## 坐标系：真机对照的结论

`android.location` 的 `NETWORK_PROVIDER` 在国内厂商 ROM 上返回 WGS-84 还是 GCJ-02，代码层面判定不了。
真机对照已经给出答案：本项目上报的位置与 **Traccar** 记录的车辆轨迹重合，而 Traccar 存的是设备原始
GPS、服务端不做坐标转换，也就是 WGS-84 —— 两条线叠在一起，说明本机走系统 API 拿到的就是 WGS-84，
**不需要**任何转换。判定依据留着，换机型时按同一方法复核：

- 纯 `GPS_PROVIDER` 是卫星定位，输出 WGS-84，**不需要**转换；
- 若某 ROM 的 network 定位输出 GCJ-02，则 `device_tracker` 会有 250–620 m 的稳定偏差（北京约 555 m、
  上海约 481 m、广州约 621 m），而且和 GPS 路径混在同一条链路上时最难诊断 —— 这时候要看
  开发者选项 → 位置跟踪 里那条记录的触发来源，才能确定是哪条路径偏了。

本项目不引入原 fork 的 `GCJ2WGS.kt`，原因见 `CHANGELOG.md`：它缺少 `outOfChina` 判域，境内一阶反算误差
只有 0.3–2.4 m 可以接受，但出境会被人为偏移 150 m–3.3 km（东京 378 m、巴黎 1229 m、纽约 3264 m）。
如果实测确认需要转换，应当带着判域和测试向量重新实现，而不是恢复那份代码。

还有一个容易忽略的配合项：**zone 的取点必须和上报坐标同系**。上报是 WGS-84，而照高德/腾讯这类
GCJ-02 图源点的 zone 会差几百米，表现为走进院子才报"在家"、离开很远才报"离家"。官方客户端在国内
遇到的同类问题就是这个来源。
