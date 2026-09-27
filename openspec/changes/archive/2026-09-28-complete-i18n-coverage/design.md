# Design

## Context

基准资源为 `values/strings.xml`（1512 条 `<string>`，其中 24 条 `translatable="false"`；25 条 `<plurals>`）与 `values/arrays.xml`（100 个 `<string-array>`，其中 51 个不可翻译）。现有 19 个 `values-*` 语言目录覆盖率在 28～366 条之间，且均无 `arrays.xml`。代码侧已全面使用 `getString(R.string.*)`，布局无待提取的硬编码文案，因此本变更纯属资源层补齐，不涉及代码改动。约束见 proposal.md 与项目 context：本地不编译 Android，静态校验靠仓库脚本，构建与 lint 交给 GitHub Actions。

## Goals / Non-Goals

**Goals:**

- 19 个现有语言目录与基准条目集合 100% 对齐（`<string>`、`<plurals>`、`<string-array>`）。
- 占位符、复数分类、XML 良构等格式契约可被脚本静态验证。
- 提供 `tools/diagnostics/i18n_coverage.py` 作为长期可复用的覆盖率门禁。

**Non-Goals:**

- 不新增语言、不删除语言、不改基准文案。
- 不引入外部翻译平台（Weblate/Crowdin）或第三方翻译 API。
- 不改动 Kotlin/Java 代码、布局、构建配置与 CI workflow。

## Decisions

1. **翻译产出方式：仓库内 AI 直接生成，按语言分批写入。**
   备选：接入外部翻译服务 API / 人工翻译。外部 API 违背「不引入未调研依赖」的约束且需密钥管理；人工翻译成本不可控。AI 直接改写各 `values-*` XML 文件最短路径、可逐批审查。质量风险由「占位符/结构脚本校验 + 每批 CI 构建」缓解。

2. **批次划分：按语言分批，每批 3～4 个语言 + 当批校验与 CI 验证。**
   备选：按资源类型（先 strings 后 arrays）或一次性全量提交。按语言分批使每批改动自洽、可独立回退，且单批 diff 体量可控；每批跑覆盖率脚本并触发预览构建，符合项目「最小自洽批次 + 随即 CI 验证」的滚动流程。`arrays.xml` 与同批语言的 `strings.xml` 一起交付，避免半成品状态。

3. **诊断脚本用 Python 标准库（`xml.etree.ElementTree` + `re`）实现，放 `tools/diagnostics/`，经 `uv run` 执行。**
   备选：Kotlin/Gradle 任务或 shell 脚本。Gradle 任务本地无法运行（不装 Android SDK）；Python 标准库即可完成 XML 解析与 `%(\d+\$)?[sdf]` 类占位符提取，且符合 `repository-governance` 对维护脚本位置与执行方式的既有要求。脚本只读，不做自动修复，避免误改译文。

4. **占位符比对采用「提取后多重集比较」而非字符串相等。**
   译文语序允许调整（如阿拉伯语、俄语语序差异），只要求占位符集合一致；`%1$s` 与 `%s` 视为不同类型以捕捉 Android 运行时格式化风险。

5. **复数分类按 CLDR 语言规则校验。**
   脚本内置各语言所需 quantity 集合（如 ru: one/few/many/other；ar: zero/one/two/few/many/other；ja/zh: other），缺失分类即报错。备选是交给 aapt/CI lint 判定——保留为二道防线，但本地脚本先失败以缩短反馈回路。

6. **既有译文默认保留，仅在脚本判定其占位符与基准不匹配时才重写该条。**
   避免无差别重译破坏已有社区译文；不匹配条目视为历史缺陷，随所属批次修复。

## Risks / Trade-offs

- [AI 翻译质量参差（术语不统一、机翻腔）] → 术语一致性靠基准英文上下文 + 同批语言内统一关键术语（proxy/subscription/rule set 等）；后续可人工抽查修订，条目级 diff 易审。
- [单批 diff 巨大（每语言约 1400+ 条）] → 按语言分批、每批独立提交，审查时以脚本输出的覆盖率数字为主要验收信号，不逐行人工审。
- [占位符/转义错误导致运行时崩溃或 lint 失败] → 脚本强制占位符多重集一致 + XML 解析校验；CI 预览构建的 `processDebugResources`/lint 作为二道防线。
- [基准后续演进再次产生缺口] → 覆盖率脚本长期保留，可随时运行；本变更不接入 CI 强制门禁（避免影响既有 workflow），是否默认在 CI 运行留作后续变更决定。
- [19 个语言中含 AI 较弱语种（be、nb、uk 等）] → 仍按完整覆盖交付；质量风险记录在案，允许后续以独立小变更修订单语种译文。

## Migration Plan

纯资源新增/扩充，无数据或行为迁移。回退策略：任一批次出问题直接 revert 该批提交即可，语言目录回退到上一状态不影响其他语言与基准。全部批次完成后运行 `uv run tools/diagnostics/i18n_coverage.py` 与 CI 预览构建作为整体验收。

## Open Questions

- 覆盖率脚本是否应默认接入 CI 作为强制门禁（本变更先仅作为本地/审查工具）——不影响本变更的规格与任务拆分，留待后续变更决定。