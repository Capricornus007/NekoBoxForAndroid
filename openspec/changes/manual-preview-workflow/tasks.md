# Tasks

## 1. 预览产物整理：preview 通道不再生成更新清单

- [x] 1.1 修改 `buildScript/release_assets.py`：`throne-update.json` 仅在 `channel == "stable"` 时生成，`preview` 通道 dist 只含各 ABI APK 与 `SHA256SUMS`；其余校验（签名、ABI、版本、包名一致性）保持不变，验证方式：通读 diff 确认 stable 分支行为零变化、preview 分支跳过清单写入
- [x] 1.2 本地静态校验：按 governance 规范走 uv 文件工作流——将语法检查逻辑写入 `tools/diagnostics/` 脚本，从仓库根目录 `uv run tools/diagnostics/<script>.py buildScript/release_assets.py` 执行，验证语法无误（禁止 `python`/`python3` 直跑）
- [x] 1.3 提交该批次（`buildScript/release_assets.py` + 配套 `tools/diagnostics/py_syntax_check.py`），验证方式：`git show --stat` 确认只含这两个文件

## 2. Preview 工作流改造（触发、产物、包名）

- [x] 2.1 修改 `.github/workflows/preview.yml`：`on` 仅保留 `workflow_dispatch`（删除 `push` 触发与 `publish` 输入），删除 `permissions: contents: write` 与 `Publish pre-release` 步骤，`Upload artifact` 条件简化为 `steps.version.outputs.skip != 'true'`，保留 `Build number` 版本计算、`concurrency` 与 14 天 artifact 留存；验证方式：diff 确认无 `push`/`gh release create`/`publish` 残留
- [x] 2.2 在 `Build` 步骤前新增一步 `sed -i 's/^PACKAGE_NAME=.*/PACKAGE_NAME=com.nb4a.throne.debug/' nb4a.properties`（与 v1.x 一致，仅作用于工作流检出），验证方式：diff 确认 sed 位于 Gradle 构建之前且只改 `PACKAGE_NAME` 行
- [x] 2.3 同步规范：将 `openspec/changes/manual-preview-workflow/specs/repository-governance/spec.md` 的 delta 与实现对照复核（触发方式、artifact-only、`.debug` 包名、无 `throne-update.json` 四点均有对应实现），验证方式：`openspec validate manual-preview-workflow` 通过
- [ ] 2.4 提交该批次（`preview.yml` + 规范同步），验证方式：`git show --stat` 确认改动范围

## 3. CI 验证（手动触发 Preview 工作流）

- [ ] 3.1 推送后在 GitHub Actions 页面手动运行 Preview 工作流，验证方式：workflow 成功完成，回传证据：run 页面截图或链接
- [ ] 3.2 检查 artifact 内容，验证方式：下载 artifact 确认含 3 个 ABI 的 APK + `SHA256SUMS`、无 `throne-update.json`，回传证据：artifact 文件列表
- [ ] 3.3 检查 Release 列表与包名，验证方式：确认没有新建任何 Release/pre-release，且 APK `aapt2 dump badging` 显示包名 `com.nb4a.throne.debug`、versionName 为 `2.0.0-pre.N` 形式，回传证据：badging 输出片段
- [ ] 3.4 回归确认：push 一个提交到 main 后确认 Preview 工作流未被自动触发，且 `release.yml`/`ci.yml` 未受影响，回传证据：Actions 历史中无 push 触发的 Preview run