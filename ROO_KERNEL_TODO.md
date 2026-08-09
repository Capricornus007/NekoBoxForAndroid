# ROO_KERNEL_TODO — 更换 sing-box 官方内核接驳调研与改动清单

> 调研日期：2026-07-31
> 调研对象：本仓库 `ThroneForAndroid`（现状） vs `C:\repos\husi`（同上游、已实现官方内核接驳的参考实现）
> 目标：将 Throne 从「starifly fork 内核 + libneko 旧架构」迁移到「sing-box 官方内核 + 官方 libbox 风格接驳」

---

## 0. 实施进展（2026-08-01，第一阶段：官方内核直换，保留旧架构）

**已实施**（用户方针：官方有的协议先接过去；neko 魔改才存在的 feat 先 skip + debug 日志兜底，待具体案例再修）：

- [x] `get_source.sh` 改为读取 `nb4a.properties` 的唯一 `SINGBOX_VERSION`（当前 v1.13.16），浅克隆**官方** `SagerNet/sing-box` 到 `../sing-box`；`get_source_env.sh`（fork commit 锁定）已删除；已有目录无条件校正官方 remote、强制刷新指定 tag，并校验 `HEAD == tag commit`。
- [x] `libcore/go.mod`：删除 `libneko`、starifly/reF1nd replace；`github.com/sagernet/sing-box v0.0.0` 仅作 module graph 占位，实际源码由 `replace => ../../sing-box` 指向 `SINGBOX_VERSION` 对应官方源码；共同直接依赖版本对齐官方 sing-box v1.13.16 go.mod（Go 仍 1.24.7，无需升级工具链）。`go.sum` 不入库，`libcore/build.sh` 在 bind 前 `go mod tidy` 现场重建。
- [x] 摘除 fork 私有依赖并自实现/替换：
  - `libneko/neko_log` → [`libcore/log.go`](libcore/log.go)（截断式文件日志）
  - `libneko/protect_server` → [`libcore/protect.go`](libcore/protect.go)（unix socket SCM_RIGHTS 收 fd）
  - `libneko/speedtest` → `box.go` 自实现 `urlTest`（经默认 outbound 的 HTTP GET 计时）
  - fork `boxapi.SbV2rayServer` → 官方 `experimental/v2rayapi.StatsService`（实现 `adapter.ConnectionTracker`，`GetStats(Reset_=true)` 取增量）
  - fork `boxapi.CreateProxyHttpClient` → `box.go` `newProxyHTTPClient`（`b.Outbound().Default()` 拨号）
  - fork `conntrack` → 官方无，`ResetAllConnections` 降级为 debug 日志
  - `nekoutils.Selector_OnProxySelected` → `BoxInstance.SelectOutbound` 内包装（Clash API 路径不覆盖，见降级项）
  - `nekoutils` geoip/geosite 钩子 → [`libcore/ruleset.go`](libcore/ruleset.go)：`box.New` 前预处理 local rule-set，官方命名（`geoip-cn`）优先指向已有 `.srs`，老格式（`geoip:cn`）从 db 转换生成 `.srs` 缓存
- [x] `platform_box.go` 迁移到官方 `adapter.PlatformInterface`（`OpenInterface`/`FindConnectionOwner` 等新签名；JNI 侧 `BoxPlatformInterface` 未变，Kotlin 零改动）。
- [x] `box_include.go` 摘除官方没有的 SSR/Snell 注册；`protocol/http`、`protocol/juicity` 适配官方 `tls.NewDialerFromOptions`/`tls.NewSTDClient` 新签名（多 logger 参数）；`interface_monitor.go` 适配 sing-tun v0.8.12（`MyInterfaces() []string`）。
- [x] CI（ci.yml/preview.yml/release.yml）libcore 缓存 key 纳入 `nb4a.properties`（SINGBOX_VERSION 变更触发内核重编，禁止复用旧 AAR）。
- [x] 静态校验脚本：[`roo_check_imports.py`](roo_check_imports.py)（import 路径 + fork 残留）、[`roo_check_symbols.py`](roo_check_symbols.py)（官方符号存在性），`uv run` 执行，均通过。
- [x] 首轮 CI 编译修复（2026-08-01）：① 官方 1.13.0 已移除 wireguard outbound（仅 endpoint），`box_include.go` 改为镜像官方 `include/registry.go` 的报错 stub（`option.StubOptions` + 明确错误信息）；② `adapter.DNSTransport` 接口 v1.13 新增 `Reset()`，`platformLocalDNSTransport` 补空实现（对齐官方 local transport，JNI 无持久连接）；③ `platform_box.go` 删除冗余 `context` import。
- [x] 首轮真机运行修复（2026-08-01，配置 schema 对齐官方 v1.13，[`ConfigBuilder.kt`](app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt) 等）：
  - `route.concurrent_dial` 1.13 已删除（`box.New` 报 unknown field 直接崩，内核完全起不来）→ 映射为官方替代 `default_network_strategy: "hybrid"`（WiFi/移动数据并发竞速拨号）；设置项保留，7 语言摘要改为明确说明（需 WiFi+移动数据同开、更耗电），防小白盲开
  - 正名（2026-08-01 二轮）：确认"并发拨号"（concurrent dial）是 singbox-p 社区版概念，此前误把它当作官核 hybrid 策略的开关名 → 设置项正名为「双网络加速」：`Key.CONCURRENT_DIAL`/`DataStore.concurrentDial` → `DUAL_NETWORK_ACCELERATION`/`dualNetworkAcceleration`（持久化键同步换新，旧 `CONCURRENT_DIAL` 常量保留，留作未来补回真正的并发拨号功能），字符串资源 `concurrent_dial*` → `dual_network_acceleration*`，7 语言标题同步更正（摘要本就准确，未动）
  - 入站 `sniff`/`sniff_override_destination`/`domain_strategy` 字段 1.13 硬错误（legacy inbound fields removed，tun/mixed 均中招）→ 迁移为路由规则动作 `{"action":"sniff"}` / `{"action":"resolve","strategy":...}`（置于规则最前）；`sniff_override_destination` 官方无替代（`OverrideDestination` 已成死代码），`trafficSniffing` 设置退化为 关/开 两档（`traffic_sniffing_values` 数组缩减，存量值 "2" 按开启处理）
  - DNS 地址 `hosts`（旧 fork=系统解析器）：官方 legacy 升级把裸 `hosts` 静默误判为 UDP 服务器域名（远程 DNS 全灭且不报错）→ `normalizeDnsAddress()` 归一化为 `local`（仅 dns-direct/订阅 resolver 等本机直解场景）
  - 远程 DNS 防泄露（2026-08-01 二轮修复）：首版把远程 `hosts` 也归一化为 `local`，实测 DNS 泄露（官方内核 `local` 在 Android 走平台接口经物理网卡直连系统 DNS，与 fork `hosts` "节点代访问"语义不对齐）→ 远程 DNS 遇 `hosts`/`local`/`localhost`/`fakeip` 占位符一律回退 `https://8.8.8.8/dns-query` + Toast 提示（`normalizeRemoteDnsAddress()`）；dns-remote 补 `detour = mainProxyTag`（当前节点，对齐 Throne 桌面端 `detour:"proxy"`/husi `detour=mainTag`；legacy 无 detour 时虽兜底走默认出站，显式指定才无歧义）。注：官方 legacy DNS 格式 1.14 将移除，且新结构化格式下无 detour 的 DNS server 默认**直连**（不再兜底默认出站），届时迁移必须保留显式 detour
  - tun 入站 `inet4_address`/`inet6_address`：字段虽仍在结构体（可解析），但构造函数硬报错（1.12 已移除，`initialize inbound[0]` 失败）→ 迁移为合并字段 `address`；`endpoint_independent_nat` 同步停止发射（官方已移除语义，当前无硬检查但迟早硬化）。Kotlin 侧 `startVpn` 不解析 tun JSON（地址/路由/MTU 用 DataStore），零影响
  - **URL 测速闪退修复**（2026-08-01 二轮兜底、三轮 Go 侧根治）：官方 [`box.go`](C:/repos/_inspect/sing-box/box.go) 在 `PlatformLogWriter != nil` 时无条件 `needCacheFile`/`needClashAPI`（libbox 假设单进程单实例），Throne 的 `NewSingBoxInstance` 恒传 `boxPlatformLogWriter` → 主进程批量测速的并发 `TestInstance` 全部共享 `no_backup/cache.db`（bbolt）。文件 freelist 被并发写坏后能正常打开、提交时才 `page already freed` panic，且 panic 在 bbolt `time.AfterFunc` 的 batch 提交 goroutine 中（[`cachefile.batch()`](C:/repos/_inspect/sing-box/experimental/cachefile/cache.go) 的 recover 只兜同步段）→ 主进程 SIGABRT 必闪退。二轮曾用 Kotlin-only 独立临时文件（`urltest_*.db`）隔离兜底；三轮根治：[`libcore/box.go`](libcore/box.go) 拆出 `newSingBoxInstance(config, transport, platformLog)`，新增 `NewTestSingBoxInstance` 不注册 PlatformLogWriter → CacheFile/ClashServer 均不创建，测速零 cache 文件（`ConfigBuilder` forTest 分支回退为不生成 experimental，`TestInstance` 改用新构造函数）；`SagerNet.onCreate` 清扫保留（老用户自愈）。零配置 ClashServer 无害（`ExternalController` 为空不监听）
  - **测速结果失真修复**（2026-08-01 三轮，真机日志实锤）：症状=批量测速飞快结束、大面积"超时"、连当前连接中的节点也超时。根因一：**接口监视器注册竞态**——`NativeInterface.startDefaultInterfaceMonitor` 原用 `runOnDefaultDispatcher` 异步注册，测试盒 `box.Start()` 后零等待首拨，`DefaultInterface()==nil` → 秒报 `no available network interface`（日志实锤；另有变体：`UpdateDefaultInterface` 里 `NewDefaultInterfaceFinder().ByIndex` 瞬时失败 `find updated interface: wlan0 ... no such network interface`，更新被永久丢弃）→ 修复：注册改 `runBlocking` 同步（actor 是 Unconfined，缓存命中时首回调在注册返回前完成）；`SagerNet` 主进程原有常驻监听预热、:bg 进程补齐同款；ByIndex 失败改后台重试 5 次。根因二：**protect 串行回环**——`protect.go` accept 循环同步处理每个 fd，JNI protect 排队时主进程 `sendFdToProtect` 100ms 超时失败 → fd 未 protect，测速流量回环进 tun（日志实锤 `tun-in` 收到测试包，当前节点形成"服务器自连"死结）→ `go handleProtectConn` 并发化 + recover 兜底。诊断日志：`ConfigurationFragment.urlTest` 失败分支 `Logs.w` 记录原始错误（真机验证通过后可移除）
  - **接口监视器补齐**（`no available network interface` 全部拨号失败，两半缺一不可）：① 官方 v1.13 只要注册了 PlatformInterface 就强制走平台 `CreateDefaultInterfaceMonitor`（`route/network.go`），旧 `interfaceMonitorStub` 空实现 `DefaultInterface()=nil` → [`interface_monitor.go`](libcore/interface_monitor.go) 重写为完整实现（参考 husi `tun.go`，含 `InterfaceUpdateListener` JNI 回调接口），`BoxPlatformInterface` 新增 `StartDefaultInterfaceMonitor`/`CloseDefaultInterfaceMonitor`；Kotlin 侧 [`NativeInterface.kt`](app/src/main/java/moe/matsuri/nb4a/NativeInterface.kt) 复用现成 `DefaultNetworkListener`（`registerBestMatchingNetworkCallback` 天然避开 VPN 接口，报告物理默认网络）。② **平台接口枚举同为硬性要求**：注册 PlatformInterface 后拨号器恒走并行接口选择（`common/dialer` strategy 默认非 nil），而 `NetworkManager.UpdateInterfaces()` 只在 `UsePlatformNetworkInterfaces()=true` 的平台分支缓存接口列表——为 false 时列表恒空，`selectInterfaces` 选不出接口报同款错误 → `UsePlatformNetworkInterfaces()` 改 true，`NetworkInterfaces()` 经 JNI `GetInterfaces()` 实现（husi `platform_box.go` 转换逻辑）；新增 `iterator.go`（StringIterator）、`network_interface.go`（NetworkInterface/迭代器/类型常量）、`link_flags_unix.go`（IFF_* → net.Flags）；Kotlin 侧 `getInterfaces()` 枚举 allNetworks+LinkProperties+NetworkCapabilities（husi 同款），`interfaceMonitor.UpdateDefaultInterface` 回调时先 `networkManager.UpdateInterfaces()` 刷新缓存（Initialize 晚于 CreateDefaultInterfaceMonitor 调用，wrapper 指针延迟访问）。T1-6 的 interfaceMonitor 部分就此完成

**已知降级项**（debug 日志兜底，待用户反馈具体案例）：SSR/Snell 节点（官方无）、`ResetAllConnections`、Clash API（yacd）切换 selector 不触发 `selector_OnProxySelected` 回调、**WireGuard 节点**（官方 1.13 仅支持 endpoint 形态，而 [`WireGuardFmt.kt`](app/src/main/java/io/nekohasekai/sagernet/fmt/wireguard/WireGuardFmt.kt) 仍生成 outbound 配置，`box.New` 会报 stub 错误；需做配置生成的 outbound→endpoint 迁移：`Outbound_WireGuardOptions` → `Endpoint_WireGuardOptions` 并写入 `endpoints` 数组，字段结构有差异需对照 `option/wireguard.go`）。

**遗留风险**（只能 CI/真机验证）：quic-go v0.59 http3 API（`http.go` TryH3Direct）、`dyhkwong/sing-juicity` 与新 sing/sing-quic 的编译兼容、`option.DefaultRule`→`DefaultHeadlessRule` 字段类型。另：「DNS hosts」功能（`dnsHosts` 非空时）规则上 `_hack_config_map["ip_accept_any"]` 在官方 DNS 规则 schema 中不存在，会触发 unknown field——server 侧 `type:"hosts"+predefined` 官方原生支持，规则侧需找官方等价（待有用户案例再修）。

**下一阶段**：按本文档阶段 3 做 Kotlin 侧适配（husi 风格 Service/Client 模型）或维持现状先功能回归（阶段 4）。

---

## 1. 结论摘要

| 维度 | ThroneForAndroid（现状） | husi（参考目标） |
|---|---|---|
| sing-box 来源 | `starifly/sing-box` fork（固定 commit `7567ef4`，含 `nekoutils` 私有包） | 官方 `github.com/sagernet/sing-box v1.14.0-beta.4`（无 replace） |
| libneko | `starifly/libneko` fork（固定 commit `1c47a3a`） | **无依赖** |
| Go 版本 | `go 1.24.7` | `go 1.26` |
| 构建工具 | `gomobile-matsuri`（MatsuriDayo/gomobile fork） | `anja`（`xchacha20-poly1305/anja`，sing-box 官方 libbox 构建链） |
| 平台接口 | 旧 `experimental/libbox/platform.Interface`（粗粒度 JNI） | 新 `adapter.PlatformInterface`（细粒度 JNI） |
| 实例模型 | `BoxInstance` 直连（NewSingBoxInstance/Start/Close） | `Service` + `Client`（unix socket 进程间通信） |
| 协议注册 | `nekoboxAndroid*Registry` 手动注册（box_include.go） | `distro/registry.go` 官方注册表 + `plugin/` 插件化 |
| Clash API | `experimental/clashapi`（旧） | `combinedapi`（自实现，注册为官方 ClashServer 构造器） |
| 流量统计 | `boxapi.SbV2rayServer`（v2ray stats） | `trafficcontrol.Manager` + `connectionObserver` |
| protect | libneko `protect_server` + `sendFdToProtect` | 自实现 `libcore/protect`（Service + Protect） |
| 日志 | libneko `neko_log` | sing-box 官方 `log` + `platformLogWrapper` |
| 配置类 | Kotlin 手写 `SingBoxOptions.java` | Go `cmd/boxoption` 反射生成 Kotlin 类 |
| 平台支持 | 仅 Android | Android + Desktop（Linux/Darwin/Windows） |
| Kotlin 侧 | 纯 Android（View + Fragment） | KMP + Compose Multiplatform |

**核心判断**：Throne 的 libcore 是「libneko 旧架构 + fork 内核」，husi 的 libcore 是「官方内核 + 官方 libbox 风格」。迁移不是改几个文件，而是**重写 libcore 接驳层 + 重写 JNI 接口 + 重写 Kotlin 侧服务调用**。UI 层（View/Fragment）可保留，但所有 `libcore.*` 调用点都要改。

---

## 2. 现状盘点（ThroneForAndroid）

### 2.1 libcore（Go 侧，共 26 个导出函数）

| 文件 | 职责 | 备注 |
|---|---|---|
| [`libcore/nb4a.go`](libcore/nb4a.go) | `InitCore` 入口、`NekoLogPrintln/Clear`、`ForceGc`、`sendFdToProtect` | 依赖 `libneko/neko_common`、`neko_log`；`//go:linkname` 篡改 `sing-box/constant.resourcePaths` |
| [`libcore/box.go`](libcore/box.go) | `BoxInstance`、`NewSingBoxInstance`、`VersionBox`、`ResetAllConnections`、`UrlTest` | 依赖 `libneko/protect_server`、`speedtest`；`boxapi.SbV2rayServer` 统计 |
| [`libcore/box_include.go`](libcore/box_include.go) | `nekoboxAndroid*Registry` 手动注册全部协议 | 含自定义 `libcore/protocol/http`（h2 ALPN 覆盖）、`libcore/protocol/juicity` |
| [`libcore/platform_box.go`](libcore/platform_box.go) | 实现旧 `platform.Interface` | `OpenTun(json,json)` 粗粒度；`interfaceMonitorStub` 空实现 |
| [`libcore/platform_java.go`](libcore/platform_java.go) | `NB4AInterface` + `BoxPlatformInterface` JNI 接口定义 | 粗粒度 |
| [`libcore/dns_box.go`](libcore/dns_box.go) | `LocalDNSTransport`（Raw/Lookup/Exchange） | 依赖 `rawQueryFunc` |
| [`libcore/geoip.go`](libcore/geoip.go) / [`geosite.go`](libcore/geosite.go) | 通过 `nekoutils.GetGeoIPHeadlessRules` 钩子注入 | **依赖 fork 私有包 `nekoutils`，官方内核无此包** |
| [`libcore/certs.go`](libcore/certs.go) / [`crypto.go`](libcore/crypto.go) / [`fix.go`](libcore/fix.go) / [`http.go`](libcore/http.go) / [`io.go`](libcore/io.go) / [`stun.go`](libcore/stun.go) | 工具函数 | 部分可保留 |
| [`libcore/device/`](libcore/device/device.go) | `NumUDPWorkers`、`GoDebug`、`DeferPanicToError` | 可保留 |
| [`libcore/ech/`](libcore/ech/ech.go) | ECH 客户端配置 | 可保留 |
| [`libcore/procfs/`](libcore/procfs/procfs.go) | procfs 查 UID | 可保留 |
| [`libcore/protocol/http/`](libcore/protocol/http/outbound.go) | h2 ALPN 覆盖的 HTTP outbound | 需迁移到 plugin 或保留 |
| [`libcore/protocol/juicity/`](libcore/protocol/juicity/outbound.go) | juicity outbound | 需迁移到 plugin 或保留 |

### 2.2 构建脚本

| 文件 | 职责 |
|---|---|
| [`libcore/build.sh`](libcore/build.sh) | `gomobile-matsuri bind`，tags：`with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api`，输出 `libcore.aar` |
| [`libcore/init.sh`](libcore/init.sh) | 安装 `gomobile-matsuri`（克隆 MatsuriDayo/gomobile master2） |
| [`buildScript/lib/core/get_source.sh`](buildScript/lib/core/get_source.sh) | 克隆 `starifly/sing-box` + `starifly/libneko` |
| [`buildScript/lib/core/get_source_env.sh`](buildScript/lib/core/get_source_env.sh) | 锁定 fork commit |
| [`buildScript/plugin/`](buildScript/plugin/) | hysteria2/juicity/mieru/naive/shadowquic 独立 so 构建 |

### 2.3 Kotlin/Java 侧

| 文件 | 职责 |
|---|---|
| [`app/src/main/java/moe/matsuri/nb4a/NativeInterface.kt`](app/src/main/java/moe/matsuri/nb4a/NativeInterface.kt) | 实现 `BoxPlatformInterface` + `NB4AInterface` |
| [`app/src/main/java/moe/matsuri/nb4a/SingBoxOptions.java`](app/src/main/java/moe/matsuri/nb4a/SingBoxOptions.java) | 手写配置类 |
| [`app/src/main/java/moe/matsuri/nb4a/SingBoxOptionsUtil.kt`](app/src/main/java/moe/matsuri/nb4a/SingBoxOptionsUtil.kt) | 配置构建 |
| [`app/src/main/java/io/nekohasekai/sagernet/bg/proto/BoxInstance.kt`](app/src/main/java/io/nekohasekai/sagernet/bg/proto/BoxInstance.kt) | 服务实例（`Libcore.newBoxInstance` 直连） |
| [`app/src/main/java/io/nekohasekai/sagernet/bg/proto/ProxyInstance.kt`](app/src/main/java/io/nekohasekai/sagernet/bg/proto/ProxyInstance.kt) | 代理实例 |
| [`app/src/main/java/io/nekohasekai/sagernet/bg/proto/TestInstance.kt`](app/src/main/java/io/nekohasekai/sagernet/bg/proto/TestInstance.kt) | 测试实例 |
| [`app/src/main/java/io/nekohasekai/sagernet/bg/proto/TrafficLooper.kt`](app/src/main/java/io/nekohasekai/sagernet/bg/proto/TrafficLooper.kt) | 流量轮询 |
| [`app/src/main/java/io/nekohasekai/sagernet/bg/proto/UrlTest.kt`](app/src/main/java/io/nekohasekai/sagernet/bg/proto/UrlTest.kt) | 延迟测试 |
| [`app/src/main/java/io/nekohasekai/sagernet/bg/VpnService.kt`](app/src/main/java/io/nekohasekai/sagernet/bg/VpnService.kt) | `startVpn(json,json)` 粗粒度 TUN 创建 |
| [`app/src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt`](app/src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt) | 服务基类 |

---

## 3. 参考实现盘点（husi）

### 3.1 libcore（Go 侧，146 个导出函数，官方 libbox 风格）

| 文件 | 职责 |
|---|---|
| [`libcore.go`](C:/repos/husi/libcore/libcore.go) | `InitCore(shouldOperateFiles, truncateLog, cachePath, internalAssets, externalAssets, maxLogLines, logLevel, useOfficialAssets, debugMode)` |
| [`service.go`](C:/repos/husi/libcore/service.go) | `Service`：`NewService(PlatformInterface)` / `NewInstance` / `StartInstance` / `StopInstance` / `HasInstance` / `SubscribeConnections` |
| [`service_android.go`](C:/repos/husi/libcore/service_android.go) | `Pause` / `Wake` / `ResetNetwork` / `NeedWIFIState` |
| [`client.go`](C:/repos/husi/libcore/client.go) | `Client`：unix socket（`api.sock`）连接 Service，`Hello` / `ImportDeepLinks` / `RunTask` |
| [`status.go`](C:/repos/husi/libcore/status.go) | `QueryConnections` / `SubscribeConnections` / `QueryMemory` / `QueryGoroutines` / `QueryClashModes` / `SetClashMode` / `UrlTest` / `SelectOutbound` / `QueryProxySets` / `ResetNetwork` / `ClearLog` / `SubscribeLogs` 等 |
| [`box.go`](C:/repos/husi/libcore/box.go) | `boxInstance`：`parseConfig`（boxoption）+ `combinedapi` + `protect` + `trafficcontrol` + `urltest.HistoryStorage` + `connectionObserver` |
| [`box_android.go`](C:/repos/husi/libcore/box_android.go) | 注册 `adapter.PlatformInterface` + 构建 `protect.Service` |
| [`platform_box.go`](C:/repos/husi/libcore/platform_box.go) | 实现官方 `adapter.PlatformInterface`（完整 interfaceMonitor、NetworkInterfaces、FindConnectionOwner） |
| [`platform_java_android.go`](C:/repos/husi/libcore/platform_java_android.go) | 细粒度 `PlatformInterface` JNI 接口 |
| [`dns.go`](C:/repos/husi/libcore/dns.go) | `LocalDNSTransport` + `registerPlatformLocalDNSTransport`（注册进官方 DNS TransportRegistry） |
| [`distro/registry.go`](C:/repos/husi/libcore/distro/registry.go) | 官方注册表：Inbound/Outbound/Endpoint/DNSTransport/Service/CertificateProvider |
| [`combinedapi/combinedapi.go`](C:/repos/husi/libcore/combinedapi/combinedapi.go) | 自实现 ClashServer（`experimental.RegisterClashServerConstructor`） |
| [`protect/`](C:/repos/husi/libcore/protect/) | 自实现 protect（unix socket 收 fd + `AutoDetectInterfaceControl`） |
| [`plugin/`](C:/repos/husi/libcore/plugin/) | http/juicity/vless/trusttunnel/anchor/raybridge/mieruproto 插件化协议 |
| [`cmd/boxoption/`](C:/repos/husi/libcore/cmd/boxoption/) | 反射生成 Kotlin 配置类（`SingBoxOption` 基类 + `@KxsSerializable`） |
| [`cmd/boxversion/`](C:/repos/husi/libcore/cmd/boxversion/) | 版本注入（`-X sing-box/constant.Version`） |
| [`cmd/licencecollect/`](C:/repos/husi/libcore/cmd/licencecollect/) | 许可证收集 |
| [`cmd/ruleset_generate/`](C:/repos/husi/libcore/cmd/ruleset_generate/) | 规则集生成 |
| [`vario/`](C:/repos/husi/libcore/vario/vario.go) | 二进制协议编解码（unix socket 通信） |
| [`ringqueue/`](C:/repos/husi/libcore/ringqueue/) | 环形队列 |
| [`oscall/`](C:/repos/husi/libcore/oscall/) | dup/flock 跨平台封装 |
| [`log.go`](C:/repos/husi/libcore/log.go) | `LogDebug/Info/Warning/Error/Clear/SetLogLevel`（sing-box 官方 log） |
| [`debug.go`](C:/repos/husi/libcore/debug.go) | `catchPanic` |
| [`format.go`](C:/repos/husi/libcore/format.go) | `FormatConfig` / `GenerateConfigSchema` / `GenerateOutboundSchema` / `GenerateDNSRuleSchema` / `CheckConfig` / `ParseDuration` |
| [`ping.go`](C:/repos/husi/libcore/ping.go) | `IcmpPing` / `TcpPing` |
| [`ruleset.go`](C:/repos/husi/libcore/ruleset.go) | `ScanRuleSet` |
| [`certs.go`](C:/repos/husi/libcore/certs.go) | `SetupRootCA` / `GetCert` / `ToV2RayPemHash` / `ToHysteriaHexSha256` / `ToSingPublicKeySha256` |
| [`assets.go`](C:/repos/husi/libcore/assets.go) | `ExtractAssets` |
| [`http.go`](C:/repos/husi/libcore/http.go) | `NewHttpClient` |
| [`io.go`](C:/repos/husi/libcore/io.go) | `TryUnpack` / `UntargzWithoutDir` / `UnzipWithoutDir` / `UnTarZstdWithoutDir` |
| [`url.go`](C:/repos/husi/libcore/url.go) | `NewURL` / `ParseURL` |
| [`version.go`](C:/repos/husi/libcore/version.go) | `VersionBox` / `BuildEnvironment` / `IsPreRelease` |
| [`age.go`](C:/repos/husi/libcore/age.go) / [`anytls.go`](C:/repos/husi/libcore/anytls.go) / [`mieru.go`](C:/repos/husi/libcore/mieru.go) / [`openconnect.go`](C:/repos/husi/libcore/openconnect.go) / [`trusttunnel.go`](C:/repos/husi/libcore/trusttunnel.go) | 各协议工具 |
| [`build_info_android.go`](C:/repos/husi/libcore/build_info_android.go) | `ReadAndroidVPNType` |
| [`stun.go`](C:/repos/husi/libcore/stun.go) | `FormatNATMapping` / `FormatNATFiltering` |

### 3.2 构建脚本

| 文件 | 职责 |
|---|---|
| [`libcore/build.sh`](C:/repos/husi/libcore/build.sh) | `anja bind`（489 行），支持 `--android` / `--desktop` / `--desktoptargets` / `--jniinclude` / `--darwinsdk` / `--no-naive`；tags：`with_gvisor,with_quic,with_wireguard,with_openconnect,with_openvpn,with_utls,with_naive_outbound`；`-javapkg` 指定包名；`go install tool` 装 anja/anjb |
| [`Makefile`](C:/repos/husi/Makefile) | `libcore_android` / `libcore_desktop` / `apk` / `desktop_package_*` / `launcher`（zig）/ `aboutlibraries` |
| [`buildScript/lib/core.sh`](C:/repos/husi/buildScript/lib/core.sh) | 参数透传包装 |
| [`buildScript/plugin/`](C:/repos/husi/buildScript/plugin/) | hysteria2/juicity/mieru/naive/shadowquic 独立构建 |

### 3.3 Kotlin 侧（KMP + Compose Multiplatform）

| 文件 | 职责 |
|---|---|
| [`AndroidPlatformInterface.kt`](C:/repos/husi/composeApp/src/androidMain/kotlin/fr/husi/bg/AndroidPlatformInterface.kt) | 实现细粒度 `PlatformInterface`：`autoDetectInterfaceControl(fd): Boolean`、`openTun(): Int`、`findConnectionOwner(): ConnectionOwner`、`readWIFIState(): WIFIState?`、`getInterfaces(): NetworkInterfaceIterator`、`startDefaultInterfaceMonitor` 等 |
| [`BoxServiceFactory.android.kt`](C:/repos/husi/composeApp/src/androidMain/kotlin/fr/husi/libcore/BoxServiceFactory.android.kt) | `Libcore.newService(AndroidPlatformInterface())` + `Libcore.setupRootCA` |
| [`LibcoreClientManager.kt`](C:/repos/husi/composeApp/src/commonMain/kotlin/fr/husi/utils/LibcoreClientManager.kt) | `Libcore.newClient(null)` + `subscribeLogs` / `subscribeConnectionEvent` / `subscribeClashMode` / `subscribeOpenConnectStatus`（断线重连） |
| [`BoxInstance.kt`](C:/repos/husi/composeApp/src/androidMain/kotlin/fr/husi/bg/proto/BoxInstance.kt) | `boxService.newInstance(config)` / `startInstance()` / `stopInstance()` / `hasInstance()` |

---

## 4. 迁移改动清单（TODO）

### 阶段 0：决策与准备

- [ ] **D0-1 确认内核版本**：锁定官方 sing-box 版本（参考 husi 用 `v1.14.0-beta.4`，建议选一个稳定 tag 或固定 commit），并确认其 `adapter.PlatformInterface` / `adapter.Endpoint` / `schema` 等新 API 形态。
- [ ] **D0-2 确认构建链**：评估引入 `anja`（`go install github.com/xchacha20-poly1305/anja/cmd/anja`）替代 `gomobile-matsuri` 的可行性；确认 NDK / Go 1.26 环境。
- [ ] **D0-3 确认保留范围**：Throne 的 UI 层（View/Fragment）是否保留？若保留，Kotlin 侧只需重写 `bg/proto/*` 与 `NativeInterface`，不动 UI。
- [ ] **D0-4 确认插件策略**：hysteria2/juicity/mieru/naive/shadowquic 独立 so 插件是否继续保留（husi 保留，且 Go 侧还有 `plugin/` 协议插件）。

### 阶段 1：Go 侧 libcore 重写（核心）

- [ ] **T1-1 重写 `go.mod`**：删除 `starifly/sing-box`、`starifly/libneko` replace；改为官方 `github.com/sagernet/sing-box` + `github.com/sagernet/sing` + `anja` 系列（`anja`、`anchor`、`TLS-scribe`、`libping`、`sing-trusttunnel`、`sing-juicity` 等）。参考 [`husi/libcore/go.mod`](C:/repos/husi/libcore/go.mod)。
- [ ] **T1-2 重写 `InitCore`**（[`nb4a.go`](libcore/nb4a.go)）：去掉 `neko_common`/`neko_log`/`//go:linkname`；改为 husi 风格签名（`shouldOperateFiles, truncateLog, cachePath, internalAssets, externalAssets, maxLogLines, logLevel, useOfficialAssets, debugMode`）。
- [ ] **T1-3 重写实例模型**：`BoxInstance` → `Service` + `boxInstance`（参考 [`husi/libcore/service.go`](C:/repos/husi/libcore/service.go) + [`box.go`](C:/repos/husi/libcore/box.go)）。新增 `NewService(PlatformInterface)` / `NewInstance` / `StartInstance` / `StopInstance` / `HasInstance` / `Pause` / `Wake` / `ResetNetwork` / `NeedWIFIState`。
- [ ] **T1-4 新增 `Client` + unix socket 通信**：参考 [`husi/libcore/client.go`](C:/repos/husi/libcore/client.go) + [`status.go`](C:/repos/husi/libcore/status.go) + [`vario/`](C:/repos/husi/libcore/vario/vario.go)。实现 `QueryConnections` / `SubscribeConnections` / `QueryMemory` / `QueryGoroutines` / `QueryClashModes` / `SetClashMode` / `UrlTest` / `SelectOutbound` / `QueryProxySets` / `ResetNetwork` / `ClearLog` / `SubscribeLogs` / `ImportDeepLinks` / `RunTask`。
- [ ] **T1-5 重写平台接口**：`BoxPlatformInterface`（粗粒度）→ `PlatformInterface`（细粒度，参考 [`husi/libcore/platform_java_android.go`](C:/repos/husi/libcore/platform_java_android.go)）：`LocalDNSTransport()`、`AutoDetectInterfaceControl(fd): Boolean`、`OpenTun(): Int`、`UseProcFS()`、`FindConnectionOwner(): ConnectionOwner`、`ReadWIFIState(): WIFIState?`、`StartDefaultInterfaceMonitor`、`CloseDefaultInterfaceMonitor`、`GetInterfaces(): NetworkInterfaceIterator`、`OnGroupSelectedChange`、`OnDeepLink`、`OnTask`。
- [ ] **T1-6 重写平台实现**：`platform_box.go` 从旧 `platform.Interface` 改为官方 `adapter.PlatformInterface`（参考 [`husi/libcore/platform_box.go`](C:/repos/husi/libcore/platform_box.go)）；补齐 `interfaceMonitor`（替换 `interfaceMonitorStub` 空实现）。
- [ ] **T1-7 重写协议注册**：`box_include.go` 的 `nekoboxAndroid*Registry` → `distro/registry.go` 风格（官方 Inbound/Outbound/Endpoint/DNSTransport/Service/CertificateProvider 注册表）。注意官方新架构用 `endpoint.Registry`（wireguard/openconnect/openvpn 走 endpoint）。
- [ ] **T1-8 迁移自定义协议**：`libcore/protocol/http`（h2 ALPN 覆盖）与 `libcore/protocol/juicity` 迁移到 `plugin/` 插件化（参考 [`husi/libcore/plugin/`](C:/repos/husi/libcore/plugin/)），或保留为 distro 注册。
- [ ] **T1-9 重写 Clash API**：`experimental/clashapi` → `combinedapi`（参考 [`husi/libcore/combinedapi/combinedapi.go`](C:/repos/husi/libcore/combinedapi/combinedapi.go)），注册 `experimental.RegisterClashServerConstructor`。
- [ ] **T1-10 重写流量统计**：`boxapi.SbV2rayServer` → `trafficcontrol.Manager` + `connectionObserver`（参考 [`husi/libcore/connection_observer.go`](C:/repos/husi/libcore/connection_observer.go)）。
- [ ] **T1-11 重写 protect**：libneko `protect_server` → 自实现 `libcore/protect`（参考 [`husi/libcore/protect/`](C:/repos/husi/libcore/protect/)）。
- [ ] **T1-12 重写日志**：`neko_log` → sing-box 官方 `log` + `platformLogWrapper`（参考 [`husi/libcore/log.go`](C:/repos/husi/libcore/log.go)）。
- [ ] **T1-13 重写 DNS 注册**：`dns_box.go` 的 `LocalDNSTransport` 改为通过 `registerPlatformLocalDNSTransport` 注册进官方 DNS TransportRegistry（参考 [`husi/libcore/dns.go`](C:/repos/husi/libcore/dns.go)）。
- [ ] **T1-14 处理 `nekoutils` 依赖**：`geoip.go`/`geosite.go` 依赖 fork 私有包 `nekoutils`，官方内核无此包。需改为官方规则集机制（`rule-set` / `geoip` 文件）或自实现钩子。
- [ ] **T1-15 新增配置工具**：`FormatConfig` / `GenerateConfigSchema` / `GenerateOutboundSchema` / `GenerateDNSRuleSchema` / `CheckConfig` / `ParseDuration`（参考 [`husi/libcore/format.go`](C:/repos/husi/libcore/format.go)）。
- [ ] **T1-16 新增 ping / 规则集 / 证书工具**：`IcmpPing` / `TcpPing` / `ScanRuleSet` / `SetupRootCA` / `GetCert` / `ToV2RayPemHash` / `ToHysteriaHexSha256` / `ToSingPublicKeySha256`（参考 husi 对应文件）。
- [ ] **T1-17 新增 `cmd/boxoption` 配置类生成器**：反射生成 Kotlin 配置类（参考 [`husi/libcore/cmd/boxoption/`](C:/repos/husi/libcore/cmd/boxoption/)），替代手写 `SingBoxOptions.java`。
- [ ] **T1-18 新增 `cmd/boxversion`**：版本注入 `-X github.com/sagernet/sing-box/constant.Version`。
- [ ] **T1-19 保留可复用工具**：`device/`、`ech/`、`procfs/`、`stun/`、`crypto.go`、`fix.go`、`http.go`、`io.go` 等无 fork 依赖的工具可直接保留。

### 阶段 2：构建脚本迁移

- [ ] **T2-1 重写 [`libcore/build.sh`](libcore/build.sh)**：`gomobile-matsuri bind` → `anja bind`（参考 [`husi/libcore/build.sh`](C:/repos/husi/libcore/build.sh)）。tags 对齐官方（`with_gvisor,with_quic,with_wireguard,with_openconnect,with_openvpn,with_utls,with_naive_outbound`），`-javapkg` 设为 Throne 包名（如 `io.nekohasekai.sagernet` 或 `moe.matsuri.nb4a`）。
- [ ] **T2-2 重写 [`libcore/init.sh`](libcore/init.sh)**：安装 `gomobile-matsuri` → `go install tool`（anja/anjb）。
- [ ] **T2-3 删除 fork 源码获取**：删除/改造 [`buildScript/lib/core/get_source.sh`](buildScript/lib/core/get_source.sh) 与 [`get_source_env.sh`](buildScript/lib/core/get_source_env.sh)（不再克隆 starifly fork）。
- [ ] **T2-4 升级 Go 版本**：`go 1.24.7` → `go 1.26`（对齐 husi），确认 CI/本地环境。
- [ ] **T2-5 评估插件构建**：`buildScript/plugin/`（hysteria2/juicity/mieru/naive/shadowquic）是否保留；若保留需确认与官方内核的兼容性。

### 阶段 3：Kotlin/Java 侧改造

- [ ] **T3-1 重写 [`NativeInterface.kt`](app/src/main/java/moe/matsuri/nb4a/NativeInterface.kt)**：`BoxPlatformInterface` + `NB4AInterface` → 细粒度 `PlatformInterface`（参考 [`husi/AndroidPlatformInterface.kt`](C:/repos/husi/composeApp/src/androidMain/kotlin/fr/husi/bg/AndroidPlatformInterface.kt)）：
  - `autoDetectInterfaceControl(fd): Boolean`（返回 VPNService.protect 结果）
  - `openTun(): Int`（无参，TUN 参数由 Go 侧 `tun.Options` 决定）
  - `findConnectionOwner(): ConnectionOwner`（返回 uid + packageNames）
  - `readWIFIState(): WIFIState?`
  - `getInterfaces(): NetworkInterfaceIterator`（枚举网络接口）
  - `startDefaultInterfaceMonitor` / `closeDefaultInterfaceMonitor`
  - `localDNSTransport()`、`onGroupSelectedChange`、`onDeepLink`、`onTask`
- [ ] **T3-2 重写 [`VpnService.kt`](app/src/main/java/io/nekohasekai/sagernet/bg/VpnService.kt)**：`startVpn(json,json)` → `startVpn()`（无参，返回 fd）。
- [ ] **T3-3 重写 [`BoxInstance.kt`](app/src/main/java/io/nekohasekai/sagernet/bg/proto/BoxInstance.kt)**：`Libcore.newBoxInstance` 直连 → `Libcore.newService(platformInterface)` + `newInstance(config)` / `startInstance()` / `stopInstance()` / `hasInstance()`。
- [ ] **T3-4 重写 [`ProxyInstance.kt`](app/src/main/java/io/nekohasekai/sagernet/bg/proto/ProxyInstance.kt) / [`TestInstance.kt`](app/src/main/java/io/nekohasekai/sagernet/bg/proto/TestInstance.kt)**：适配新 Service/Client 模型。
- [ ] **T3-5 重写 [`TrafficLooper.kt`](app/src/main/java/io/nekohasekai/sagernet/bg/proto/TrafficLooper.kt) / [`UrlTest.kt`](app/src/main/java/io/nekohasekai/sagernet/bg/proto/UrlTest.kt)**：改用 `Client`（`QueryConnections` / `SubscribeConnections` / `UrlTest` / `SelectOutbound`）。
- [ ] **T3-6 重写配置构建**：`SingBoxOptions.java` + `SingBoxOptionsUtil.kt` → `boxoption` 生成的 Kotlin 类（`SingBoxOption` 基类 + `@KxsSerializable`）。
- [ ] **T3-7 新增 `LibcoreClientManager`**：`Libcore.newClient(null)` + 订阅日志/连接/Clash 模式（参考 [`husi/LibcoreClientManager.kt`](C:/repos/husi/composeApp/src/commonMain/kotlin/fr/husi/utils/LibcoreClientManager.kt)）。
- [ ] **T3-8 新增 `BoxServiceFactory`**：`createBoxService(isBgProcess)` + `loadCA(provider)`（参考 [`husi/BoxServiceFactory.android.kt`](C:/repos/husi/composeApp/src/androidMain/kotlin/fr/husi/libcore/BoxServiceFactory.android.kt)）。
- [ ] **T3-9 适配 `BaseService.kt` / `SagerConnection.kt` / `ServiceNotification.kt`**：服务生命周期与通知逻辑适配新模型。
- [ ] **T3-10 适配 selector 切换**：`selector_OnProxySelected` → `onGroupSelectedChange(group, old, now)`。

### 阶段 4：验证与收尾

- [ ] **T4-1 编译验证**：`libcore` 构建通过（anja bind 产出 `libcore.aar`），`app` 编译通过。
- [ ] **T4-2 功能验证**：订阅导入、代理连接（SS/VMess/Trojan/VLESS/SSR/Snell/HTTP/SOCKS/SSH/ShadowTLS/AnyTLS/Hysteria/Hysteria2/Tuic/Juicity/WireGuard）、TUN 模式、DNS、分流规则、Clash 模式切换、连接列表、流量统计、日志。
- [ ] **T4-3 回归验证**：后台进程（`:bg`）、开机自启、快捷开关、Tile、通知、URL 测试、STUN、证书管理。
- [ ] **T4-4 清理**：删除 `starifly` fork 相关残留（`get_source.sh`、`get_source_env.sh`、`libneko` 引用、`nekoutils` 引用）。
- [ ] **T4-5 文档**：更新 README / 构建文档，记录新构建链（anja）与依赖。

---

## 5. 风险与注意事项

1. **`nekoutils` 是最大障碍**：Throne 的 `geoip.go`/`geosite.go` 依赖 fork 私有包 `nekoutils`（`GetGeoIPHeadlessRules` 钩子）。官方内核没有该包，需改用官方规则集机制（`rule-set` + `geoip.db`/`geosite.db` 文件）或自实现等价钩子。
2. **JNI 接口全面变更**：`BoxPlatformInterface`/`NB4AInterface` → `PlatformInterface` 是破坏性变更，Kotlin 侧所有 `libcore.*` 调用点（`bg/proto/*`、`NativeInterface`、`VpnService`）都要改。
3. **进程模型变化**：旧模型 `BoxInstance` 直连（同进程）；新模型 `Service`（后台进程）+ `Client`（unix socket 跨进程）。`TrafficLooper`/`UrlTest`/日志订阅都要走 `Client`。
4. **TUN 创建方式变化**：旧 `OpenTun(json,json)`（Kotlin 侧传参）→ 新 `OpenTun()`（无参，Go 侧 `tun.Options` 决定），`VpnService.startVpn` 签名要改。
5. **接口监控补齐**：旧 `interfaceMonitorStub` 是空实现；新架构需要完整 `interfaceMonitor`（`StartDefaultInterfaceMonitor`/`GetInterfaces`），Kotlin 侧要新增 `DefaultNetworkMonitor` 实现。
6. **配置类生成**：`boxoption` 反射生成依赖 sing-box `option` 包结构，生成结果与官方版本强绑定；升级 sing-box 后需重新生成。
7. **插件兼容性**：`buildScript/plugin/` 的独立 so（hysteria2/juicity/mieru/naive/shadowquic）与官方内核的兼容性需逐一验证；Go 侧 `plugin/` 协议插件（http/juicity/vless/trusttunnel/anchor）依赖官方 `schema` 新 API。
8. **Go 版本升级**：`go 1.24.7` → `go 1.26` 需确认 CI、本地工具链、`patches/`（cgo 补丁）是否仍需要。
9. **Desktop 支持（可选）**：husi 的 `anja` 构建链天然支持 Desktop；若 Throne 未来要出桌面版，可顺带获得，但当前任务可只做 Android。
10. **建议分步走**：先做「Go 侧 libcore 重写 + 构建链切换」（阶段 1-2），产出可编译的 aar；再做「Kotlin 侧适配」（阶段 3）；最后功能回归（阶段 4）。避免一次性大爆炸式替换。

---

## 6. 参考文件索引（husi）

| 用途 | 文件 |
|---|---|
| go.mod 依赖 | [`C:/repos/husi/libcore/go.mod`](C:/repos/husi/libcore/go.mod) |
| 构建脚本 | [`C:/repos/husi/libcore/build.sh`](C:/repos/husi/libcore/build.sh) |
| 初始化 | [`C:/repos/husi/libcore/libcore.go`](C:/repos/husi/libcore/libcore.go) |
| 服务模型 | [`C:/repos/husi/libcore/service.go`](C:/repos/husi/libcore/service.go) / [`service_android.go`](C:/repos/husi/libcore/service_android.go) |
| 客户端 | [`C:/repos/husi/libcore/client.go`](C:/repos/husi/libcore/client.go) / [`status.go`](C:/repos/husi/libcore/status.go) |
| 实例 | [`C:/repos/husi/libcore/box.go`](C:/repos/husi/libcore/box.go) / [`box_android.go`](C:/repos/husi/libcore/box_android.go) |
| 平台接口 | [`C:/repos/husi/libcore/platform_java_android.go`](C:/repos/husi/libcore/platform_java_android.go) / [`platform_box.go`](C:/repos/husi/libcore/platform_box.go) |
| 协议注册 | [`C:/repos/husi/libcore/distro/registry.go`](C:/repos/husi/libcore/distro/registry.go) |
| 插件 | [`C:/repos/husi/libcore/plugin/`](C:/repos/husi/libcore/plugin/) |
| Clash API | [`C:/repos/husi/libcore/combinedapi/combinedapi.go`](C:/repos/husi/libcore/combinedapi/combinedapi.go) |
| protect | [`C:/repos/husi/libcore/protect/`](C:/repos/husi/libcore/protect/) |
| 配置生成 | [`C:/repos/husi/libcore/cmd/boxoption/`](C:/repos/husi/libcore/cmd/boxoption/) |
| Kotlin 平台接口 | [`C:/repos/husi/composeApp/src/androidMain/kotlin/fr/husi/bg/AndroidPlatformInterface.kt`](C:/repos/husi/composeApp/src/androidMain/kotlin/fr/husi/bg/AndroidPlatformInterface.kt) |
| Kotlin 服务工厂 | [`C:/repos/husi/composeApp/src/androidMain/kotlin/fr/husi/libcore/BoxServiceFactory.android.kt`](C:/repos/husi/composeApp/src/androidMain/kotlin/fr/husi/libcore/BoxServiceFactory.android.kt) |
| Kotlin 客户端管理 | [`C:/repos/husi/composeApp/src/commonMain/kotlin/fr/husi/utils/LibcoreClientManager.kt`](C:/repos/husi/composeApp/src/commonMain/kotlin/fr/husi/utils/LibcoreClientManager.kt) |
| Kotlin 实例 | [`C:/repos/husi/composeApp/src/androidMain/kotlin/fr/husi/bg/proto/BoxInstance.kt`](C:/repos/husi/composeApp/src/androidMain/kotlin/fr/husi/bg/proto/BoxInstance.kt) |
