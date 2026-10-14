# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，
版本号遵循 [SemVer](https://semver.org/lang/zh-CN/)。

## 0.1.0

本版本 fork 自 <https://github.com/nesror/Home-Assistant-Companion-for-Android>
（作者 nesror 的"Home Assistant 官方 app 适配国内环境版"），代码基线为该 fork 最后一次与上游同步后的
提交 `ef6eadd57`（对应上游 `home-assistant/android` 2026.6.6 周更，提交 `cae8833b9`）。

感谢 Home Assistant 官方团队写出并持续维护这个客户端，也感谢 nesror 先行验证了"国内无 GMS 设备"这个
方向并提供了一批简体中文文案。本版本对这些前作没有任何权利主张，全部来源见 README 与
[docs/CHANGES-VS-UPSTREAM.md](docs/CHANGES-VS-UPSTREAM.md)。

### 安全与供应链

- 移除仓库内的签名密钥（`nestor.keystore`）。它原先违反 `.gitignore` 被强制提交，而构建 release 需要
  环境变量里的口令——意味着除原作者外无人能复现官方 Release 的签名，且这把 key 无法再轮换。
  本项目改用独立包名 + 独立签名密钥，密钥与口令不入库。
- 移除仓库内的真实 Firebase 配置（`app/google-services.json`）。原先所有安装者的崩溃日志与分析数据
  都会上报到作者个人的 Firebase 项目 `fir-test-ab83f`，而这恰恰是本 fork 声称要摆脱的厂商链路。
- 删除整条不可达的自更新实现（`update/` 包约 600 行：GitHub Release 抓取、下载、拉起安装、
  蒲公英通道残留）。它没有任何调用点，却仍然申请 `REQUEST_INSTALL_PACKAGES`、导出一个不校验来源与
  download id 的 `DownloadReceiver`、把 `FileProvider` 根路径放大到 `root-path path="."`、并在进程级
  清空 StrictMode VmPolicy。安装渠道现在只有 GitHub Release。
- 关闭应用级 `usesCleartextTraffic="true"`。它原先把整个 App 的 HTTP 通信降级为允许明文，
  影响面远超更新场景；上游的 `network_security_config` 仍然生效。
- 移除清单里的悬空组件 `.notifications.NotificationDeleteReceiver`（指向一个不存在的类）与无对应
  Android API 的 `ScopedStorage` meta-data。
- 移除代码中从未使用的敏感权限 `CHANGE_WIFI_STATE`、`ACCESS_LOCATION_EXTRA_COMMANDS`。
- 移除注入全局 OkHttpClient 的 `RedirectInterceptor`（为已删除的死功能服务，却让每一笔 HA 请求都经过它）。
- 移除经纬度的 error 级日志输出。
- 删除高德定位 SDK 的死代码：依赖已注释、`GCJ2WGS.kt` 被 import 但全仓零调用、清单仍声明
  `com.amap.api.location.APSService`、设置界面仍保留"高德Key"输入框。

### 修复

- **修复后台定位监听器只注册不注销导致的持续耗电与位置冻结。** 旧实现每次调用都用匿名
  `LocationListener` 订阅，而 `LocationManager` 按监听器引用去重与移除，于是订阅数只增不减；叠加一条
  判据恒为真的"停摆自愈"逻辑，一天可累积近百个 GPS 订阅。现改为稳定的 `PendingIntent` 订阅 + 配对的
  `removeUpdates`，并把 `removeBackgroundUpdateRequests()` 真正实现了（原先是空函数体）。
- **修复"仅上报区域"隐私开关失效。** 旧实现里 `locationName` / `inZones` 是硬编码 `null`，勾选该选项的
  用户实际仍被上报精确经纬度。
- **修复地理编码回调空列表崩溃。** 旧实现在无 GMS 设备上大概率触发的路径上直接取 `it[0]`，且异常发生在
  `catch (IOException)` 罩不住的异步回调里。
- 修复主线程阻塞：旧实现有 9 处 `runBlocking` 跑在 `Dispatchers.Main` 上做磁盘读写、HTTPS 与地理编码
  IPC（上游同文件为 0 处，且做过三轮专门移除，fork 每次同步都回退）。
- 恢复被删除的上游能力：区域进入/退出事件、区域判定（含同半径按区域中心距离打破平局）、单次精确定位
  （含 wakelock 与精度重试）、定位历史、坐标去重与 5 秒防抖、未来时间戳拦截。
- 修复"发送失败仍推进去重状态"导致下一次有效定位被自我拦截（上游 #4545 的回归）。
- 恢复"最低精度"设置项在界面上可见可编辑（旧实现只剩一个传感器会建行，其余恒回退默认值）。
- 让 `ignoreAccuracy` 参数真正生效（原先声明后从未被读取，网络定位的精度豁免是空的）。
- 地理编码交回上游的 `GeocodeSensorManager`，并沿用其 `Geocoder.isPresent()` 与精度门限前置；
  不再复制一份属性命名不兼容（PascalCase 对 snake_case）的实现与之争抢同一传感器 ID。
- 修正中文文案放错资源桶：旧实现把 307 条中文写进 `app/src/main/res/values/strings.xml`（默认英文桶），
  导致非中文用户也看到中文；现移至 `values-zh-rCN/`，并清理 83 条上游已不存在的孤儿 key。
- 版本号不再由 `System.currentTimeMillis()` 生成（旧实现使产物不可复现，且与 configuration cache
  冲突），改回由 git tag 推导。

### 变更

- 包名改为 `dev.sctale.homeassistant.companion.minimal`，与官方客户端和原定制包互不覆盖，可并存安装。
- 应用名改为「Home Assistant 无GMS」，避免在非官方构建上冒充官方身份。
- 只发布 `minimal` flavor（不链接 Google Play 服务）。`full` flavor 保留但需自备 Firebase 配置才能构建。
- 定位改为纯 `android.location` 实现：优先省电的网络定位，15 分钟节奏，允许平台批量投递。
  新增"定位来源 / 更新间隔 / 最小距离 / 区域进出唤醒"四项可调设置。
- 恢复本地质量门禁脚本 `scripts/verify.sh`（ktlint + Android Lint + 单元测试 + 构建）。
  本项目的产物按惯例全部本地构建，不引入 hosted CI。

### 已知限制

- 无推送通知（`notify.mobile_app_*` 不可用）：官方推送依赖 FCM，而 FCM 依赖 Google Play 服务。
- 无"高精度模式"传感器：不提供无法在无 GMS 设备上工作的空壳开关。
- 区域判断由每次定位计算得出，而非系统围栏——`Geofence` / `LocationManager.addGeofences` 不在公开
  Android SDK 中，只随 Play 服务发布。`addProximityAlert` 作为可选唤醒源，是否被底层支持取决于机型。
- 简体中文文案覆盖官方字符串的 68%（1013 / 1479），其余回退英文。
- 不自动检查更新，需手动下载新 APK。

### 测试状态

- `ktlintCheck`（app / common / build-logic）通过。
- Android Lint（`lintMinimalRelease`）通过。
- 单元测试 `:app:testFullDebugUnitTest` 1298 项通过；2 项失败为 Windows 上 Robolectric 的既有环境问题
  （测试临时目录路径长 262 字符，超过 `MAX_PATH` 260；沿用上游原包名同样超长），与本项目改动无关。
- `:common:testDebugUnitTest` 中 `UrlUtilTest` 一条失败为环境依赖：该用例断言一个不存在的域名无法解析，
  而本机 DNS 是代理通配解析器（Clash/sing-box TUN 对任意域名返回 198.18.x.x）。
- 定位新实现已完成编译、静态检查与打包验证；真机上的定位行为（尤其是网络定位返回 WGS-84 还是 GCJ-02、
  以及 `addProximityAlert` 的支持情况）仍需在目标机型上实测。
