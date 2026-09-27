# Design

## Context

`preview.yml` 现状（`27982c8` 重写后）：`push main` 自动触发 + `workflow_dispatch` 带 `publish` 输入；`push` 或 `publish` 时 `gh release create` 创建 pre-release；不改包名。v1.x 时代（`d02a91c`）为手动触发、构建前 `sed` 改 `PACKAGE_NAME=com.nb4a.throne.debug`、仅 `upload-artifact`。pre 版本计算（`Build number` 步骤）是后来加入的且运行良好，需保留。`release_assets.py` 同时被 `release.yml`（stable）与 `preview.yml`（preview）调用，当前无条件生成 `throne-update.json`。动机见 proposal.md - Why。

## Goals / Non-Goals

**Goals:**

- Preview 仅手动触发，产物仅存 workflow artifact，不创建 Release。
- 预览 APK 使用 `.debug` 包名，可与正式版并存安装。
- 预览产物只含 APK + `SHA256SUMS`，不含 `throne-update.json`。
- 保留现有 pre 自动版本计算与 skip 逻辑。

**Non-Goals:**

- 不改动 `.github/actions/android-build` 等共享 composite action。
- 不改动 `release.yml`、`ci.yml` 的行为。
- 不改动 `nb4a.properties` 仓库内容。
- 不调整 artifact 保留期（维持 14 天）。

## Decisions

1. **触发器只留 `workflow_dispatch`，删除 `publish` 输入与 `gh release create` 步骤**，`Upload artifact` 的条件简化为仅判断 `skip != 'true'`。
   - 备选：保留 `publish` 输入但默认 false——被否，既然不再发 Release，输入无意义，徒增维护面。
   - `permissions: contents: write` 与 `concurrency` 是否保留：`contents: write` 仅服务于 release 创建，可一并移除；concurrency 保留（防止重复手动触发并发构建）。

2. **`.debug` 包名在 `preview.yml` 的 Build 步骤前用 `sed` 改写 `nb4a.properties`**（与 v1.x 一致），只影响该次工作流检出的工作区。
   - 备选：给 `android-build` action 加输入——被否（用户明确选择不动共享 action）。
   - 注意：`Build number` 步骤读取 `VERSION_NAME`，sed 只改 `PACKAGE_NAME` 行，互不影响；sed 应放在 Build 之前、版本计算之后或之前均可，但必须在 Gradle 执行前。

3. **`release_assets.py` 按通道决定是否生成 `throne-update.json`**：`channel == "stable"` 才写清单，`preview` 只复制 APK 并写 `SHA256SUMS`。
   - 备选：加 CLI 开关——被否，通道参数已存在且语义吻合（preview 通道本就不走应用内更新），无需新参数。
   - `release_assets.py` 中其余校验（签名、ABI、版本一致性）对 preview 仍全部保留；`.debug` 包名经 `output-metadata.json` 的 `applicationId` 与 badging 比对自然通过。

## Risks / Trade-offs

- [预览不再有 Release，外部若有订阅预览 Release 的路径会失效] → 提案已确认当前无正式依赖方；artifact 保留 14 天可满足按需取用。
- [sed 改写使 APK 包名与仓库 `PACKAGE_NAME` 不一致，`release_assets.py` 校验可能失败] → 校验比对的是 `output-metadata.json` 与 APK badging，两者均来自同一次构建，一致；`throne-update.json` 不再生成，无清单层面的包名错位。
- [手动触发依赖维护者记得跑，预览可能滞后] → 这是用户明确接受的取舍（v1.x 即如此）。

## Migration Plan

单批次改完后手动触发一次 Preview 工作流验证：artifact 含 3 个 ABI 的 APK + `SHA256SUMS`、无 `throne-update.json`、无新 Release、APK 包名为 `com.nb4a.throne.debug`。回滚即 revert 该提交。

## Open Questions

（无）