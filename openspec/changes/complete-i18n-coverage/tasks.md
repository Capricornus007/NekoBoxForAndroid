# Tasks

## 1. 批次 1：覆盖率诊断脚本

- [x] 1.1 创建 `tools/diagnostics/i18n_coverage.py`：解析基准 `values/strings.xml`、`values/arrays.xml` 与各 `values-*` 目录，输出缺失/多余条目、占位符多重集不匹配、`plurals` quantity 分类缺失（按 CLDR 语言规则），存在任一问题时退出码非 0；验证方式为从仓库根目录 `uv run tools/diagnostics/i18n_coverage.py` 能正常运行并列出当前已知缺口（如 it 缺约 1480 条），脚本自身无语法/运行错误
- [x] 1.2 验证脚本只读与门禁语义：对当前仓库运行退出码非 0 且输出包含各语言缺失统计；用临时副本制造一个占位符不匹配样例确认能被检出后丢弃副本（不修改仓库文件）
- [x] 1.3 提交脚本（仅 `tools/diagnostics/i18n_coverage.py`），确认 diff 不含任何 `app/` 资源改动
- [x] 1.4 CI/真机验证：本批次不适用真机验证，原因为未改动任何 Android 资源或代码；CI 侧确认推送后 `preview.yml` 预览构建仍成功（预期：构建通过，与基线无差异），回传证据为该 workflow 运行链接与成功状态

## 2. 批次 2：中文三地补齐（zh-rCN / zh-rTW / zh-rHK）

- [x] 2.1 补齐 `values-zh-rCN`、`values-zh-rTW`、`values-zh-rHK` 的全部缺失 `<string>` 与 `<plurals>`（保留既有译文，仅补缺与修复占位符不匹配项），并为三地新建 `arrays.xml` 翻译 49 个可翻译数组；验证方式为 `uv run tools/diagnostics/i18n_coverage.py` 中三地缺失/多余/占位符不匹配均为 0
- [x] 2.2 本地静态校验：对三地全部 `strings.xml`、`arrays.xml` 执行 XML 良构解析（由诊断脚本内置完成），确认无解析错误、无 `translatable="false"` 条目泄漏、无重复 `name`
- [x] 2.3 提交本批次（6 个文件：3 个 `strings.xml` 修改 + 3 个 `arrays.xml` 新增；另按用户批准包含诊断脚本 `i18n_coverage.py` 按条目类型判定的缺陷修复与 delta spec 对应措辞修正），确认 diff 不含基准与代码改动
- [x] 2.4 CI/真机验证：推送触发 `preview.yml` 预览构建，预期 `processDebugResources` 与 lint 通过；真机场景为系统语言分别切换简体中文、繁体中文（台湾/香港），抽查设置页、配置列表、测试面板文案无英文回退；回传证据为 workflow 成功链接 + 三地界面截图

## 3. 批次 3：日韩俄乌补齐（ja / ko / ru / uk）

- [x] 3.1 补齐 `values-ja`、`values-ko`、`values-ru`、`values-uk` 的全部缺失 `<string>`、`<plurals>`（ru/uk 复数须覆盖 one/few/many/other 分类）并新建四地 `arrays.xml`；验证方式为诊断脚本中四地全部指标为 0
- [x] 3.2 本地静态校验：诊断脚本确认 XML 良构、占位符一致、无不可翻译条目泄漏
- [x] 3.3 提交本批次（8 个文件），确认 diff 范围仅限四地资源
- [x] 3.4 CI/真机验证：推送触发 `preview.yml` 预览构建成功；真机场景为切换日语、韩语、俄语、乌克兰语抽查主界面与订阅页，俄语复数（如「已删除 N 个配置」）显示正确分类；回传证据为 workflow 链接 + 截图

## 4. 批次 4：阿拉伯语/波斯语/白俄罗斯语补齐（ar / fa / be）

- [x] 4.1 补齐 `values-ar`、`values-fa`、`values-be` 的全部缺失条目并新建三地 `arrays.xml`；ar 复数须覆盖 zero/one/two/few/many/other 分类，RTL 文案保持占位符顺序语义正确；验证方式为诊断脚本中三地全部指标为 0
- [x] 4.2 本地静态校验：诊断脚本确认 XML 良构、占位符一致、复数分类完整
- [x] 4.3 提交本批次（6 个文件），确认 diff 范围仅限三地资源
- [ ] 4.4 CI/真机验证：推送触发 `preview.yml` 预览构建成功；真机场景为切换阿拉伯语（RTL 布局）与波斯语抽查界面方向与文案，白俄罗斯语抽查列表页；回传证据为 workflow 链接 + RTL 界面截图

## 5. 批次 5：主要欧洲语言补齐（de / es / fr / it / nl / pt-rBR）

- [ ] 5.1 补齐 `values-de`、`values-es`、`values-fr`、`values-it`、`values-nl`、`values-pt-rBR` 的全部缺失条目（it/nl/pt-rBR 缺口最大，约 1400+ 条/语言）并新建六地 `arrays.xml`；验证方式为诊断脚本中六地全部指标为 0
- [ ] 5.2 本地静态校验：诊断脚本确认 XML 良构、占位符一致、无不可翻译条目泄漏
- [ ] 5.3 提交本批次（12 个文件），确认 diff 范围仅限六地资源
- [ ] 5.4 CI/真机验证：推送触发 `preview.yml` 预览构建成功；真机场景为切换德语、法语、西班牙语抽查设置页与路由页文案完整；回传证据为 workflow 链接 + 截图

## 6. 批次 6：印尼语/挪威语/土耳其语补齐（in / nb-rNO / tr）

- [ ] 6.1 补齐 `values-in`、`values-nb-rNO`、`values-tr` 的全部缺失条目并新建三地 `arrays.xml`；验证方式为诊断脚本中三地全部指标为 0
- [ ] 6.2 本地静态校验：诊断脚本确认 XML 良构、占位符一致、无不可翻译条目泄漏
- [ ] 6.3 提交本批次（6 个文件），确认 diff 范围仅限三地资源
- [ ] 6.4 CI/真机验证：推送触发 `preview.yml` 预览构建成功；真机场景为切换印尼语、土耳其语抽查主界面文案，挪威语（nb-rNO）抽查设置页；回传证据为 workflow 链接 + 截图

## 7. 批次 7：整体验收与规范同步

- [ ] 7.1 运行 `uv run tools/diagnostics/i18n_coverage.py`，预期 19 个语言全部 0 缺失、0 多余、0 占位符不匹配、0 复数分类缺失，退出码 0；同时确认基准 `values/strings.xml`、`values/arrays.xml` 与变更前一致、语言目录集合未增减
- [ ] 7.2 核对 delta spec `openspec/changes/complete-i18n-coverage/specs/i18n/spec.md` 各场景与实现一致（覆盖率、占位符、复数、脚本退出码语义），如有偏差修正实现或 spec 使其对齐
- [ ] 7.3 CI/真机验证：最后一次推送触发 `preview.yml` 预览构建成功；真机场景为遍历全部 19 个语言各抽查一个主界面确认无英文回退与乱码；回传证据为 workflow 链接 + 各语言抽查记录，归档时按 archive 指引将 i18n 主规范同步至 `openspec/specs/i18n/spec.md`