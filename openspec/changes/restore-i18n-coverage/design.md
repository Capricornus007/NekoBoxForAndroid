# Design

## Context

merge dev-converge 后基准 `app/src/main/res/values/strings.xml` 新增了两条可翻译字符串（`local_dns_failed` 含 `%1$s` 占位符、`local_dns_failed_system` 无占位符），19 个语言目录尚未跟进，`uv run tools/diagnostics/i18n_coverage.py` 报 38 项 missing。动机见 proposal.md - Why。约束：基准文案不再改动；不增删语言目录；诊断脚本只读；本地不执行 Android 编译，构建验证交给 GitHub Actions。

## Goals / Non-Goals

**Goals:**

- 19 个语言目录的 `strings.xml` 各补齐 2 条翻译，使 `i18n_coverage.py` 输出 0 issue、退出码 0。
- 翻译满足 i18n 规范的占位符与 XML 良构契约。

**Non-Goals:**

- 不修改基准文案、不新增/删除语言目录、不调整诊断脚本本身。
- 不翻译 `translatable="false"` 条目，不触碰 `arrays.xml`。
- 不引入新的翻译管理工具或外部翻译服务。

## Decisions

1. **翻译产出方式：逐语言人工撰写，参照各语言目录内既有的网络/DNS 错误类文案风格。**
   备选：接入机器翻译脚本——否决，会新增维护工具且超出本 change 范围（工具布局约束：新脚本只能进 `tools/diagnostics/`，而一次性翻译不值得一个脚本）。
2. **占位符与转义：`local_dns_failed` 的 `%1$s` 在所有语言中原样保留（位置可随语序调整为 `%1$s` 的语言学等价位置，但类型与数量必须一致）；撇号按 Android 资源规则处理——基准用 `\'`，各语言译文中的撇号同样转义或以双引号包裹整个值。**
   备选：不转义——否决，AAPT 会把未转义撇号视为字符串终止符，导致构建失败。
3. **插入位置：各语言文件中放在与基准相同的相对位置（`service_failed` 之后、`stop` 之前），保持文件可读性与后续 diff 友好。**
   备选：追加到文件末尾——否决，脚本按 `name` 比对虽不受影响，但会破坏与基准的视觉对齐，增加后续维护成本。
4. **验收以 `uv run tools/diagnostics/i18n_coverage.py` 归零为唯一静态门禁，CI 构建为最终门禁。**
   备选：额外写单元测试——否决，脚本本身即规范要求的校验器，重复验证无增量反馈价值。

## Risks / Trade-offs

- [非母语翻译质量参差] → 译文参照各语言既有同类错误文案的术语与语气；真机抽查 zh-rCN/zh-rTW 等关键语言的显示效果。
- [撇号/转义疏漏导致 AAPT 构建失败] → 本地 `i18n_coverage.py` 检查 XML 良构与占位符，CI Android 构建兜底；失败时仅回退对应语言条目。
- [占位符语序在 SOV 语言中不自然] → 允许 `%1$s` 在句中移动，但脚本会校验数量与类型一致，语序问题留给真机验证确认。