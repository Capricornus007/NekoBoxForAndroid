# Proposal

## Why

应用基准语言 `values/strings.xml` 共有 1512 条 `<string>` 与 25 条 `<plurals>`，但现有 19 个语言目录的翻译覆盖率极低（it 28 条、nl 32 条、de 46 条，最高的 ru 也仅 366 条），多数语言用户看到的是大段英文回退，多语言体验不完整。本变更一次性补齐所有现有语言的缺失翻译，使各语言资源与基准对齐。

## What Changes

- 为 `app/src/main/res/values-*/strings.xml` 中每个现有语言目录补齐基准里全部可翻译条目（`<string>` 与 `<plurals>`），排除 `translatable="false"` 的 24 条。
- 为每个现有语言目录新增 `values-*/arrays.xml`，翻译基准 `values/arrays.xml` 中 49 个可翻译 `<string-array>`（100 个中 51 个标记 `translatable="false"`）。
- 翻译由 AI 在仓库内直接产出并写入各语言文件，保持既有 XML 结构、占位符（`%1$s`、`%d` 等）与 `plurals` 复数分类不变。
- 新增仓库维护用覆盖率诊断脚本 `tools/diagnostics/i18n_coverage.py`（经 `uv run` 执行），用于核对各语言相对基准的缺失/多余条目与占位符一致性。
- 不新增语言目录、不修改基准 `values/strings.xml` 与 `values/arrays.xml` 的文案内容、不改动任何 Kotlin/Java 代码或布局中的硬编码文本（现存唯一硬编码为装饰性 `▼`，不可翻译）。

## Capabilities

### New Capabilities

- `i18n`: 定义应用字符串资源的国际化要求——基准资源完整性、各现有语言目录的翻译覆盖率、占位符与复数形式一致性、以及覆盖率诊断校验方式。

### Modified Capabilities

（无。`repository-governance` 已允许 `tools/diagnostics/` 承载维护脚本，无需修改其需求。）

## Impact

- **Android 资源**：`app/src/main/res/values-*/strings.xml` 共 19 个语言文件被大幅扩充（合计约 2.6 万条新增翻译条目），并新增 19 个 `values-*/arrays.xml`（各含 49 个可翻译数组）；基准 `values/strings.xml`、`values/arrays.xml` 不变。
- **开发工具布局**：新增 `tools/diagnostics/i18n_coverage.py`（首次创建 `tools/` 目录），符合 `repository-governance` 对维护脚本的位置与执行方式约束。
- **不涉及**：throne 核心、`nb4a.properties`、构建链（`buildScript/`、`buildSrc/`、Gradle 配置）、`.github/` 工作流、数据库 schema。
- **验证**：本地仅做静态校验（XML 良构、覆盖率脚本）；APK 构建与 lint 由 GitHub Actions 预览构建验证，真机仅抽查语言切换显示。
- **假设**：用户表述为「20 个语言」，实际清点为 19 个含 `strings.xml` 的 `values-*` 目录；范围以「所有现有语言目录」为准，如后续发现遗漏目录按同一规则补齐。