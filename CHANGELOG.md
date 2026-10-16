# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，
版本号遵循 [SemVer](https://semver.org/lang/zh-CN/)。

## [0.1.1] - 2026-10-16

### 界面本地化

- 设置界面补齐 **233 条简体中文文案**，覆盖之前在中文环境下仍然是英文的部分：
  - 传感器：33 个传感器名称与 39 条说明（Health Connect 健康类、血压/体温/体重/睡眠、SIM 信号强度与
    网络类型、IPv6 地址、NFC、热点、车速与剩余续航等），以及蓝牙 BLE 发射器与信标监视器两段长说明；
  - 开发者选项 → 位置跟踪：已发送 / 已跳过 / 发送失败，六种跳过原因（精度不足、更新过于频繁、
    与上次位置重复、时间戳位于未来、早于上次上报、位置过于陈旧），进入 / 离开 / 区域内停留；
  - 服务器与连接：连接检查逐项结果、远程连接安全级别、安全连接与位置共享引导；
  - 手势：分类、方向、双指/三指、全部动作名称；
  - Assist 与唤醒词、设备控制、桌面入口、待办事项小部件、家庭网络与 SSID 管理、页面缩放、
    前端缓存重置、开源许可等设置项。
- 简体中文文案覆盖率由 68% 提升到 **84%**（1246 / 1484）。仍未翻译的主要是引导页、小部件配置项和
  实体状态名，不在设置界面里。
- 新增 `tools/check-translations.js`：一次校验重复 key、上游已不存在的孤儿 key、以及译文里 `%1$s`、
  `%%` 这类占位符有没有被改写，并输出覆盖率。这三类问题都要等到编译或运行期才暴露。

### 工程化与文档

- 发布入口改为单个 PowerShell 脚本 `scripts/release-app.ps1`（根目录 `发布到GitHub.bat` 是它的双击入口）：
  打 tag → 构建 → 校验包名/版本/签名 → 推送 → 建 Release，一条命令做完，Release 正文直接取自本文件
  对应版本的小节。此前那条 `.bat` → Git Bash → `.sh` 的链路已删除，它在这台机器上不稳定
  （cmd 按 GBK 解析中文、`where bash` 会命中 WSL 的 bash.exe）。
- 新增 `tools/fix-script-encoding.js` 与 `tools/check-encoding.js`，管住这台机器上两条硬约束：
  `.ps1` 必须 UTF-8 带 BOM、`.bat` 必须纯 ASCII 无 BOM。
- 真机对照结论写入文档：上报坐标与 Traccar 记录的车辆轨迹重合，而 Traccar 存的是设备原始 GPS
  （WGS-84），故本项目上报同样为 WGS-84，**不引入 GCJ-02 转换**；同时记录 zone 取点必须与上报同系。
- `versionCode` 从 1 起改为按版本号推导（major\*10000+minor\*100+patch），本版为 101。

## [0.1.0] - 2026-10-15

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
- `:app:lintMinimalRelease` 通过（0 错误）。
- 本项目的两个 flavor 各自跑完单元测试：`:app:testMinimalDebugUnitTest` 1242 个用例、
  `:app:testFullDebugUnitTest` 1298 个用例，**0 失败**（各 1 个跳过）。
- `:common:testDebugUnitTest` 里 `UrlUtilTest` 一条失败为本机环境依赖：它假设一个不存在的域名
  解析不了，而本机代理的 TUN 模式对任意域名都返回 198.18.x.x。由
  `scripts/check-test-results.py` 按失败特征显式识别，非预期失败 0 个。
- 原本在 Windows 上恒失败的 2 个 Robolectric 导航测试已**真正修复**（不是跳过）：测试 JVM 的
  临时目录根改短，使每轮测试的数据库路径不再超过 MAX_PATH 260 字符上限。
- `minimal` flavor 此前一个单元测试都跑不起来（共享测试源集引用了只存在于 `full` 的类），
  现在可运行 1242 个用例，其中 14 个是本项目新增：11 条发送门限判定 + 3 条注册句柄不变量。
- 产物已在 Android 36 模拟器上安装并启动到引导页，无崩溃；APK 内 firebase/gms 条目为 0，
  "无 GMS"在产物层面成立。

真机对照已经定下一项：**上报点与 Traccar 记录的车辆轨迹重合**。Traccar 客户端上报的是设备原始 GPS、
服务端也不做坐标转换，所以它存的就是 WGS-84；两条线能叠在一起，说明本项目上报的同样是 WGS-84——
不引入 GCJ-02 转换是正确的，加了反而会稳定偏移 250–620 m（北京约 555 m、上海约 481 m、广州约 621 m）。

仍待验证的只剩：`addProximityAlert` 在目标机型上是否被系统 servicing；以及厂商 ROM 的 network 定位
在**别的**机型上会不会返回 GCJ-02（对照用的那批点，若在 开发者选项 → 位置跟踪 里触发来源显示为网络，
就连这条一起验过了）。另外要注意一致性：区域（zone）的取点也必须是 WGS-84，照高德/腾讯这类
国内图源点的 zone 会和上报坐标差几百米，表现为到家要走进院子才触发。
