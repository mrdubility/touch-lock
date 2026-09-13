# 触摸锁 (Touch Lock)

用悬浮窗覆盖层锁定屏幕触摸的极简 Android 应用：屏幕压到最低亮度、触摸全部被拦截、底层应用保持前台，屏幕正中央的滑动条拖到底解锁。

适用场景：擦屏幕 / 贴膜 / 清洁手机、放口袋防误触、给小孩看视频防乱点、直播或展示时防触摸。

## 功能

- **点桌面图标即开始倒数锁定**：默认 5 秒，设置里可调 0–60 秒（0 = 立即锁定）。
- **倒数期间不打断操作**：屏幕顶部只有一张小卡片（含"取消"与"设置"），其余区域触摸正常，可自由切换到要保护的应用。
- **锁定态**：全屏覆盖层消费所有触摸，返回键被吞掉，窗口亮度压到最低；底层应用仍在前台运行。
- **解锁**：屏幕中央滑动条拖到底（iPhone 滑动关机样式）；通知栏"解锁"按钮为备用出口。
- **设置页**：锁定倒计时、悬浮窗权限引导（含分厂商路径）、通知权限、能力边界说明、版本号。
- **长按桌面图标**直接进设置（静态 App Shortcut）。

## 安装

最低 Android 8.0（API 26）。

- 直接下载安装：https://github.com/mrdubility/touch-lock/releases/latest
- 本地构建（需 Android Studio 或 JDK 17 + SDK 34）：`Build > Build App Bundle(s) / APK(s) > Build APK(s)`，产物在 `app/build/outputs/apk/debug/app-debug.apk`。

首次运行需授予"显示在其他应用上层"权限：点图标会出现引导页，设置页也提供一键跳转与各品牌路径。

## 权限清单（共 4 项，全部为功能必需）

| 权限 | 用途 |
|---|---|
| `SYSTEM_ALERT_WINDOW` | 显示全屏覆盖层以拦截触摸（核心） |
| `FOREGROUND_SERVICE` | 锁定期间保持进程不被回收 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | targetSdk 34 对前台服务类型的强制要求 |
| `POST_NOTIFICATIONS` | Android 13+ 展示常驻通知，提供备用"解锁"与"取消"入口 |

**明确未申请**：剪贴板读写、传感器 / 设备动作方向、安装桌面快捷方式（`INSTALL_SHORTCUT`）、读取应用列表（`QUERY_ALL_PACKAGES`）、定位、相机、麦克风、通讯录、存储读写、修改系统设置（`WRITE_SETTINGS`）、振动。

说明：桌面长按快捷方式通过 `app/src/main/res/xml/shortcuts.xml` 静态声明实现，不需要任何权限；亮度调低走窗口参数 `screenBrightness`，也不需要 `WRITE_SETTINGS`。

## 能力边界（重要）

| 可以拦截 | 无法拦截 |
|---|---|
| 屏幕内的普通触摸（点击、滑动、长按） | 通知栏 / 控制中心下拉 |
| 返回键（含手势返回中的按键事件部分） | 上滑回桌面、上滑悬停进任务中心 |
| — | 侧滑返回等全面屏系统手势 |
| — | 音量键、电源键、指纹 / 人脸解锁 |

### 为什么拦不住通知栏与系统手势

1. **窗口层级**：SystemUI 的 `StatusBar` / `NavigationBar` / `NotificationShade` 窗口类型高于 `TYPE_APPLICATION_OVERLAY`，下拉手势由 SystemUI 在系统层优先接管，应用层没有可用 API 阻止。
2. **手势不是按键**：全面屏的上滑回桌面、上滑悬停、侧滑返回由系统 `InputDispatcher` 直接消费，不产生 `KeyEvent`。因此即便接入无障碍服务并用 `FLAG_REQUEST_FILTER_KEY_EVENTS` 过滤按键，也只能挡住三键导航与实体键，挡不住手势。
3. **能做到系统级禁用的两条路**（本项目未实现）：
   - **屏幕固定 / Lock Task**：`Activity.startLockTask()`，系统会禁用状态栏下拉、最近任务与通知展开。代价：必须有前台 Activity，会覆盖当前应用（与"底层应用保持前台"的核心目标冲突）；首次需用户确认；部分 ROM 需先在系统设置中启用"屏幕固定"。
   - **Device Owner（Kiosk 模式）**：`adb shell dpm set-device-owner com.touchlock/.AdminReceiver` 一次性配置后，可无提示进入 Lock Task 并彻底禁用状态栏与导航栏。代价：需电脑 adb 操作、设备不能有其他账户、退出流程繁琐。

本项目为兼顾"保持底层应用前台 + 权限最小化"，选择纯悬浮窗方案，并把边界如实写清。

## 技术要点

- **覆盖层**：`TYPE_APPLICATION_OVERLAY` + `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS | FLAG_KEEP_SCREEN_ON`，不加 `FLAG_NOT_TOUCHABLE`，触摸被覆盖层消费而不下传。
- **亮度**：窗口参数 `screenBrightness = 0.0f`（个别 ROM 可改 `0.01f`），窗口移除后自动恢复原亮度。
- **返回键**：自定义 `LockRootLayout.dispatchKeyEvent` 吞掉 `KEYCODE_BACK`。
- **倒计时卡片**：`MATCH_PARENT x WRAP_CONTENT` + `FLAG_NOT_FOCUSABLE`，只覆盖顶部一小块，其余屏幕触摸自然穿透给底层应用。
- **滑动解锁**：`SlideToUnlockView` 纯代码绘制轨道 / 滑块 / 箭头；必须按在滑块上（含 12dp 容差）并拖过 92% 行程才解锁，松手未到位自动回弹 —— 单点、长按、慢速拖动都不会误解锁。
- **前台服务**：`foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`；`START_NOT_STICKY`，避免被系统回收后以空 Intent 意外重锁。
- **零 AndroidX 依赖**：`android.app.Activity` + 平台 `Theme.Material.Light.NoActionBar`，仅依赖 Kotlin stdlib，最大化构建成功率。

## 目录结构

```
app/src/main/
├─ AndroidManifest.xml
├─ java/com/touchlock/
│  ├─ MainActivity.kt          # 入口路由：已授权直接倒数，未授权显示引导页
│  ├─ SettingsActivity.kt      # 设置页
│  ├─ TouchLockService.kt      # 前台服务：倒计时卡片 + 全屏锁定覆盖层
│  ├─ SlideToUnlockView.kt     # 滑动解锁条
│  ├─ Prefs.kt                 # 倒计时秒数持久化
│  └─ (LockRootLayout 定义在 TouchLockService.kt 顶部)
└─ res/
   ├─ layout/  activity_main, activity_settings, overlay_countdown, overlay_lock
   ├─ values/  strings, colors, themes
   ├─ drawable/ bg_countdown_card, ic_settings, ic_notification, ic_launcher_foreground
   ├─ xml/      shortcuts
   └─ mipmap-anydpi-v26/  ic_launcher, ic_launcher_round
```

## CI / 发版

- push 到 `main`：GitHub Actions 自动构建 debug APK 并上传为构建产物。
- push `v*` 标签：自动创建 GitHub Release 并附上 `touch-lock-<tag>-debug.apk`。
- 仓库未提交 `gradle-wrapper.jar`，CI 会从 `gradle/wrapper/gradle-wrapper.properties` 解析版本号后安装同版本 Gradle，再执行 `gradle assembleDebug`。详见 `.github/workflows/android.yml`。

发版：

```bash
git tag v1.2.0
git push origin v1.2.0
```

## 版本历史

- **1.1**（versionCode 2）：解锁方式由双击改为滑动条（防误触）；新增设置页与可调倒计时；点图标直接倒数锁定；新增长按图标进设置的快捷方式；倒计时改为顶部小卡片，期间可自由切换应用。
- **1.0**：首个版本，悬浮窗锁定 + 最低亮度 + 双击解锁。
