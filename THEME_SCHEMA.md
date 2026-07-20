# NekoBoxForAndroid (nb4a) 主题色系统设计与实现指南

本项目实现了一套优雅、灵活且支持动态切换的主题色系统。该系统不仅支持 22 种不同的主题配色，还完美融合了 Android 系统的深色模式（夜间模式）以及全面屏沉浸式体验（Edge-to-Edge）。

---

## 1. 整体架构与核心流程

主题色系统的整体架构遵循 **“存储偏好 -> 控件交互 -> 动态监听 -> 运行时应用 -> 资源渲染”** 的闭环流程：

```
[ 资源定义 (themes.xml / attrs.xml / colors.xml) ]
                       │
                       ▼
[ 偏好存储 (DataStore) ] <─── [ 控件交互 (ColorPickerPreference) ]
                       │
                       ▼
[ 动态监听 (SettingsPreferenceFragment) ] ───► [ 重建 Activity (recreate) ]
                       │
                       ▼
[ 运行时应用 (Theme.kt / ThemedActivity) ]
```

---

## 2. 核心组件详解

### 2.1 偏好设置存储 (DataStore)
主题和夜间模式的配置持久化存储在 SharedPreferences 中，并通过 [`DataStore.kt`](app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt) 进行封装：
- **`DataStore.appTheme`**：存储当前选择的主题 ID（整数，范围 1 ~ 22）。
  - 对应 Key：`Key.APP_THEME`（即 `"appTheme"`），定义在 [`Constants.kt`](app/src/main/java/io/nekohasekai/sagernet/Constants.kt:15)。
- **`DataStore.useSystemTheme`**：存储是否使用系统主题色（布尔值，默认 `false`）。
  - 对应 Key：`Key.USE_SYSTEM_THEME`（即 `"useSystemTheme"`），定义在 [`Constants.kt`](app/src/main/java/io/nekohasekai/sagernet/Constants.kt:16)。
- **`DataStore.nightTheme`**：存储当前选择的夜间模式设置（0: 跟随系统, 1: 开启, 2: 关闭）。
  - 对应 Key：`Key.NIGHT_THEME`（即 `"nightTheme"`），定义在 [`Constants.kt`](app/src/main/java/io/nekohasekai/sagernet/Constants.kt:16)。

### 2.2 主题色选择器 (ColorPickerPreference)
[`ColorPickerPreference.kt`](app/src/main/java/moe/matsuri/nb4a/ui/ColorPickerPreference.kt) 是一个自定义的 `Preference` 控件，配置在 [`global_preferences.xml`](app/src/main/res/xml/global_preferences.xml:11) 中：
- **交互逻辑**：
  - 在 [`onBindViewHolder`](app/src/main/java/moe/matsuri/nb4a/ui/ColorPickerPreference.kt:38) 中，动态在 Preference 右侧的 `widget_frame` 中添加一个圆形颜色预览（使用 `R.drawable.ic_baseline_fiber_manual_record_24` 并通过 `DrawableCompat.setTint` 染色）。
  - 点击该 Preference 时，触发 [`onClick`](app/src/main/java/moe/matsuri/nb4a/ui/ColorPickerPreference.kt:80)，弹出一个 `MaterialAlertDialog`。
  - 对话框内包含一个 4 列的 `GridLayout`，展示从 `R.array.material_colors`（定义在 [`colors.xml`](app/src/main/res/values/colors.xml:4)）中读取的 22 种主题色。
  - 用户点击某种颜色时，将对应的索引（从 1 开始的 `themeId`）通过 `persistInt(themeId)` 持久化，并调用 `callChangeListener(themeId)` 触发监听器。

### 2.3 主题应用逻辑 (Theme.kt)
[`Theme.kt`](app/src/main/java/io/nekohasekai/sagernet/utils/Theme.kt) 是主题系统的核心调度单例：
- **主题映射**：
  - 定义了 22 种主题色常量（如 `RED = 1`, `PINK_SSR = 2`, `BLACK = 21`, `VERDANT_MINT = 22`），以及系统 Monet 动态主题常量 `MONET = 0`。
  - [`getTheme(theme: Int)`](app/src/main/java/io/nekohasekai/sagernet/utils/Theme.kt:53) 将主题 ID 映射为对应的 Android Style 资源 ID（如 `R.style.Theme_SagerNet_Red`，或 `R.style.Theme_SagerNet_Monet`）。
  - [`getDialogTheme(theme: Int)`](app/src/main/java/io/nekohasekai/sagernet/utils/Theme.kt:81) 将主题 ID 映射为对应的 Dialog Style 资源 ID（如 `R.style.Theme_SagerNet_Dialog_Red`，或 `R.style.Theme_SagerNet_Dialog_Monet`）。
- **夜间模式调度**：
  - [`getNightMode()`](app/src/main/java/io/nekohasekai/sagernet/utils/Theme.kt:110) 将用户设置的 `nightTheme` 映射为 `AppCompatDelegate` 的夜间模式常量（如 `MODE_NIGHT_FOLLOW_SYSTEM`）。
  - [`applyNightTheme()`](app/src/main/java/io/nekohasekai/sagernet/utils/Theme.kt:134) 调用 `AppCompatDelegate.setDefaultNightMode` 应用夜间模式。
  - [`usingNightMode()`](app/src/main/java/io/nekohasekai/sagernet/utils/Theme.kt:126) 用于判断当前实际是否处于夜间模式（考虑了系统深色模式状态）。

### 2.4 Activity 基类应用 (ThemedActivity)
[`ThemedActivity.kt`](app/src/main/java/io/nekohasekai/sagernet/ui/ThemedActivity.kt) 是所有需要应用主题的 Activity 的基类：
- **生命周期注入**：
  - 在 [`onCreate`](app/src/main/java/io/nekohasekai/sagernet/ui/ThemedActivity.kt:28) 中，在调用 `super.onCreate` 之前，先调用 `Theme.apply(this)`（或 `Theme.applyDialog(this)`）和 `Theme.applyNightTheme()`，确保 Activity 在加载布局前已应用正确的主题样式。
- **系统配置监听**：
  - 重写 [`onConfigurationChanged`](app/src/main/java/io/nekohasekai/sagernet/ui/ThemedActivity.kt:66)，当系统深色模式发生切换（`uiMode` 改变）时，调用 `ActivityCompat.recreate(this)` 重建 Activity 以刷新界面。

### 2.5 动态主题切换 (SettingsPreferenceFragment)
在设置界面 [`SettingsPreferenceFragment.kt`](app/src/main/java/io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt) 中，实现了主题的即时动态刷新：
- **系统主题色开关监听**：
  - 仅在 Android 12+ (API 31+) 系统上显示。
  - 开启时，禁用 `appTheme`（ColorPickerPreference）选择器，并强行载入绑定了 Monet API 的主题 `Theme.SagerNet.Monet`。
  - 关闭时，启用 `appTheme` 选择器，并恢复用户之前选择的主题。
- **主题色改变监听**：
  ```kotlin
  val appTheme = findPreference<ColorPickerPreference>(Key.APP_THEME)!!
  appTheme.setOnPreferenceChangeListener { _, newTheme ->
      if (DataStore.serviceState.started) {
          SagerNet.reloadService() // 重新加载服务以刷新通知栏颜色
      }
      val theme = Theme.getTheme(newTheme as Int)
      app.setTheme(theme) // 应用到 Application
      requireActivity().apply {
          setTheme(theme) // 应用到当前 Activity
          ActivityCompat.recreate(this) // 重建当前 Activity
      }
      true
  }
  ```
- **夜间模式改变监听**：
  ```kotlin
  val nightTheme = findPreference<SimpleMenuPreference>(Key.NIGHT_THEME)!!
  nightTheme.setOnPreferenceChangeListener { _, newTheme ->
      Theme.currentNightMode = (newTheme as String).toInt()
      Theme.applyNightTheme() // 触发 AppCompatDelegate 刷新
      true
  }
  ```

---

## 3. 资源文件定义

### 3.1 自定义主题属性 (attrs.xml)
为了让不同的主题变体能够灵活控制特定 UI 元素的颜色，项目在 [`attrs.xml`](app/src/main/res/values/attrs.xml) 中定义了一系列自定义属性：
- `colorPrimary` / `colorPrimaryDark` / `colorAccent`：标准的 Material Design 核心颜色。
- `colorMaterial100` / `colorMaterial300`：用于特定背景或辅助色。
- `cardElevatedSurfaceColor`：立体卡片（如路由、分组卡片）的“色调提升表面色”，各主题变体朝自身 primary 颜色进行染色。
- `accentOrTextSecondary` / `accentOrTextPrimary`：在普通模式下使用 Accent 色，在特定模式下退化为文本颜色的自适应属性。
- `primaryOrTextSecondary` / `primaryOrTextPrimary`：自适应 Primary 或文本颜色的属性。
- `fabColorBackground`：悬浮操作按钮（FAB）的背景色。
- `selectedColorPrimary`：选中状态下的主色调。

### 3.2 基础主题与变体 (themes.xml)
在 [`themes.xml`](app/src/main/res/values/themes.xml) 中定义了完整的主题树：
- **基础应用主题**：`Theme.SagerNet` 继承自 `Theme.MaterialComponents.DayNight.NoActionBar`，定义了通用的 Material 组件样式（如 Dialog、CardView、TextInputLayout、Button 等的 Style 映射）。
- **基础对话框主题**：`Theme.SagerNet.Dialog` 继承自 `Theme.MaterialComponents.DayNight.Dialog.Alert`。
- **22 种主题变体**：
  - 每一个主题变体（如 `Theme.SagerNet.Red`、`Theme.SagerNet.Amber` 等）都继承自 `Theme.SagerNet`，并覆盖了核心颜色属性。
  - 每一个对话框主题变体（如 `Theme.SagerNet.Dialog.Red` 等）都继承自 `Theme.SagerNet.Dialog`，并覆盖了核心颜色属性。
  - **黑色主题 (`Theme.SagerNet.Black`)** 进行了特殊定制，将 `accentOrTextSecondary` 等自适应属性退化为系统默认的文本颜色（`?android:textColorSecondary`），以保证高对比度和极简视觉。

### 3.3 沉浸式适配 (values-v26/themes.xml)
在 [`values-v26/themes.xml`](app/src/main/res/values-v26/themes.xml) 中，针对 Android 8.0+ (API 26+) 进行了沉浸式适配：
- 将 `android:statusBarColor` 和 `android:navigationBarColor` 设置为 `@android:color/transparent`。
- 在 [`ThemedActivity.kt`](app/src/main/java/io/nekohasekai/sagernet/ui/ThemedActivity.kt:40) 中，通过 `WindowCompat.setDecorFitsSystemWindows(window, false)` 开启全面屏沉浸式。
- 使用 `WindowCompat.getInsetsController` 动态控制状态栏和导航栏图标的亮色/暗色外观：
  - 导航栏图标：`insetController.isAppearanceLightNavigationBars = !Theme.usingNightMode()`。
  - 状态栏图标：对于黑色主题（`Theme.BLACK`），在非夜间模式下将状态栏图标设为亮色（深色图标），其他主题默认保持暗色（白色图标）。

---

## 4. 开发者指南：如何新增一个主题色

若要在未来版本中新增一种主题色（例如命名为 `Emerald` 祖母绿）：

1. **在 [`colors.xml`](app/src/main/res/values/colors.xml) 中定义颜色**：
   - 添加 `material_emerald_500`、`material_emerald_700`、`material_emerald_accent_200` 等颜色值。
   - 将 `material_emerald_500` 添加到 `material_colors` 整数数组中。

2. **在 [`Theme.kt`](app/src/main/java/io/nekohasekai/sagernet/utils/Theme.kt) 中注册常量**：
   - 在 `Theme` 单例中添加 `const val EMERALD = 23`。
   - 在 `getTheme(theme: Int)` 中添加 `EMERALD -> R.style.Theme_SagerNet_Emerald`。
   - 在 `getDialogTheme(theme: Int)` 中添加 `EMERALD -> R.style.Theme_SagerNet_Dialog_Emerald`。

3. **在 [`themes.xml`](app/src/main/res/values/themes.xml) 中定义 Style**：
   - 创建 `Theme.SagerNet.Emerald` 继承自 `Theme.SagerNet`，并覆盖相关颜色属性。
   - 创建 `Theme.SagerNet.Dialog.Emerald` 继承自 `Theme.SagerNet.Dialog`，并覆盖相关颜色属性。
