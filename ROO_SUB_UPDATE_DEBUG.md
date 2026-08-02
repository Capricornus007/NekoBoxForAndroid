# ROO_KERNEL_SCAN — 订阅更新失败 "h3: ... no recent network activity" 排查报告

> 排查日期：2026-08-02
> 涉及日志：`NB4A 2875215121204276759.log`、`NB4A 8014127680637238957.log`
> 涉及代码：[`libcore/http.go`](libcore/http.go)、[`app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt`](app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt)

---

## 1. 问题概述

用户反馈订阅更新失败，Toast 报错形如 **"h3: ... no recent network activity"**（以 `h3:` 开头、以 `no recent network activity` 结尾）。

---

## 2. 错误来源定位

该报错来自 [`libcore/http.go`](libcore/http.go:232) 的 `doH3Direct()` 函数：

- **`h3:` 前缀**：来自 [`doH3Direct()`](libcore/http.go:302) 第 302 行 `t = "h3"`，表示这是 **HTTP/3 (QUIC) 直连请求**失败。
- **`no recent network activity`**：是 quic-go 库的 **idle timeout（空闲超时）** 错误。当 QUIC 连接建立后，在 `MaxIdleTimeout` 内没有收到任何数据包时触发。原代码将 [`MaxIdleTimeout` 硬编码为 1 秒](libcore/http.go:275)。

---

## 3. 完整失败链路

1. 订阅更新时，[`RawUpdater.doUpdate`](app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt:64) 调用 `Libcore.newHttpClient()`，并调用 `trySocks5(...)` + `tryH3Direct()`。
2. 日志显示 `isAutoConnect: false`，**代理未连接**，本地 mixed 入站不可用 → socks5 连接失败 → 回退到 `doH3Direct()`。
3. `doH3Direct()` 并发发起两个直连请求，**两者都失败**：
   - **HTTP(s) with ECH**：ECH 依赖 DNS 获取 ECH 配置。两份日志均显示订阅域名 `qel6y.no-mad-sub.one` 的 DNS 查询返回 `result_code:408, found = 0`（**解析失败**）。
   - **H3 HTTPS**：QUIC 直连 idle timeout（"no recent network activity"）。
4. 两个错误经 `errors.Join` 合并，最终 Toast 显示 "h3: ... no recent network activity"。

---

## 4. 反思的 5-7 个可能来源

1. **HTTP/3 (QUIC) 直连被网络阻断**（UDP 443/QUIC 被运营商或防火墙阻断）→ 最可能
2. **HTTP(s) with ECH 失败**（ECH 依赖 DNS 获取配置，DNS 解析失败）→ 最可能
3. **socks5 代理不可用**（代理未连接，回退到直连）
4. **DNS 解析失败**（日志中 `result_code:408, found=0`）
5. **订阅服务器不可达/响应慢**
6. **`MaxIdleTimeout: 1秒` 设置过短**（即使网络正常，服务器响应稍慢也会触发）
7. **10 秒整体超时**（两个请求都在超时内失败）

---

## 5. 提炼的最可能来源（1-2 个）

**在代理未连接的情况下，订阅更新回退到 H3 直连，但直连（QUIC 和 ECH）都失败了**——核心是 QUIC 直连被阻断（idle timeout），叠加 ECH 依赖的 DNS 解析失败。

---

## 6. 已做的修改

### 6.1 调大 `MaxIdleTimeout`（1s → 10s）

[`libcore/http.go`](libcore/http.go:277) 中 H3 HTTPS 请求的 `QUICConfig.MaxIdleTimeout` 由 `time.Second` 调大为 `10 * time.Second`，与 `doH3Direct()` 整体 10s 超时保持一致，避免 QUIC 空闲超时（1s）过早触发导致误报。

> 注：按用户要求，**解析逻辑暂未修改**。

### 6.2 添加验证日志

在 [`libcore/http.go`](libcore/http.go:215) 添加 3 处日志，用于区分 socks5 回退、http(s)/h3 各自的具体失败原因：

- `Execute()` 中记录 socks5 连接失败及回退到 H3 直连
- `doH3Direct()` 中记录每个请求（http(s)/h3）的具体失败原因

---

## 7. 内核全流程分析（确认是否符合预期）

### 7.1 `Execute()` 入口（[`libcore/http.go`](libcore/http.go:209)）

```
分支 A：tryH3Direct && !trySocks5  → 直接 doH3Direct()
分支 B：trySocks5                  → 先走 socks5 代理
         ├─ 成功且 200            → 返回
         ├─ 失败且 errFailConnectSocks5 且 tryH3Direct → 回退 doH3Direct()
         ├─ 失败但非 errFailConnectSocks5 → 直接返回错误（不回退）
         └─ 成功但非 200          → 返回错误
```

订阅更新走**分支 B**（`trySocks5` + `tryH3Direct` 均被调用）。代理未连接时 socks5 失败 → `errFailConnectSocks5` → 回退 `doH3Direct()`。**符合预期**。

### 7.2 `doH3Direct()`（[`libcore/http.go`](libcore/http.go:234)）

- 10 秒整体超时（`context.WithTimeout`）。
- 并发两个请求：ECH HTTPS（`t="http(s)"`）与 H3 HTTPS（`t="h3"`）。
- `http://` scheme 时仅保留 ECH 请求（`funcs = funcs[:1]`）。
- 每个请求 goroutine：
  - **失败**（`rsp == nil || err != nil`）→ 记录到 `finalErr`（格式化为 `http(s): ...` / `h3: ...`），递增 `failedCount`；当 `failedCount >= len(funcs)` 且 `successCount == 0` 时 `cancel()`。
  - **非 200** → 记录到 `finalErr`，**但不递增 `failedCount`，也不 `cancel()`**。
  - **200** → 发送到 `successCh`（第一个成功者），后续成功者关闭 body。
- 主 select：`successCh` → 返回成功；`ctx.Done()` → 返回 `finalErr`。

### 7.3 发现的潜在问题

**非 200 状态码不递增 `failedCount`，也不触发 `cancel()`**（[`libcore/http.go`](libcore/http.go:320)）：

若两个请求都返回非 200（如 403/404），`failedCount` 保持 0，`cancel()` 不会被调用，主 select 会**等满 10 秒**才返回 `finalErr`。即：即使服务器快速返回了非 200 响应，订阅更新也会白白等待 10 秒才失败。

> 该问题不影响最终失败结果，仅造成不必要的等待。按用户要求"解析逻辑先不改"，**暂未修复**，留待后续评估。

---

## 8. 后续建议

1. **重新编译并抓取带新验证日志的 logcat**，确认 socks5 回退、http(s)/h3 各自的具体失败原因，验证诊断。
2. 评估是否修复"非 200 不触发 cancel"的等待问题。
3. 若确认 QUIC 直连被网络阻断，可考虑在 H3 直连失败时回退到普通 HTTPS 直连，提升订阅更新成功率。
4. 若确认订阅域名 DNS 解析失败（`result_code:408`），需进一步排查 DNS 解析路径（ECH 的 `fetchEchKeys` 依赖 `gLocalDNSTransport`）。
