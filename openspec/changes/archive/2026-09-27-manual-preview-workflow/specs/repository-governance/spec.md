# Spec Delta

## MODIFIED Requirements

### Requirement: 应用版本来源唯一

应用版本 MUST 以 `nb4a.properties` 的 `VERSION_NAME`（及 `VERSION_CODE`）为唯一真实来源；包名 MUST 以 `PACKAGE_NAME` 为准。发布物（tag、Release 标题、预览版本号）MUST 随该值派生，避免构建与发布版本错位。preview 工作流 MAY 在其检出的工作区内于构建前将 `PACKAGE_NAME` 临时改写为 `.debug` 后缀包名（如 `com.nb4a.throne.debug`），该改写 MUST 仅作用于该次工作流运行，MUST NOT 提交回仓库，也 MUST NOT 影响 `release.yml` 与 `ci.yml` 的构建。

#### Scenario: 升级应用版本

- **WHEN** 维护者修改 `nb4a.properties` 的 `VERSION_NAME` 并发布
- **THEN** 构建产物、发布 tag 与标题均使用新版本号

#### Scenario: 预览构建使用 .debug 包名

- **WHEN** preview 工作流构建预览 APK
- **THEN** APK 的 applicationId 为 `PACKAGE_NAME` 加 `.debug` 后缀，可与正式版并存安装
- **AND** 仓库中的 `nb4a.properties` 与 `release.yml`、`ci.yml` 的构建仍使用原始 `PACKAGE_NAME`

### Requirement: 发布与预览工作流分离

GitHub Actions MUST 通过 `release.yml` 与 `preview.yml` 分离正式发布与预览构建。正式发布 MUST 仅允许手动触发，MUST 从 `nb4a.properties` 的 `VERSION_NAME` 派生 tag `v${VERSION_NAME}`；当该 tag 已存在时 MUST 构建失败并提示提升版本号，而不复用旧 tag。发布入口 MUST 支持仅产出 workflow artifact 不创建 Release 的模式。预览 MUST 仅允许手动触发（`workflow_dispatch`），MUST NOT 因 push 自动构建；版本号 MUST 为 `v${VERSION_NAME}-pre.${N}`（N 为自最近一个正式 tag 起的提交数，上限 998，保证预览排序低于对应正式版），HEAD 恰为正式 tag 时 MUST 跳过预览。预览产物 MUST 仅以 workflow artifact 形式提供，MUST NOT 创建 GitHub Release 或 pre-release。预览产物 MUST 包含各 ABI 的 APK 与 `SHA256SUMS`，MUST NOT 生成应用内更新清单 `throne-update.json`（该清单仅由 stable 通道生成）。

#### Scenario: 手动发布正式版本

- **WHEN** 维护者手动运行 Release 工作流且允许发布
- **THEN** 工作流构建签名 APK 并创建 `v${VERSION_NAME}` 的 GitHub Release 并附加产物

#### Scenario: 版本号未提升时拒绝发布

- **WHEN** 目标 `v${VERSION_NAME}` tag 已存在于远端
- **THEN** 工作流失败并提示提升 `VERSION_NAME`，不覆盖旧 Release

#### Scenario: 手动触发预览构建

- **WHEN** 维护者在 GitHub Actions 页面手动运行 Preview 工作流且 HEAD 不是正式 tag
- **THEN** 工作流按提交数生成 `v${VERSION_NAME}-pre.${N}` 预览版本并上传 workflow artifact
- **AND** 未创建任何 GitHub Release 或 pre-release

#### Scenario: 主分支推送触发预览

- **WHEN** 提交推送到主分支
- **THEN** Preview 工作流不被触发（预览仅允许手动触发，push 不再自动构建）

#### Scenario: HEAD 为正式 tag 时跳过预览

- **WHEN** 手动运行 Preview 工作流且 HEAD 恰为最近的正式 tag
- **THEN** 工作流跳过构建与上传，不产出预览 artifact

#### Scenario: 预览产物内容

- **WHEN** 预览构建完成并整理产物
- **THEN** 产物目录仅包含各 ABI 的 APK 与 `SHA256SUMS`
- **AND** 不包含 `throne-update.json`