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
  - 支持 Shadowsocks, VMess, Trojan, Hysteria, Juicity, Naive, WireGuard 等协议的配置解析与转换。
  - `forTest`（URL 测速）配置不生成 `experimental` 块：官方内核在 `PlatformLogWriter != nil` 时无条件创建 CacheFile（bbolt）与 ClashServer，无显式 `path` 时所有实例共用工作目录（`no_backup`）下的 `cache.db`。主进程批量测速并发创建大量 `TestInstance` 曾共享该文件，bbolt freelist 被写坏后每次 `box.Start` 的清理 batch 在 bbolt 定时器 goroutine 中 `page already freed` panic（异步 goroutine 中无法 recover）→ 主进程 SIGABRT 闪退。最终修复在 Go 侧：测速实例走 [`NewTestSingBoxInstance`](libcore/box.go)（不注册 PlatformLogWriter → 官方 `needCacheFile`/`needClashAPI` 均不触发），测速完全不产生 cache.db；[`SagerNet.onCreate`](app/src/main/java/io/nekohasekai/sagernet/SagerNet.kt)（main/bg 进程）启动时仍清扫存量共享 `cache.db` 与历史残留 `urltest_*.db` 实现老用户自愈。另：测速拨号依赖平台接口监视器，`NativeInterface.startDefaultInterfaceMonitor` 必须同步注册（异步曾致首拨竞态秒报 `no available network interface`），`SagerNet` 两个进程常驻 `DefaultNetworkListener` 预热缓存。**测速配置须与正式连接逐项对齐**（对齐 husi）：`ipv6Mode` 与 outbound `domain_strategy` 均沿用用户设置——曾分别强制 `IPv6Mode.ENABLE` 与空串，测速拨号的协议族/解析结果与真实路径不同，造成"测速 err 实际能用、测速成功实际不能用"的双向失真；DNS 侧 forTest 保持 dns-direct 收尾（与正式配置中服务器域名经 dns 规则归 dns-direct 的解析路径一致），无 fakeip/sniff/路由规则/experimental 属测速本就不需要之合理差异。
  - `SingBoxOutboundParser.kt`: 将 sing-box 配置中的单个 outbound JSON 还原为原生协议 Bean（支持 shadowsocks/vmess/vless/trojan/hysteria/hysteria2/tuic/socks/http/wireguard/anytls，含 TLS/transport/multiplex 子块解析）。用于订阅返回完整 sing-box 配置（含 `outbounds`）的场景：`RawUpdater.parseJSON` 的 `outbounds` 分支对每个 outbound 优先调用 `parseSingBoxOutbound()` 还原原生节点，不支持的类型或解析失败时回退为 `ConfigBean`（自定义 JSON）；`dns`/`block`/`direct`/`selector`/`urltest` 类型的 outbound 始终跳过。
- [`io/nekohasekai/sagernet/ui/`](app/src/main/java/io/nekohasekai/sagernet/ui): 各种 Activity 和 Fragment 界面。
  - `MainActivity.kt`: 应用主界面。预览版启动时弹出提示对话框（标题为 `BuildConfig.PRE_VERSION_NAME`），提供「不再显示」按钮（`preview_hint_dont_show_again`）：点击后把当前版本号写入 `DataStore.previewHintDismissedVersion`（`Key.PREVIEW_HINT_DISMISSED_VERSION`），之后仅对已忽略的版本不再弹窗，发布新预览版时会重新提示。
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
- [`libcore/box.go`](libcore/box.go) / [`box_include.go`](libcore/box_include.go): 与 `sing-box` 核心的集成与初始化。流量统计使用官方 `experimental/v2rayapi.StatsService`；`UrlTest` 为自实现（经 box 默认 outbound 拨号的 HTTP GET 计时，替代 libneko/speedtest），其链路（`UrlTest`/`urlTest`）的 debug 级日志复用现有级别过滤管线——经 `boxPlatformLogWriter.WriteMessage(sblog.LevelDebug, ...)` 输出，受 `platformLogLevel` 门控（仅 trace/debug 级别放行），而非直接打标准库 log；`ResetAllConnections` 因官方无 conntrack 暂为 debug 日志兜底。SSR/Snell 协议官方内核不支持，已摘除（配置含此类节点时 `box.New` 报错，待有具体案例再评估）。WireGuard 官方 1.13 起仅支持 endpoint（outbound 已移除），`box_include.go` 镜像官方注册了报错 stub；Kotlin 侧 `WireGuardFmt` 的 outbound→endpoint 配置迁移待做（见 ROO_KERNEL_TODO 已知降级项）。
- [`libcore/platform_box.go`](libcore/platform_box.go): 实现官方 `adapter.PlatformInterface`（旧 `experimental/libbox/platform` 包已不存在）：TUN 创建（`OpenInterface`）、fd protect、按应用分包（`FindConnectionOwner`）、默认接口监视器、平台网络接口枚举（`NetworkInterfaces`，官方拨号路径硬性要求）等。[`libcore/interface_monitor.go`](libcore/interface_monitor.go): 完整 `tun.DefaultInterfaceMonitor` 实现：Kotlin 经 JNI 回调 `InterfaceUpdateListener.UpdateDefaultInterface` 上报物理默认接口（复用 app 侧 `DefaultNetworkListener`，避开 VPN 接口）。[`libcore/network_interface.go`](libcore/network_interface.go) / [`iterator.go`](libcore/iterator.go) / [`link_flags_unix.go`](libcore/link_flags_unix.go): `GetInterfaces` JNI 桥接类型与转换辅助；Kotlin 侧 `NativeInterface.getInterfaces()` 枚举网络接口（类型/MTU/地址/flags/metered）。JNI 侧 `BoxPlatformInterface` 相应新增 `StartDefaultInterfaceMonitor`/`CloseDefaultInterfaceMonitor`/`GetInterfaces`。
- [`libcore/log.go`](libcore/log.go): `neko_log` 的自实现替代（带大小截断的文件日志 writer，接管标准库 log）。日志文件有**两条写入通道，级别过滤分别实现**：①sing-box 内核 → `PlatformWriter`：官方内核对 `PlatformWriter` 通道**不做级别过滤**（`log/observable.go` 无条件转发含 trace 在内的所有级别），过滤在本侧实现——[`platform_box.go`](libcore/platform_box.go) 的 `WriteMessage` 按 `platformLogLevel` 丢弃超限消息，级别由 [`box.go`](libcore/box.go) `newSingBoxInstance` 从配置 `log.level` 解析记录（空级别对齐官方默认 trace 全放行）；②Kotlin `Logs.x()` → JNI `nekoLogPrintln` → std log：官方同样不过滤，门控在源头 [`Logs.kt`](app/src/main/java/io/nekohasekai/sagernet/ktx/Logs.kt)（`d` 需 logLevel≥3、`i` ≥2、`w` ≥1、`e` 恒放行，读取失败放行）。插件进程（hysteria 等）的 stdout/stderr 经 `GuardedProcessPool` 直接转发入文件，其级别由 `BoxInstance.kt` 启动参数 `--log-level` 控制（与 ConfigBuilder 同一套 0~4 映射）。
- [`libcore/protect.go`](libcore/protect.go): `libneko/protect_server` 的自实现替代（unix socket 接收主进程经 SCM_RIGHTS 发来的 fd 并回调 `VpnService.protect`）。
- [`libcore/ruleset.go`](libcore/ruleset.go): geo 规则集预处理（替代 fork 的 `nekoutils` geoip/geosite 钩子）。官方 local rule-set 只认真实文件路径，本模块在 `box.New` 前改写配置：官方格式（`geoip-cn`/`geosite-cn`）优先指向 `<externalAssets>/` 下已存在的官方 `.srs`；老 nb4a 格式（`geoip:cn`/`geosite:cn`）或官方 `.srs` 缺失时，从本地 `geoip.db`/`geosite.db` 转换生成 `.srs` 缓存（`<externalAssets>/srs/`，db 更新后自动重建）。
- [`libcore/build.sh`](libcore/build.sh): 编译 Go 核心的本地脚本（gomobile bind 前先 `go mod tidy`：go.sum 不入库，由构建时现场重建；go.mod 直接依赖版本已按官方 sing-box go.mod 钉死）。bind 时从 `nb4a.properties` 读取 `SINGBOX_VERSION`，经 `-ldflags "-X github.com/sagernet/sing-box/constant.Version=..."` 注入内核版本号（官方 `constant.Version` 默认为 `"unknown"`，不注入则关于页 sing-box 版本显示 unknown）。
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
   - 源码获取：[`buildScript/lib/core/get_source.sh`](buildScript/lib/core/get_source.sh) 读取 `nb4a.properties` 的 `SINGBOX_VERSION`，在 CI 中将**官方** `SagerNet/sing-box` 浅克隆到仓库同级目录 `../sing-box`（`libcore/go.mod` 以 `replace` 指向它）；已存在的非官方（旧 fork）克隆会被强制重定向到官方仓库。
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
