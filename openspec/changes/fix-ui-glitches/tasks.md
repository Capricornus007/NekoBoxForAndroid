# Tasks

## 1. 空状态提示块屏幕居中

- [x] 1.1 修改 `app/src/main/res/layout/layout_profile_list.xml`：`profiles_empty` 内层 `LinearLayout` 的 `android:gravity` 改为 `center`（水平+垂直），删除无效的 `android:layout_gravity="center"`，不对称 padding（top 24dp / bottom 104dp）改为对称（上下 24dp 量级）；保留 `fillViewport="true"` 与 `wrap_content` 以维持超高内容可滚动。验证：diff 仅触及该 LinearLayout 属性，`ScrollView` 结构与 id 不变
- [x] 1.2 对照 `openspec/changes/fix-ui-glitches/specs/ui-layout/spec.md` 的"空状态提示块屏幕居中"requirement 复核实现覆盖三个场景（空分组居中、空订阅含按钮居中、超高内容可滚动），并运行 `openspec validate fix-ui-glitches` 确认 delta 格式通过；本地仅做此静态校验（不跑 Android 编译，构建交 CI）
- [x] 1.3 提交本批次改动（由用户执行 git 提交，批次独立可回退）
- [x] 1.4 CI/真机验证阶段（结果回传前不开始批次 2）：CI 走 `.github/workflows/ci.yml` 的 `build` job（`app:testOssDebugUnitTest app:assembleOssDebug`）；真机场景：打开空分组首页、打开空订阅分组首页，各截整屏；将字体缩放调至最大后再次查看并上下滑动提示内容。预期：提示块（含"更新订阅"按钮）视觉重心在屏幕中心、不贴上部，超高内容可滚动。需回传证据：两张整屏截图（含空订阅态）+ 大字体下滑动后的截图或说明

## 2. 配置切换栏高度与字号调整

- [x] 2.1 在 `app/src/main/res/values/themes.xml` 新增 `TextAppearance.SagerNet.Tab`（parent `TextAppearance.SagerNet.Button`，`android:textSize="16sp"`），不改动 `TextAppearance.SagerNet.Button` 与 `Widget.SagerNet.TabLayout`；在 `app/src/main/res/layout/layout_group_list.xml` 的 `group_tab` 上设 `android:layout_height="40dp"` 并以 `app:tabTextAppearance` 引用新样式。验证：diff 确认按钮/对话框共用样式无变化，`group_tab` 之外的样式与布局无改动
- [x] 2.2 对照 spec 的"配置切换栏尺寸"requirement 复核两个场景（标签栏约 40dp 高/16sp 字；其他控件字尺寸不变），并运行 `openspec validate fix-ui-glitches` 通过
- [x] 2.3 提交本批次改动（由用户执行 git 提交，批次独立可回退）
- [x] 2.4 CI/真机验证阶段（结果回传前不开始批次 3）：CI 同 `ci.yml` `build` job；真机场景：存在至少两个分组时打开首页截整屏，进入任一对话框/设置页截按钮文字。预期：分组标签栏明显更矮、标签文字明显更大（约 40dp/16sp 观感），按钮与对话框文字尺寸与之前一致。需回传证据：首页整屏截图 + 对话框截图；若"稍微"的量级观感不符，回传建议数值后在本批次内微调重验

## 3. legacy 界面状态栏空间固定（规避 material #3404）

- [x] 3.1 修改 `app/src/main/res/layout/layout_apps.xml`、`layout_app_list.xml`、`layout_rule_set_picker.xml`：root `CoordinatorLayout`、`AppBarLayout`、`CollapsingToolbarLayout` 去掉 `fitsSystemWindows`，`AppBarLayout` 去掉 `app:statusBarForeground`，header 去掉硬编码 `paddingTop="56dp"`；状态栏空间由 `ThemedActivity.onContentChanged` 自动安装的 `applyTopInset()` 提供。验证：三布局 diff 结构一致、`@id/appbar` 保留
- [x] 3.2 同步调整 `AppManagerActivity.kt`、`AppListActivity.kt`、`RuleSetPickerActivity.kt`：移除 toolbar/header 的 `applyInsetPadding(horizontal = true)`（AppBarLayout 已含 horizontal padding），`binding.list.applyListInsets(ime = true, horizontal = false)` 改 `horizontal = true`，更新原 "the app bar fits system windows (status bar foreground)" 注释。验证：diff 确认三处代码模式一致、无其他 inset 调用残留双份
- [x] 3.3 对照 spec 的"legacy 界面状态栏空间固定"requirement 复核三个场景（fling 顶部不动、其他 legacy 界面同样固定、折叠头部滚动正常），并运行 `openspec validate fix-ui-glitches` 通过
- [ ] 3.4 提交本批次改动（由用户执行 git 提交，批次独立可回退）
- [ ] 3.5 CI/真机验证阶段：CI 同 `ci.yml` `build` job；真机场景：分应用代理界面用力上滑/下滑（fling）各一次并录屏或连拍、缓慢滚动折叠/展开头部各一次、横屏重复 fling 一次；应用列表选择（路由内选应用）与规则集选择界面各 fling 一次。预期：顶部状态栏区域全程主题色填充，无白色/窗口背景露出、头部不随 fling 下移，折叠/展开正常，横屏侧边无内容被导航栏遮挡。需回传证据：分应用代理 fling 录屏（或连拍截图）+ 另两个界面的 fling 截图；若折叠几何与迁移前有可见差异或折叠异常，回传录屏并在本批次内按 design 的回退方案调整重验

## 4. 收尾核对

- [ ] 4.1 汇总三个批次的 CI/真机证据，逐条对照 `specs/ui-layout/spec.md` 全部 requirement 场景确认达成；运行 `openspec validate fix-ui-glitches --strict` 做最终静态校验。验证：证据清单与 spec 场景一一对应、校验输出通过；本任务不新增代码改动（CI/真机验证不适用：无独立行为变化，证据已在 1.4/2.4/3.5 回传）
