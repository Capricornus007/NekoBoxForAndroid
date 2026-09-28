# Design

## Context

动机见 proposal.md - Why；行为要求见 specs/ui-layout/spec.md。此处只记录影响方案的现状事实：

- **空状态块**（`app/src/main/res/layout/layout_profile_list.xml`）：`ScrollView`（`@id/profiles_empty`，`fillViewport="true"`）内唯一子 `LinearLayout` 设了 `android:layout_gravity="center"` + `android:gravity="center_horizontal"` + 不对称 padding（top 24dp / bottom 104dp）。`ScrollView` 忽略子 view 的 `layout_gravity`；`fillViewport` 又把子高拉到视口高度，内容便顶对齐贴在上部（即用户看到的 top middle）。`ProfileListFragment` 对该 ScrollView 调 `applyListInsets()`（bottom + horizontal inset，`clipToPadding=false`）。
- **配置切换栏**（`app/src/main/res/layout/layout_group_list.xml` 的 `@id/group_tab`）：`TabLayout`，`layout_height="wrap_content"`，文字样式来自全局 `tabStyle` → [`Widget.SagerNet.TabLayout`](app/src/main/res/values/themes.xml:157)（`tabTextAppearance` = [`TextAppearance.SagerNet.Button`](app/src/main/res/values/themes.xml:150)，14sp）。`TextAppearance.SagerNet.Button` 同时被 MaterialButton、对话框按钮、snackbar 动作共用，不能整体放大。
- **legacy 界面**：`layout_apps.xml`（`AppManagerActivity` 分应用代理）、`layout_app_list.xml`（`AppListActivity`）、`layout_rule_set_picker.xml`（`RuleSetPickerActivity`）共享同一套 T4A v1.x 折叠头部模式：root `CoordinatorLayout` / `AppBarLayout` / `CollapsingToolbarLayout` 三层 `fitsSystemWindows="true"`，`AppBarLayout` 带 `app:statusBarForeground="?attr/colorPrimary"`，header 硬编码 `paddingTop="56dp"` 顶开状态栏；三个 Activity 都以注释 "the app bar fits system windows (status bar foreground); the list pads the navigation bar" 配 `binding.list.applyListInsets(ime = true, horizontal = false)` + toolbar/header 的 `applyInsetPadding(horizontal = true)`。
- **已知上游 bug**：material-components-android [#3404](https://github.com/material-components/material-components-android/issues/3404)（"Status bar foreground detaches when flinging"）——`statusBarForeground` 在 fling 时与 AppBarLayout 脱开下移，与用户截图一致；该 issue 截至 2026-05 仍 Open，本仓库 `com.google.android.material:material:1.8.0` 受影响。
- **仓库既有模式**：非折叠界面统一用 [`AppBarLayout.applyTopInset()`](app/src/main/java/io/nekohasekai/sagernet/widget/WindowInsetsListeners.kt:137)（top + horizontal inset padding，insets 不消费），由 [`ThemedActivity.onContentChanged`](app/src/main/java/io/nekohasekai/sagernet/ui/ThemedActivity.kt:66) 对 `R.id.appbar` 自动安装。三个 legacy 布局的 AppBarLayout 同为 `@id/appbar`，改掉 XML 后自动走此路径，无需 Activity 级 inset 代码。

## Goals / Non-Goals

**Goals:**
- 三个问题各有一个最小、可独立审查与回退的修复；行为契约落在 specs/ui-layout。
- 问题 3 的修复采用仓库已有 inset 模式（`applyTopInset`），让 legacy 界面与其余界面的状态栏处理同构，而不是引入新的 hack。
- 全部改动限于 Android UI 资源/布局与少量 Activity inset 调用；不动核心、构建链、工具布局。

**Non-Goals:**
- 不升级 material 库、不为 #3404 引入 fork/补丁。
- 不重构 legacy 界面的折叠头部视觉设计（布局结构、卡片、chips 保持原样）。
- 不统一全应用的 inset 处理风格（只迁移出问题的三个界面）。
- 不处理与本次三个问题无关的 UI 细节（如横屏 stats bar）。

## Decisions

1. **空状态居中：修正 `ScrollView` 子 view 的 gravity，而不是换容器。**
   把 `LinearLayout` 的 `android:gravity` 从 `center_horizontal` 改为 `center`（水平 + 垂直），删除无效的 `android:layout_gravity="center"`；`fillViewport="true"` 保留——内容不足视口时子高被拉伸、内部 `gravity="center"` 垂直居中，内容超高时 `wrap_content` 生效可滚动（满足 spec 的滚动场景）。不对称 padding（top 24dp / bottom 104dp）改为对称（上下 24dp 量级），否则居中仍被推高。
   - 备选：ConstraintLayout/FrameLayout 包裹居中——为一个纯静态提示块换容器并新增依赖层级，收益为零；`layout_gravity` 改动即根因修复。
   - 注：`applyListInsets()` 给 ScrollView 加的 bottom inset（导航栏高度）使居中区域是"屏幕减导航栏"，视觉中心高半个导航栏高度，属可接受偏差（Risks 记录）。

2. **配置切换栏：只调 `group_tab` 局部属性，不共用按钮文字样式。**
   在 `group_tab` 上设 `android:layout_height="40dp"`（48dp 默认高 → 稍矮）并新增专用文字样式 `TextAppearance.SagerNet.Tab`（parent `TextAppearance.SagerNet.Button`，`android:textSize="16sp"`）经 `app:tabTextAppearance` 引用。改动仅落在首页这一处 TabLayout。
   - 备选：直接改 `Widget.SagerNet.TabLayout` 全局样式——该样式经主题 `tabStyle` 作用于所有 TabLayout，与 spec "不影响其他标签栏"冲突；被否。
   - 备选：放大 `TextAppearance.SagerNet.Button`——按钮/对话框共用，波及面失控；被否。
   - 40dp/16sp 是"稍微小一点/稍大一点"的量化解析，真机观感可在验证阶段微调（Risks 记录）。

3. **legacy 界面：从 `fitsSystemWindows` + `statusBarForeground` 迁移到 `applyTopInset()` 模式，绕开上游 #3404。**
   三个布局各做同样四处改动：root `CoordinatorLayout`、`AppBarLayout`、`CollapsingToolbarLayout` 去掉 `fitsSystemWindows`；`AppBarLayout` 去掉 `app:statusBarForeground`；header 去掉硬编码 `paddingTop="56dp"`。状态栏空间改由 `ThemedActivity.onContentChanged` 自动安装的 `applyTopInset()` 提供（AppBarLayout top + horizontal inset padding，背景色自然填满状态栏区域，fling 时没有任何动态绘制的 foreground 可脱开）。Activity 侧同步调整：toolbar/header 的 `applyInsetPadding(horizontal = true)` 移除（AppBarLayout 已含 horizontal padding，否则双倍侧距），`binding.list.applyListInsets(ime = true, horizontal = false)` 改 `horizontal = true`（root 不再消费 horizontal inset），注释同步更新。
   - 备选：升级 material 到修复版——#3404 至今 Open，无修复版本可升；被否。
   - 备选：列表 `overScrollMode="never"`——#3404 是 fling settle 时 foreground 绘制位置问题，与 over-scroll 效果开关无关，且损失 over-scroll 反馈；被否。
   - 备选：状态栏区域自绘一个色块 View 兜底——增加 hack 层且折叠几何仍留在问题模式里；被否。
   - 代价：折叠头部的几何从"Collapse 内容含 56dp 状态栏占位 + foreground"变为"AppBarLayout padding 固定占位 + 内容在其下折叠"，折叠/展开视觉需真机确认等价（Risks 记录）。

## Risks / Trade-offs

- [空状态居中区域含 bottom inset，中心比纯屏幕中心略高（约半个导航栏高度）] → 视觉差异在数 dp 量级，真机确认可接受；若要求严格屏幕中心，可在验证阶段把 `applyListInsets()` 换成对称 inset 处理，spec 场景不变。
- [40dp / 16sp 的"稍微"量级因屏幕密度/字体缩放观感不同] → 真机截图核对，不符即微调数值；spec 以"约"表述，改动不越出行为边界。
- [legacy 布局迁移后折叠/展开几何与 56dp 硬编码时代的像素级差异] → 真机验证场景覆盖折叠/展开与横屏；若折叠异常，回退方案是恢复 `fitsSystemWindows` 但去掉 `statusBarForeground`、以 root 固定 top padding 顶替（同批次内调整，不跨批次累积）。
- [Material AppBarLayout 在 padding 模式下与 CollapsingToolbarLayout 的组合未在本仓库其他界面出现过（其他界面均为非折叠 appbar）] → 该组合是 Material 文档支持的标准用法；风险由问题 3 的真机 fling/折叠验证批次兜底。
- [三个 Activity 的 inset 调用调整若漏改会出双倍 padding 或列表贴边] → tasks 中列为同批次必改项，验证场景含横竖屏侧边/底部导航栏检查。

## Migration Plan

无数据/接口迁移。三个修复分为三个独立批次提交（见 tasks.md），每批次可单独 `git revert` 回退，互不依赖；回退时对应 spec 场景随之失去实现，需在回退说明中标注。

## Open Questions

（无。数值微调与居中精度属验证阶段的观感校准，不改变 specs、方案或任务拆分。）
