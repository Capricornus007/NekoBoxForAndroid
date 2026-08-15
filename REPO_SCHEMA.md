# ThroneForAndroid (nb4a) 项目架构与开发指南

本项目是一个基于 SagerNet 框架的 Android 代理客户端，底层核心是 `libcore`（基于 Go 语言的 `sing-box` 核心）。项目采用 `gomobile` 技术，将 Go 语言编写的底层核心编译为 Android AAR 库，供 Android 端的 Java/Kotlin 代码调用。

---

## 1. 项目目录结构 (Repository Schema)

### 1.1 顶层目录
- `app/`: Android 应用程序模块，包含所有的 UI、后台服务、数据库和配置解析逻辑。
- `libcore/`: Go 语言编写的底层核心，基于 `sing-box`，负责底层的网络代理、路由和协议实现。
- `buildScript/`: 包含用于初始化环境、编译底层 Go 核心、打包 AAR 等构建辅助 shell 脚本。
- `buildSrc/`: Gradle 构建配置，包含一些 Kotlin 编写的构建辅助工具。
- `gradle/`: Gradle Wrapper 目录。
- `.github/`: GitHub 配置目录，包含 GitHub Actions 工作流定义。

---

### 1.2 Android 应用程序模块 (`app/`)
Android 端的代码主要分为两个核心包：

#### 1.2.1 `io.nekohasekai.sagernet` (SagerNet 核心框架)
这是项目的核心框架，继承自 SagerNet：
- [`io/nekohasekai/sagernet/bg/`](app/src/main/java/io/nekohasekai/sagernet/bg): 后台服务模块。
  - `VpnService.kt`: Android VPN 服务的核心实现，负责拦截流量并传递给底层核心。
  - `ProxyService.kt`: 代理服务。
  - `BaseService.kt`: 基础服务。
  - `SubscriptionUpdater.kt`: 订阅自动更新。通过 WorkManager（`RemoteWorkManager`）注册周期任务；`UpdateTask` 是 `RemoteCoroutineWorker`，借助清单中声明在 `:bg` 进程的 `androidx.work.multiprocess.RemoteWorkerService` 在 `:bg` 进程执行（该进程的 `DataStore.serviceState` 由 `BaseService` 实时维护，保证"仅连接时更新"判断正确）。
- [`io/nekohasekai/sagernet/database/`](app/src/main/java/io/nekohasekai/sagernet/database): 数据库与偏好设置模块。
  - `SagerDatabase.kt`: Room 数据库定义，存储代理配置、分组、规则等。
  - `ProfileManager.kt` / `GroupManager.kt`: 配置和分组管理器。
- [`io/nekohasekai/sagernet/fmt/`](app/src/main/java/io/nekohasekai/sagernet/fmt): 各种代理协议的配置格式化与解析。`ConfigBuilder.kt` 生成 sing-box 配置，已对齐官方 v1.13 schema：入站 sniff/解析目标地址迁移为路由规则动作（`sniff`/`resolve`，置于规则最前）；tun 地址用合并字段 `address`（`inet4_address`/`endpoint_independent_nat` 等 legacy 字段已弃用）；「双网络加速」设置（`dualNetworkAcceleration`，原误名 `concurrent_dial`——"并发拨号"实为 singbox-p 社区版概念，与官核无关）映射为 `default_network_strategy: "hybrid"`；DNS 地址归一化：dns-direct/订阅 resolver 的 `hosts` 视为 `local`（本机直解，语义正确），远程 DNS 的 `hosts`/`local`/`localhost`/`fakeip` 占位符一律回退为 `https://8.8.8.8/dns-query` 并 Toast 提示用户修改设置（本机直解用作远程即 DNS 泄露），且 dns-remote 显式 `detour`=当前节点（对齐 Throne 桌面端 `detour:"proxy"`）。
  - **自定义服务器 DNS 暂停开放（未来潜在 TODO）**：Throne 桌面端目前尚未正式支持该功能，Android 端已移除订阅设置中的 `server_dns` 界面入口，并同步停用 `GroupSettingsActivity` 中对应的 UI 缓存读写、Preference 查找/监听与输入校验；原代码以注释保留，数据库字段与既有配置生成逻辑继续保留以兼容历史数据，但不继续修复。已确认的待办包括：①订阅「强制解析」仍由 Android 底层网络/系统 DNS 执行，不读取该订阅的自定义服务器 DNS；②批量 URL Test 的隔离配置会跳过订阅自定义服务器 DNS并固定使用 `dns-direct`，因此依赖该 DNS 才能解析服务器域名的节点可能测速失败，而连接后复用正式 box 的单次测试可成功。待 Throne 上游正式支持并明确配置语义后，再统一恢复入口、UI 绑定并对齐强制解析、批量测速和正式连接三条路径。
  - AnyTLS outbound 的 `tls.insecure` 同时合并节点「允许不安全连接」与全局「总是跳过 TLS 证书验证」，与 V2Ray/TUIC/Hysteria/Juicity 等 TLS 协议保持一致。
  - debug 级别输出脱敏的 `Outbound chain ... appToEgress=` 链拓扑摘要，仅含实体 ID、协议类型与服务器端点，不含 UUID、密码、Reality 公钥或其他认证材料，用于区分链式代理 EOF 发生在哪一跳。
  - 支持 Shadowsocks, VMess, Trojan, Hysteria, Juicity, Naive, WireGuard 等协议的配置解析与转换。
  - `forTest`（URL 测速）配置不生成 `experimental` 块：官方内核在 `PlatformLogWriter != nil` 时无条件创建 CacheFile（bbolt）与 ClashServer，无显式 `path` 时所有实例共用工作目录（`no_backup`）下的 `cache.db`。主进程批量测速并发创建大量 `TestInstance` 曾共享该文件，bbolt freelist 被写坏后每次 `box.Start` 的清理 batch 在 bbolt 定时器 goroutine 中 `page already freed` panic（异步 goroutine 中无法 recover）→ 主进程 SIGABRT 闪退。最终修复在 Go 侧：测速实例走 [`NewTestSingBoxInstance`](libcore/box.go)（不注册 PlatformLogWriter → 官方 `needCacheFile`/`needClashAPI` 均不触发），测速完全不产生 cache.db；[`SagerNet.onCreate`](app/src/main/java/io/nekohasekai/sagernet/SagerNet.kt)（main/bg 进程）启动时仍清扫存量共享 `cache.db` 与历史残留 `urltest_*.db` 实现老用户自愈。另：测速拨号依赖平台接口监视器，`NativeInterface.startDefaultInterfaceMonitor` 必须同步注册（异步曾致首拨竞态秒报 `no available network interface`），`SagerNet` 两个进程常驻 `DefaultNetworkListener` 预热缓存。**测速配置须与正式连接逐项对齐**（对齐 husi）：`ipv6Mode` 与 outbound `domain_strategy` 均沿用用户设置——曾分别强制 `IPv6Mode.ENABLE` 与空串，测速拨号的协议族/解析结果与真实路径不同，造成"测速 err 实际能用、测速成功实际不能用"的双向失真；DNS 侧 forTest 保持 dns-direct 收尾（与正式配置中服务器域名经 dns 规则归 dns-direct 的解析路径一致），无 fakeip/sniff/路由规则/experimental 属测速本就不需要之合理差异。
  - `SingBoxOutboundParser.kt`: 将 sing-box 配置中的单个 outbound JSON 还原为原生协议 Bean（支持 shadowsocks/vmess/vless/trojan/hysteria/hysteria2/tuic/socks/http/wireguard/anytls，含 TLS/transport/multiplex 子块解析，TLS 的 `ech` 子块含 `config` 与 `query_server_name`）。用于订阅返回完整 sing-box 配置（含 `outbounds`）的场景：`RawUpdater.parseJSON` 的 `outbounds` 分支对每个 outbound 优先调用 `parseSingBoxOutbound()` 还原原生节点，不支持的类型或解析失败时回退为 `ConfigBean`（自定义 JSON）；`dns`/`block`/`direct`/`selector`/`urltest` 类型的 outbound 始终跳过。
  - ECH 分享链接参数：`parseDuckSoft`（trojan/vless/vmess ducksoft 格式）解析 `ech=` 查询参数——社区格式 `ech=<ECH查询域名>+<DoH地址>`（Xray echConfigList 风格）或 `ech=true/1`，存在且非 `none/0/false` 时置 `enableECH=true`，`+` 前域名部分写入 `StandardV2RayBean.echQueryServerName`（DoH 部分丢弃：sing-box 不支持为 ECH 查询单独指定 DoH，走自身 DNS 路由）；`buildSingBoxOutboundTLS` 将其输出为 sing-box 1.13+ 的 `ech.query_server_name`（为空时内核用 `server_name` 查询 HTTPS 记录）；`toUriVMessVLESSTrojan` 导出时 `echQueryServerName` 非空写 `ech=<域名>`、否则写 `ech=true`。Bean 序列化版本 11（v11 起附带 `echQueryServerName`）。
- [`io/nekohasekai/sagernet/ui/`](app/src/main/java/io/nekohasekai/sagernet/ui): 各种 Activity 和 Fragment 界面。
  - `MainActivity.kt`: 应用主界面。预览版启动时弹出提示对话框（标题为 `BuildConfig.PRE_VERSION_NAME`），提供「不再显示」按钮（`preview_hint_dont_show_again`）：点击后把当前版本号写入 `DataStore.previewHintDismissedVersion`（`Key.PREVIEW_HINT_DISMISSED_VERSION`），之后仅对已忽略的版本不再弹窗，发布新预览版时会重新提示。软件图标长按菜单由 `res/xml/shortcuts.xml` 的四个静态快捷方式提供（切换/启用/禁用/扫描二维码），其显式 Intent 的 `targetPackage` 必须与 `nb4a.properties` 的 `PACKAGE_NAME` 同步；当前 Preview/Release 均为 `com.nb4a.throne`。快捷方式元数据仅声明在 Launcher `MainActivity` 下，三个服务控制入口及 `ScannerActivity` 均导出供系统 Launcher 启动。
  - `ScannerActivity.kt` / [`layout_scanner.xml`](app/src/main/res/layout/layout_scanner.xml)：全屏二维码扫描界面不再叠加会被相机预览遮挡的工具栏；手电筒与从图片导入入口分别作为左下角、右下角悬浮按钮，图片入口复用既有多图二维码解析与配置导入流程。
  - `SettingsFragment.kt`: 设置界面。「入站设置」：TUN 模式（Android 10+）下只要混合入站存在，`VpnService` 即无条件 `setHttpProxy` 指向本地代理端口（原 nb4a「追加 HTTP 代理至 VPN」开关 `appendHttpProxy` 已移除——其作用与混合入站存在性重叠，回环访问控制改由认证字段表达）。「配置身份验证」项（`mixedAuthConfig`，仅 UI 查找键）点击弹出用户名/密码对话框：两项均留空则混合入站免认证（本机回环免密直连），用户名非空则 `ConfigBuilder` 为 mixed 入站配置该账密（`DataStore.mixedInboundNeedsAuth`，仅 TUN 模式）；原 nb4a 时代自动生成随机 `mixedSecret` 的隐藏鉴权机制已移除，app 内部组件（订阅更新/资产下载/版本检查经 `DataStore.mixedInboundUser/Pass`）跟随运行时 `mixedInboundAuthed` 自动带票。「禁用混合入站」开关（`disableMixedInbound`，见 `SettingsPreferenceFragment.kt`）：仅在 TUN 模式下真正生效（`DataStore.mixedInboundDisabled`），开启后 `ConfigBuilder` 不再生成 mixed 入站及其专属的 `inbound = [mixed-in]` 路由规则、不再监听本地代理端口，`VpnService` 同时跳过 `setHttpProxy`；此时「代理端口」「配置身份验证」「HTTP 代理绕过列表」设置项一并变灰，端口摘要显示「已禁用」。在系统代理模式下开启该开关会 Toast 提示"禁用只在TUN模式有效，当前监听端口：xxx"且不影响混合入站。内部 HTTP（订阅/资产/版本检查）统一 [`tryProxyOutbound()`](app/src/main/java/io/nekohasekai/sagernet/ktx/Nets.kt)→ libcore `HTTPClient.TryBoxOutbound` 经 main box 默认 outbound 拨号，不再 `trySocks5(mixedPort)`（纯 TUN 下 mixed 不存在会 fail connect socks5）。
  - `BackupFragment.kt` / [`layout_backup.xml`](app/src/main/res/layout/layout_backup.xml)：本地备份区在分享/导出/导入之外增加「从Throne电脑版导入」（`action_import_throne_desktop`），选择 `.thrbackup` 后由 [`ThroneDesktopBackupImporter`](app/src/main/java/io/nekohasekai/sagernet/database/ThroneDesktopBackupImporter.kt) 解析（QDataStream `THRN` + 内嵌 SQLite，见 [`THR_FILE_RESEARCH.md`](THR_FILE_RESEARCH.md)）。忽略 `icons/*`；配置档：`profiles.outbound_json` 经 `parseSingBoxOutbound` 还原原生 Bean（失败则 `ConfigBean` outbound），分组 URL→订阅、`profiles_json` 保序、保留桌面 id/前置与落地代理；路由：导入当前 `current_route_id`（否则全部）的 `route_rules`，跳过 `hijack-dns`/`sniff`/`resolve`，outbound `-1/-2/-3`→T4A `0/-1/-2`，`rule_set` 的 geoip/geosite 写入 ip/domains、远程 URL 写入 `ruleset`；设置：按键尽力映射 DNS/混合入站/MTU/IPv6/TUN 实现/测速/分片/嗅探/绕过局域网/Clash API/日志级别等，不整表清空 Android 专有项。导入后 `triggerFullRestart`。
  - `AboutFragment.kt`: 关于界面。版本更新检查：「检查正式版更新」为灰色禁用项（Throne 尚未发布正式版，点击仅弹出 toast `release_not_available`）；「检查预览版更新」请求 GitHub `releases/latest` API，将远端 release 名（即 git tag，形如 `v1.4.2-m20-10`）与本地 `BuildConfig.VERSION_NAME` 按 `-` 分段、逐段提取数字组从左到右比较（左侧段优先级高于右侧，如 `v1.2.3-m21-1` > `v1.2.3-m20-100`），见 `compareVersionNames()`。
- [`io/nekohasekai/sagernet/widget/`](app/src/main/java/io/nekohasekai/sagernet/widget): 自定义 UI 控件。

#### 1.2.2 `moe.matsuri.nb4a` (NekoBox 专属扩展)
这是 NekoBox 专属的扩展和定制代码，Nekobox是Throne的前生，故兼容之：
- [`moe/matsuri/nb4a/NativeInterface.kt`](app/src/main/java/moe/matsuri/nb4a/NativeInterface.kt): 与底层 Go 核心 (`libcore`) 交互的 JNI 接口，实现了 `libcore.BoxPlatformInterface` 和 `libcore.NB4AInterface`。
- [`moe/matsuri/nb4a/SingBoxOptions.java`](app/src/main/java/moe/matsuri/nb4a/SingBoxOptions.java) / [`SingBoxOptionsUtil.kt`](app/src/main/java/moe/matsuri/nb4a/SingBoxOptionsUtil.kt): Sing-box 相关的配置选项和工具类。`makeSingBoxRule()` / `generateRuleSet()` 中 geo 引用同时接受老 nb4a 冒号格式（`geoip:cn`/`geosite:cn`）与 throne/官方连字符格式（`geoip-cn`/`geosite-cn`），均生成本地 rule-set 交由 libcore `ruleset.go` 解析；`geoip:private` 与 `geoip-private` 都映射为 `ip_is_private`。
- [`moe/matsuri/nb4a/proxy/`](app/src/main/java/moe/matsuri/nb4a/proxy): 额外的代理协议（如 AnyTLS, ShadowTLS, NekoBean 等）和配置绑定。
- [`moe/matsuri/nb4a/ui/`](app/src/main/java/moe/matsuri/nb4a/ui) / [`utils/`](app/src/main/java/moe/matsuri/nb4a/utils): 专属的 UI 控件和工具类。

#### 1.2.3 资源文件 (`app/src/main/res/`)
- `layout/`: 界面布局 XML 文件。
- `menu/`: 菜单 XML 文件。
- `xml/`: 偏好设置 XML 文件（如 `global_preferences.xml`, `shadowsocks_preferences.xml` 等）。
- `values-zh-rCN/`: 简体中文本地化字符串。

---

### 1.3 底层核心模块 (`libcore/`)
Go 语言编写的底层核心，负责高性能的网络处理。**内核为官方 `SagerNet/sing-box`**（版本由 [`nb4a.properties`](nb4a.properties) 的 `SINGBOX_VERSION` 指定，构建时克隆官方源码，无任何 fork/魔改；原 starifly fork、`libneko`、`nekoutils`、`boxapi`、`conntrack` 依赖已全部摘除）：
- [`libcore/nb4a.go`](libcore/nb4a.go): Go 核心的入口，导出 `InitCore` 等函数，供 Android 端通过 JNI 调用。
- [`libcore/box.go`](libcore/box.go) / [`box_include.go`](libcore/box_include.go): 与 `sing-box` 核心的集成与初始化。流量统计使用官方 `experimental/v2rayapi.StatsService`；`UrlTest` 为自实现（替代 libneko/speedtest，对齐 husi `libcore/ping.go`：显式经 box 默认 outbound 拨号建立一条连接，HTTP client 恒复用该连接发两次 HEAD——首发预热不计时、第二次复用连接计纯 RTT；延迟跨协议可比、贴近实际连接复用体感，第二次请求兼作连接持续性验证，"首包能通随即断开"的节点不再假成功；超时由 ctx 在拨号+两次请求间全程共用），其链路（`UrlTest`/`urlTest`）的 debug 级日志复用现有级别过滤管线——经 `boxPlatformLogWriter.WriteMessage(sblog.LevelDebug, ...)` 输出，受 `platformLogLevel` 门控（仅 trace/debug 级别放行），而非直接打标准库 log；`ResetAllConnections` 调用官方 `NetworkManager.ResetNetwork()`（CloseAll + outbound.InterfaceUpdated），**仅供手动** `Action.RESET_UPSTREAM_CONNECTIONS` / `wakeResetConnections` 等显式入口——正常切网重置由接口监视器回调官方 `notifyInterfaceUpdate→ResetNetwork` 完成，app 侧不再叠一层（避免 hy2 被连续拆两次）。SSR/Snell 协议官方内核不支持，已摘除（配置含此类节点时 `box.New` 报错，待有具体案例再评估）。WireGuard 官方 1.13 起仅支持 endpoint（outbound 已移除），`box_include.go` 镜像官方注册了报错 stub；Kotlin 侧 `WireGuardFmt` 的 outbound→endpoint 配置迁移待做（见 ROO_KERNEL_TODO 已知降级项）。
- [`libcore/platform_box.go`](libcore/platform_box.go): 实现官方 `adapter.PlatformInterface`（旧 `experimental/libbox/platform` 包已不存在）：TUN 创建（`OpenInterface`）、fd protect、按应用分包（`FindConnectionOwner`）、默认接口监视器、平台网络接口枚举（`NetworkInterfaces`，官方拨号路径硬性要求）等。**`boxPlatformInterfaceWrapper` 必须按 box 实例创建**（`newSingBoxInstance` 每 box 注册新实例，对齐官方 libbox 结构）：其 `networkManager`/`myTunName` 是每 box 状态，若做成进程级单例，并发测速时各 box 的 `Initialize` 会互相覆盖 `wrapper.networkManager`，使 `interfaceMonitor.UpdateDefaultInterface` 里 `UpdateInterfaces()` 刷错 NetworkManager，落选 box 自己的接口缓存永远为空 → 其所有拨号秒报 `no available network interface`（URL Test 概率性全超时的根因）。主进程 fd protect 经 `protect_path` unix socket 转发至 :bg 进程，`AutoDetectInterfaceControl` 对转发失败实行 **fail-fast**：仅 socket 不存在/无监听（`ENOENT`/`ECONNREFUSED`，即 VPN 未运行）时放行直连，其余失败（如 100ms ack 超时）返回错误使拨号失败——此前 `_ =` 吞错会让未 protect 的测速流量回环进 tun、经当前节点"套娃"出站，测速结果与节点直连可用性彻底脱节。[`libcore/interface_monitor.go`](libcore/interface_monitor.go): 对齐官方 `experimental/libbox/monitor.go` 的 `platformDefaultInterfaceMonitor`：`UpdateInterfaces` → index==-1 立即 `callback(nil)`；否则 resolve 后仅 name/index 变化时 `callback(iface)`（→ 官方 `notifyInterfaceUpdate→ResetNetwork`）；resolve 失败只报错并**保留旧 defaultInterface**（官方同款，无重试/无时间防抖）。**唯一 Android 补丁**：官方只调 `InterfaceFinder().ByIndex`，部分 OEM（OnePlus/Android16+VPN）finder 可能未含该 index——`resolveInterface` 回退 nm-cache → `net.InterfaceByIndex` → Kotlin name+index，避免 `ResetNetwork` 永不触发导致 hy2 僵尸。**设置项门控**：`networkChangeResetConnections`（默认 true）经 `Libcore.setNetworkChangeResetConnections` 同步到 Go——为 false 时 name/index 变化只更新 `DefaultInterface()`、不 callback/ResetNetwork（从 nil 恢复仍通知以 `NetworkWake`）；`wakeResetConnections` 仅门控 `BaseService` 在 `ACTION_DEVICE_IDLE_MODE_CHANGED` 唤醒时的 `resetAllConnections`。Kotlin 经 JNI 上报物理默认接口（复用 `DefaultNetworkListener`，其回调经专用 `HandlerThread` 派发、不占进程主线程（Go unchanged 路径本就只刷缓存后 skip ResetNetwork，零语义损失）。[`BaseService.preInit`](app/src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt) 只跟踪 `underlyingNetwork`/网卡名，**不再**叠调 `resetAllConnections`。[`libcore/network_interface.go`](libcore/network_interface.go) / [`iterator.go`](libcore/iterator.go) / [`link_flags_unix.go`](libcore/link_flags_unix.go): `GetInterfaces` JNI 桥接；Kotlin `NativeInterface.getInterfaces()` 枚举接口。
- [`libcore/log.go`](libcore/log.go): `neko_log` 的自实现替代（带大小截断的文件日志 writer，接管标准库 log）。日志文件有**两条写入通道，级别过滤分别实现**：①sing-box 内核 → `PlatformWriter`：官方内核对 `PlatformWriter` 通道**不做级别过滤**（`log/observable.go` 无条件转发含 trace 在内的所有级别），过滤在本侧实现——[`platform_box.go`](libcore/platform_box.go) 的 `WriteMessage` 按 `platformLogLevel` 丢弃超限消息，级别由 [`box.go`](libcore/box.go) `newSingBoxInstance` 从配置 `log.level` 解析记录（空级别对齐官方默认 trace 全放行）；②Kotlin `Logs.x()` → JNI `nekoLogPrintln` → std log：官方同样不过滤，门控在源头 [`Logs.kt`](app/src/main/java/io/nekohasekai/sagernet/ktx/Logs.kt)（`d` 需 logLevel≥3、`i` ≥2、`w` ≥1、`e` 恒放行，读取失败放行）。插件进程（hysteria 等）的 stdout/stderr 经 `GuardedProcessPool` 直接转发入文件，其级别由 `BoxInstance.kt` 启动参数 `--log-level` 控制（与 ConfigBuilder 同一套 0~4 映射）。
- [`libcore/protect.go`](libcore/protect.go): `libneko/protect_server` 的自实现替代（unix socket 接收主进程经 SCM_RIGHTS 发来的 fd 并回调 `VpnService.protect`）。
- [`libcore/ruleset.go`](libcore/ruleset.go): geo 规则集预处理（替代 fork 的 `nekoutils` geoip/geosite 钩子）。官方 local rule-set 只认真实文件路径，本模块在 `box.New` 前改写配置：官方格式（`geoip-cn`/`geosite-cn`）优先指向 `<externalAssets>/` 下已存在的官方 `.srs`；老 nb4a 格式（`geoip:cn`/`geosite:cn`）或官方 `.srs` 缺失时，从本地 `geoip.db`/`geosite.db` 转换生成 `.srs` 缓存（`<externalAssets>/srs/`，db 更新后自动重建）。
- [`libcore/build.sh`](libcore/build.sh): 编译 Go 核心的本地脚本（gomobile bind 前先 `go mod tidy`：go.sum 不入库，由构建时现场重建；go.mod 直接依赖版本已按官方 sing-box go.mod 钉死）。bind 时从 `nb4a.properties` 读取 `SINGBOX_VERSION`，经 `-ldflags "-X github.com/sagernet/sing-box/constant.Version=..."` 注入内核版本号（官方 `constant.Version` 默认为 `"unknown"`，不注入则关于页 sing-box 版本显示 unknown）。
  - sing-box 版本以 [`nb4a.properties`](nb4a.properties) 的 `SINGBOX_VERSION` 为唯一真实来源；[`libcore/go.mod`](libcore/go.mod) 中 `github.com/sagernet/sing-box v0.0.0` 仅为 module graph 占位，实际源码始终由 `replace => ../../sing-box` 提供。源码获取脚本强制使用官方 remote、强制刷新指定 tag，并校验 `HEAD` 与该 tag 的 commit 完全一致；发布/预览/CI 的内核缓存键均包含 `nb4a.properties`，版本变化不会复用旧 AAR。
  - [`libcore/interface_monitor.go`](libcore/interface_monitor.go) 在 debug 级别记录同一默认接口的重复上报及 `skip ResetNetwork`，在 info 级别记录默认接口丢失与回调数，便于把 Hysteria2 共享会话 EOF 与真实切网事件精确对时。
- [`libcore/device/`](libcore/device/), [`ech/`](libcore/ech/), [`procfs/`](libcore/procfs/), [`stun/`](libcore/stun/): Go 核心的子模块，处理设备、ECH、进程文件系统和 STUN 测试。
- [`libcore/protocol/`](libcore/protocol/): libcore 侧自定义/覆盖的 sing-box 协议实现，在 [`libcore/box_include.go`](libcore/box_include.go) 中注册。
  - `juicity/`: Juicity outbound（官方内核无此协议，基于 `dyhkwong/sing-juicity`）。
  - `http/`: 对 sing-box `http` outbound 的**覆盖实现**（在 sing-box 自身注册之后再次注册同名 `"http"` 类型，registry 后注册生效）。行为差异：TLS 启用且用户未显式配置 ALPN 时默认提供 `["h2", "http/1.1"]`，TLS 握手后按 ALPN 协商结果分流——协商到 `h2` 走 HTTP/2 CONNECT（基于 `golang.org/x/net/http2`，上行流为 `io.Pipe` 请求体、响应体为下行流），否则保持原有 HTTP/1.1 CONNECT。用于兼容 h2-only 的 HTTPS 代理节点（对齐 v2ray 系核心行为）；用户可在节点配置中显式填写 ALPN=`http/1.1` 回退旧行为。

> 内核迁移的完整调研与后续计划见 [`ROO_KERNEL_TODO.md`](ROO_KERNEL_TODO.md)。已知降级项（debug 日志兜底，待用户反馈后按案例修）：SSR/Snell 节点、通过 Clash API（yacd 面板）切换 selector 节点不触发 `selector_OnProxySelected` 回调（app 内切换不受影响）、WireGuard 节点（配置生成需从 outbound 迁移为 endpoint）。

---

## 2. 构建流程 (Build Process)

> 本项目**不需要本地 Go 环境**，也**不需要本地克隆 sing-box 仓库**。所有编译（Go 核心 + Android APK）均在 GitHub Actions 中完成。开发者（AI）只需写好代码并提交，由用户 push 到 GitHub Actions 上验证编译与真机测试。

项目的构建分为两步（均在 CI 中执行）：
1. **编译底层 Go 核心**：
   - 源码获取：[`buildScript/lib/core/get_source.sh`](buildScript/lib/core/get_source.sh) 读取 `nb4a.properties` 的唯一 `SINGBOX_VERSION`，校验其 tag 格式，在 CI 中将**官方** `SagerNet/sing-box` 浅克隆到仓库同级目录 `../sing-box`（`libcore/go.mod` 以 `replace` 指向它）；已有目录会被无条件校正到官方 remote、强制刷新目标 tag，并校验 `HEAD == tag commit`，不一致即终止构建。
   - 使用 `gomobile` 工具，运行 `buildScript/lib/core/build.sh` 或 `libcore/build.sh`（bind 前先 `go mod tidy` 重建依赖锁定）。
   - 编译生成 `app/libs/libcore.aar` 库。
2. **编译 Android 应用程序**：
   - 使用 Gradle 编译 Android 应用。
   - 运行 `./gradlew app:assembleOssRelease` 编译生成 OSS 版本的 APK，该步骤会自动将 `libcore.aar` 打包进 APK 中。

---

## 3. 开发备忘与协作规范 (Developer Notes)

### 3.1 GitHub Actions 小步快跑模式
- **背景**：项目所有者个人不做安卓开发，**本地不安装 Go 环境**，因此本项目的开发采用 **GitHub Actions 小步快跑** 的模式。
- **CI/CD 流程**：
  - 核心构建工作流定义在 [`.github/workflows/build.yml`](.github/workflows/build.yml) 中。
  - 每次向 `main` 分支提交代码或手动触发（`workflow_dispatch`）时，GitHub Actions 会自动运行构建。
  - 工作流会先检查并缓存 `libcore.aar`（基于 `libcore` 目录和构建脚本的哈希值），避免重复编译 Go 核心以节省时间。
  - 随后，工作流会使用 Gradle 编译生成 OSS 版本的 APK，并将生成的 APK 上传为 Artifact（命名为 `APKs`）。
- **开发建议**：
  - 开发者在修改代码时，应尽量保持**小步快跑**，每次完成一个微小的、自洽的修改后即提交代码。
  - 提交后，通过 GitHub Actions 自动验证编译是否通过，并下载生成的测试 APK 进行真机测试。
  - **不要询问用户本地是否有 Go 环境**：本地一律不装 Go，也不做本地编译。AI 只需把代码写好、改完整，交由用户 push 到 GitHub Actions 上验证即可。
  - **依赖查询走 firecrawl MCP**：本地已删除 sing-box 等仓库克隆，不再依赖本地源码。需要查询 sing-box 官方源码、go.mod 依赖版本、API 定义等外部信息时，直接通过 firecrawl MCP 联网访问 GitHub 获取。

### 3.2 文档同步规范
- **核心要求**：每次对项目结构、关键模块、新增协议、构建流程或配置进行修改时，**必须**同步更新本文件 (`REPO_SCHEMA.md`)。
- **检查清单**：在每次提交前，请务必对照 `ROO_TODO.example.md` 中的检查清单，确保文档与代码保持 100% 一致。

### 3.3 `.roo/` 辅助脚本工作流
- AI 调研、格式解析、竞态模拟与静态校验使用的 Python 辅助脚本统一放在 [`.roo/`](.roo/) 目录，避免在仓库根目录散落 `roo_*.py` 文件；`ROO_KERNEL_TODO.md` 等项目文档不属于脚本，继续保留在根目录。
- 脚本统一从仓库根目录通过 `uv run .roo/<脚本名>.py [参数]` 执行，脚本若需按自身位置推导仓库根目录，应使用 `Path(__file__).parent.parent`。
- 当前快捷方式静态校验见 [`.roo/roo_check_shortcuts.py`](.roo/roo_check_shortcuts.py)：检查四个 ID/目标组件、Preview/Release 包名、Launcher Activity 元数据唯一性及目标 Activity 导出状态；修改快捷方式或安装包名后必须运行。
