# Proposal

## Why

merge dev-converge 后，dev 侧新增的两条可翻译字符串 `local_dns_failed` 与 `local_dns_failed_system` 只存在于基准 `app/src/main/res/values/strings.xml`，19 个语言目录全部缺失。`uv run tools/diagnostics/i18n_coverage.py` 当前报 38 项 missing 并以非零退出，违反 `openspec/specs/i18n/spec.md` 的条目对齐要求——刚由 `complete-i18n-coverage` 建立的全翻译覆盖状态在合并后回退，需要立即补齐。

## What Changes

- 为全部 19 个语言目录（`values-ar`、`values-be`、`values-de`、`values-es`、`values-fa`、`values-fr`、`values-in`、`values-it`、`values-ja`、`values-ko`、`values-nb-rNO`、`values-nl`、`values-pt-rBR`、`values-ru`、`values-tr`、`values-uk`、`values-zh-rCN`、`values-zh-rHK`、`values-zh-rTW`）的 `strings.xml` 各补齐 `local_dns_failed` 与 `local_dns_failed_system` 两条翻译。
- 翻译保持与基准相同的格式占位符（`local_dns_failed` 含 `%1$s`，`local_dns_failed_system` 无占位符）与 XML 转义。
- 不修改基准资源的文案内容，不新增或删除任何语言目录。
- 验收：`uv run tools/diagnostics/i18n_coverage.py` 输出 0 issue 并以 0 退出。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

（无——i18n 能力规范的条目对齐、占位符契约与诊断脚本要求已完整覆盖本场景；本次是让实现重新符合现有 `openspec/specs/i18n/spec.md`，规范文本不变，故 `.openspec.yaml` 设置 `skip_specs: true`。）

## Impact

- 受影响能力规范：`openspec/specs/i18n/spec.md`（仅恢复其第 2 条 requirement 的实现符合性，规范文本不修改）。
- 涉及范围：Android 资源层（`app/src/main/res/values-*/strings.xml`）与本地静态校验（`tools/diagnostics/i18n_coverage.py`，只读脚本，不修改）。
- **不涉及** throne 核心、构建链（Gradle/CI workflow）与开发工具布局（`.roo/`、`tools/` 目录结构不变）。
- 无外部 API 依赖，不引入任何新代码路径或运行时行为变化；仅翻译文本落地，风险面为各语言文案质量。