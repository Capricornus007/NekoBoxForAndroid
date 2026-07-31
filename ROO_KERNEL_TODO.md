# ROO_KERNEL_TODO — 更换 sing-box 官方内核接驳调研与改动清单

> 调研日期：2026-07-31
> 调研对象：本仓库 `ThroneForAndroid`（现状） vs `C:\repos\husi`（同上游、已实现官方内核接驳的参考实现）
> 目标：将 Throne 从「starifly fork 内核 + libneko 旧架构」迁移到「sing-box 官方内核 + 官方 libbox 风格接驳」

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