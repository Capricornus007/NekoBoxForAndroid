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
- [`io/nekohasekai/sagernet/fmt/`](app/src/main/java/io/nekohasekai/sagernet/fmt): 各种代理协议的配置格式化与解析。
  - 支持 Shadowsocks, VMess, Trojan, Hysteria, Juicity, Naive, WireGuard 等协议的配置解析与转换。
  - `SingBoxOutboundParser.kt`: 将 sing-box 配置中的单个 outbound JSON 还原为原生协议 Bean（支持 shadowsocks/vmess/vless/trojan/hysteria/hysteria2/tuic/socks/http/wireguard/anytls，含 TLS/transport/multiplex 子块解析）。用于订阅返回完整 sing-box 配置（含 `outbounds`）的场景：`RawUpdater.parseJSON` 的 `outbounds` 分支对每个 outbound 优先调用 `parseSingBoxOutbound()` 还原原生节点，不支持的类型或解析失败时回退为 `ConfigBean`（自定义 JSON）；`dns`/`block`/`direct`/`selector`/`urltest` 类型的 outbound 始终跳过。
- [`io/nekohasekai/sagernet/ui/`](app/src/main/java/io/nekohasekai/sagernet/ui): 各种 Activity 和 Fragment 界面。
  - `MainActivity.kt`: 应用主界面。预览版启动时弹出提示对话框（标题为 `BuildConfig.PRE_VERSION_NAME`），提供「不再显示」按钮（`preview_hint_dont_show_again`）：点击后把当前版本号写入 `DataStore.previewHintDismissedVersion`（`Key.PREVIEW_HINT_DISMISSED_VERSION`），之后仅对已忽略的版本不再弹窗，发布新预览版时会重新提示。
  - `SettingsFragment.kt`: 设置界面。「入站设置」中含「禁用混合入站」开关（`disableMixedInbound`，见 `SettingsPreferenceFragment.kt`）：仅在 TUN 模式下真正生效（`DataStore.mixedInboundDisabled`），开启后 `ConfigBuilder` 不再生成 mixed 入站及其专属的 `inbound = [mixed-in]` 路由规则、不再监听本地代理端口，`VpnService` 同时跳过 `appendHttpProxy`；此时「代理端口」设置项变灰且摘要显示「已禁用」，「追加 HTTP 代理至 VPN」开关被强制关闭（持久化，避免置灰勾选态造成"锁定开启"错觉）并变灰（其依赖项 `httpProxyBypass` 经 dependency 级联一并变灰）。在系统代理模式下开启该开关会 Toast 提示"禁用只在TUN模式有效，当前监听端口：xxx"且不影响混合入站。
  - `AboutFragment.kt`: 关于界面。版本更新检查：「检查正式版更新」为灰色禁用项（Throne 尚未发布正式版，点击仅弹出 toast `release_not_available`）；「检查预览版更新」请求 GitHub `releases/latest` API，将远端 release 名（即 git tag，形如 `v1.4.2-m20-10`）与本地 `BuildConfig.VERSION_NAME` 按 `-` 分段、逐段提取数字组从左到右比较（左侧段优先级高于右侧，如 `v1.2.3-m21-1` > `v1.2.3-m20-100`），见 `compareVersionNames()`。
- [`io/nekohasekai/sagernet/widget/`](app/src/main/java/io/nekohasekai/sagernet/widget): 自定义 UI 控件。

#### 1.2.2 `moe.matsuri.nb4a` (NekoBox 专属扩展)
这是 NekoBox 专属的扩展和定制代码，Nekobox是Throne的前生，故兼容之：
- [`moe/matsuri/nb4a/NativeInterface.kt`](app/src/main/java/moe/matsuri/nb4a/NativeInterface.kt): 与底层 Go 核心 (`libcore`) 交互的 JNI 接口，实现了 `libcore.BoxPlatformInterface` 和 `libcore.NB4AInterface`。
- [`moe/matsuri/nb4a/SingBoxOptions.java`](app/src/main/java/moe/matsuri/nb4a/SingBoxOptions.java) / [`SingBoxOptionsUtil.kt`](app/src/main/java/moe/matsuri/nb4a/SingBoxOptionsUtil.kt): Sing-box 相关的配置选项和工具类。
- [`moe/matsuri/nb4a/proxy/`](app/src/main/java/moe/matsuri/nb4a/proxy): 额外的代理协议（如 AnyTLS, ShadowTLS, NekoBean 等）和配置绑定。
- [`moe/matsuri/nb4a/ui/`](app/src/main/java/moe/matsuri/nb4a/ui) / [`utils/`](app/src/main/java/moe/matsuri/nb4a/utils): 专属的 UI 控件和工具类。

#### 1.2.3 资源文件 (`app/src/main/res/`)
- `layout/`: 界面布局 XML 文件。
- `menu/`: 菜单 XML 文件。
- `xml/`: 偏好设置 XML 文件（如 `global_preferences.xml`, `shadowsocks_preferences.xml` 等）。
- `values-zh-rCN/`: 简体中文本地化字符串。

---

### 1.3 底层核心模块 (`libcore/`)
Go 语言编写的底层核心，负责高性能的网络处理：
- [`libcore/nb4a.go`](libcore/nb4a.go): Go 核心的入口，导出 `InitCore` 等函数，供 Android 端通过 JNI 调用。
- [`libcore/box.go`](libcore/box.go) / [`box_include.go`](libcore/box_include.go): 与 `sing-box` 核心的集成与初始化。
- [`libcore/build.sh`](libcore/build.sh): 编译 Go 核心的本地脚本。
- [`libcore/device/`](libcore/device/), [`ech/`](libcore/ech/), [`procfs/`](libcore/procfs/), [`stun/`](libcore/stun/): Go 核心的子模块，处理设备、ECH、进程文件系统和 STUN 测试。
- [`libcore/protocol/`](libcore/protocol/): libcore 侧自定义/覆盖的 sing-box 协议实现，在 [`libcore/box_include.go`](libcore/box_include.go) 中注册。
  - `juicity/`: Juicity outbound。
  - `http/`: 对 sing-box `http` outbound 的**覆盖实现**（在 sing-box 自身注册之后再次注册同名 `"http"` 类型，registry 后注册生效）。行为差异：TLS 启用且用户未显式配置 ALPN 时默认提供 `["h2", "http/1.1"]`，TLS 握手后按 ALPN 协商结果分流——协商到 `h2` 走 HTTP/2 CONNECT（基于 `golang.org/x/net/http2`，上行流为 `io.Pipe` 请求体、响应体为下行流），否则保持原有 HTTP/1.1 CONNECT。用于兼容 h2-only 的 HTTPS 代理节点（对齐 v2ray 系核心行为）；用户可在节点配置中显式填写 ALPN=`http/1.1` 回退旧行为。

---

## 2. 构建流程 (Build Process)

项目的构建分为两步：
1. **编译底层 Go 核心**：
   - 使用 `gomobile` 工具，运行 `buildScript/lib/core/build.sh` 或 `libcore/build.sh`。
   - 编译生成 `app/libs/libcore.aar` 库。
2. **编译 Android 应用程序**：
   - 使用 Gradle 编译 Android 应用。
   - 运行 `./gradlew app:assembleOssRelease` 编译生成 OSS 版本的 APK，该步骤会自动将 `libcore.aar` 打包进 APK 中。

---

## 3. 开发备忘与协作规范 (Developer Notes)

### 3.1 GitHub Actions 小步快跑模式
- **背景**：项目所有者个人不做安卓开发，因此本项目的开发采用 **GitHub Actions 小步快跑** 的模式。
- **CI/CD 流程**：
  - 核心构建工作流定义在 [`.github/workflows/build.yml`](.github/workflows/build.yml) 中。
  - 每次向 `main` 分支提交代码或手动触发（`workflow_dispatch`）时，GitHub Actions 会自动运行构建。
  - 工作流会先检查并缓存 `libcore.aar`（基于 `libcore` 目录和构建脚本的哈希值），避免重复编译 Go 核心以节省时间。
  - 随后，工作流会使用 Gradle 编译生成 OSS 版本的 APK，并将生成的 APK 上传为 Artifact（命名为 `APKs`）。
- **开发建议**：
  - 开发者在修改代码时，应尽量保持**小步快跑**，每次完成一个微小的、自洽的修改后即提交代码。
  - 提交后，通过 GitHub Actions 自动验证编译是否通过，并下载生成的测试 APK 进行真机测试。

### 3.2 文档同步规范
- **核心要求**：每次对项目结构、关键模块、新增协议、构建流程或配置进行修改时，**必须**同步更新本文件 (`REPO_SCHEMA.md`)。
- **检查清单**：在每次提交前，请务必对照 `ROO_TODO.example.md` 中的检查清单，确保文档与代码保持 100% 一致。
