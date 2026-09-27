# I18n Specification

## Purpose

定义 ThroneForAndroid 应用字符串资源的国际化契约：基准资源是翻译的唯一来源，所有现有语言目录必须与基准保持条目对齐、占位符与复数形式一致，并可通过仓库维护脚本静态校验覆盖率。

## Requirements

### Requirement: 基准资源作为翻译唯一来源
基准资源 `app/src/main/res/values/strings.xml` 与 `app/src/main/res/values/arrays.xml` MUST 作为全部翻译的唯一来源；标记 `translatable="false"` 的条目（当前为 24 条 `<string>` 与 51 个 `<string-array>`）MUST NOT 出现在任何语言目录中。判定 MUST 按条目类型分别对照：语言目录 `strings.xml` 中的 `<string>`/`<plurals>` 仅与基准 `strings.xml` 的同类型条目比对，语言目录 `arrays.xml` 中的 `<string-array>` 仅与基准 `arrays.xml` 的同类型条目比对（基准中存在 `<string>` 与 `<string-array>` 同名但可翻译性不同的条目，如 `log_level`）。本变更 MUST NOT 修改基准资源的文案内容，也 MUST NOT 新增或删除任何现有语言目录（`values-*`）。

#### Scenario: 基准与语言集合保持稳定
- **WHEN** 本变更完成后比对基准 `values/strings.xml`、`values/arrays.xml` 的内容与变更前一致，且 `values-*` 语言目录集合与变更前一致
- **THEN** 基准文案与语言集合未被改动，符合要求

#### Scenario: 不可翻译条目不泄漏到语言目录
- **WHEN** 检查任一 `values-*/strings.xml` 或 `values-*/arrays.xml` 中的条目，按其类型与基准 `strings.xml`（`<string>`/`<plurals>`）或基准 `arrays.xml`（`<string-array>`）中的同类型条目比对
- **THEN** 语言目录的 `<string>`/`<plurals>` 不包含基准 `strings.xml` 中标记 `translatable="false"` 的名字，语言目录的 `<string-array>` 不包含基准 `arrays.xml` 中标记 `translatable="false"` 的名字

### Requirement: 语言目录条目完整覆盖基准
每个含 `strings.xml` 的现有语言目录 MUST 包含基准中全部可翻译 `<string>` 与 `<plurals>` 条目，且 MUST 为基准中全部可翻译 `<string-array>` 提供同名数组（存放于该语言目录的 `arrays.xml`）。语言目录 MUST NOT 包含基准中不存在的条目名，也 MUST NOT 出现同名重复条目。

#### Scenario: 补齐后覆盖率达标
- **WHEN** 对任一现有语言目录统计其 `<string>`、`<plurals>`、`<string-array>` 名称集合
- **THEN** 该集合与基准可翻译条目名称集合完全一致，缺失与多余条目数均为 0

#### Scenario: 既有翻译被保留
- **WHEN** 比对变更前后某语言目录中原本已存在的条目
- **THEN** 原有条目名称仍然存在，且其译文未被无故改写

### Requirement: 翻译保持格式契约
每条翻译 MUST 与基准条目保持相同数量与语义的格式占位符（如 `%1$s`、`%d`、`%1$d`），MUST 保持 XML 良构（正确转义 `&`、`<`、`>`、引号与撇号），MUST 保持 `<string>`/`<string-array>` 的 `name` 属性及 `<item>` 结构不变。`<plurals>` 的翻译 MUST 覆盖该语言复数规则所需的全部 quantity 分类（如俄语、阿拉伯语等多分类语言），且每个分类的译文同样遵守占位符契约。

#### Scenario: 占位符一致
- **WHEN** 用诊断脚本比对某语言条目与基准条目的格式占位符序列
- **THEN** 两者占位符数量与类型完全一致，无不匹配报告

#### Scenario: 复数分类完整
- **WHEN** 检查俄语（`values-ru`）中任一 `<plurals>` 条目
- **THEN** 该条目包含俄语复数规则要求的 `one`、`few`、`many`、`other` 分类

#### Scenario: XML 良构
- **WHEN** 对全部 `values-*` 下的 `strings.xml` 与 `arrays.xml` 执行 XML 解析
- **THEN** 所有文件解析成功，无格式错误

### Requirement: 覆盖率诊断脚本可静态校验
仓库 MUST 提供维护用诊断脚本 `tools/diagnostics/i18n_coverage.py`，从仓库根目录经 `uv run tools/diagnostics/i18n_coverage.py` 执行。脚本 MUST 输出每个语言目录相对基准的缺失条目、多余条目与占位符不匹配项；当存在任一缺失、多余或占位符不匹配时 MUST 以非零退出码结束，全部通过时 MUST 以 0 退出。脚本 MUST 只读取资源文件，不修改任何文件。

#### Scenario: 覆盖率全部达标时通过
- **GIVEN** 所有语言目录已补齐且占位符一致
- **WHEN** 从仓库根目录运行 `uv run tools/diagnostics/i18n_coverage.py`
- **THEN** 脚本输出各语言 0 缺失、0 多余、0 占位符不匹配，退出码为 0

#### Scenario: 存在缺失时失败
- **GIVEN** 某语言目录缺少一条基准可翻译字符串
- **WHEN** 运行 `uv run tools/diagnostics/i18n_coverage.py`
- **THEN** 脚本列出该缺失条目与所属语言，且退出码非 0
