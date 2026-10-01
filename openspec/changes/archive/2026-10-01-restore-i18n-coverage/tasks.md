# Tasks

## 1. 补齐 19 语言翻译（实现批次）

- [x] 1.1 为 19 个语言目录（values-ar/be/de/es/fa/fr/in/it/ja/ko/nb-rNO/nl/pt-rBR/ru/tr/uk/zh-rCN/zh-rHK/zh-rTW）的 `strings.xml` 在 `service_failed` 之后各插入 `local_dns_failed` 与 `local_dns_failed_system` 两条翻译：参照各语言既有网络错误文案的术语与语气，`local_dns_failed` 保留 `%1$s` 占位符，撇号按 Android 规则转义（`\'` 或双引号包裹），不修改任何既有条目
- [x] 1.2 运行 `uv run tools/diagnostics/i18n_coverage.py`，确认输出 `0 issue(s)` 且退出码为 0（本地静态校验，覆盖缺失/多余/占位符/XML 良构）
- [x] 1.3 提交本批次（单 commit，说明为 merge 后新增的 2 条 DNS 错误字符串补齐翻译、恢复 i18n 全覆盖）

## 2. 批次 1 的 CI/真机验证阶段

- [x] 2.1 CI 验证：push 后确认 `CI` workflow（.github/workflows/ci.yml）的 `build` job（Unit tests and debug build）通过——AAPT 资源编译成功即证明 38 条新翻译 XML 良构、占位符与转义合法；回传该 job 的运行链接与结论，失败则仅回退对应语言条目修复后重跑
- [x] 2.2 真机验证：将系统语言切换为 zh-rCN，把 Direct DNS 设置为不可达地址后启动代理触发连接失败，观察首页 snackbar 显示中文文案"网络的 DNS (%1$s) 未应答…"且参数被实际地址正确替换；再抽查 zh-rTW 与 ja 的文案显示；回传截图。其余 16 个语言的显示效果依赖对应母语能力，不逐语言真机抽查，由 1.2 静态校验与 2.1 CI 构建兜底