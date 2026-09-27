# Proposal

## Why

当前 `preview.yml` 在推送到 main 时自动构建并创建 GitHub pre-release，导致预览产物堆积在 Release 列表中、每次 push 都消耗构建资源；而 v1.x 时代的 preview 是手动触发、仅产出 workflow artifact、并使用 `.debug` 包名与正式版并存安装的形态，这套形态更符合当前维护者"自己去 Action 页面点、产物放 artifact 即可"的使用习惯。

## What Changes

- `preview.yml` 触发方式改为仅 `workflow_dispatch` 手动触发，移除 `push` 到 main 的自动触发。
- 移除 `publish` 输入与 `gh release create` 的 pre-release 发布步骤；产物始终只通过 `actions/upload-artifact` 上传（保留 14 天留存），不再创建任何 GitHub Release。
- 构建前通过 `sed` 将 `nb4a.properties` 的 `PACKAGE_NAME` 改写为 `com.nb4a.throne.debug`（与 v1.x 做法一致，仅作用于 preview 工作流的临时检出，不影响 `release.yml`/`ci.yml` 与仓库文件）。
- `release_assets.py` 仅在 `stable` 通道生成 `throne-update.json`（应用内更新清单）；`preview` 通道只产出 APK 与 `SHA256SUMS`，因为预览不再发布 Release、不走应用内更新。
- 保留现有 pre 自动版本计算：`v${VERSION_NAME}-pre.${N}`（N 为自最近正式 tag 起的提交数，上限 998，HEAD 恰为正式 tag 时跳过）。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `repository-governance`："发布与预览工作流分离"需求中关于预览的部分发生变化：预览由"push 自动构建 + pre-release 发布"改为"仅手动触发 + 仅 workflow artifact、不创建 Release"，且预览构建使用 `.debug` 包名；正式发布的既有需求不变。同时"应用版本来源唯一"需求需澄清 preview 工作流在构建期对 `PACKAGE_NAME` 的 `.debug` 覆写属于工作流级临时改写，不改变仓库中 `nb4a.properties` 的唯一来源地位。

## Impact

- 受影响文件：`.github/workflows/preview.yml`（触发器、发布步骤、包名覆写）、`buildScript/release_assets.py`（`throne-update.json` 仅 stable 生成）。
- 受影响能力规范：`openspec/specs/repository-governance/spec.md`。
- 不涉及 throne 核心、Android 应用代码；构建链仅涉及共享 composite action 之外的 preview 工作流编排（`android-build` action 不改动）；开发工具布局不变。
- 行为兼容性：预览不再产生 Release 与 `throne-update.json`，依赖预览 Release 的外部订阅/更新路径失效（当前无正式依赖方，属预期移除）。