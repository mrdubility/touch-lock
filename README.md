# 触摸锁 (Touch Lock)

用悬浮窗覆盖层锁定屏幕触摸的极简 Android 应用：屏幕压到最低亮度、触摸全部被拦截、底层应用保持前台，屏幕四分之三处的滑动条拖到底解锁。

适用场景：擦屏幕 / 贴膜 / 清洁手机、放口袋防误触、给小孩看视频防乱点、直播或展示时防触摸。

## 功能

- **点桌面图标即开始倒数锁定**：默认 5 秒，设置里可调 0–60 秒（0 = 立即锁定）。
- **倒数期间不打断操作**：屏幕顶部只有一张小卡片（含"取消"与"设置"，点齿轮进设置会直接放弃本次锁定），其余区域触摸正常，可自由切换到要保护的应用。
- **锁定态**：全屏覆盖层消费所有触摸，返回键被吞掉，窗口亮度压到最低；底层应用仍在前台运行。
- **解锁**：滑动条位于屏幕垂直四分之三处（拇指更好够到），拖到底即解锁（iPhone 滑动关机样式）；**点通知栏那条通知也能直接解锁**。
- **暗屏下找不到滑块**：长按屏幕任意位置（包括滑块上）临时恢复亮度，松手重新变暗。
- **设置页**：锁定倒计时、悬浮窗权限引导（含分厂商路径）、通知权限、能力边界说明、版本号。
- **长按桌面图标**直接进设置（静态 App Shortcut）。

## 安装

最低 Android 8.0（API 26）。

- 直接下载安装：https://github.com/mrdubility/touch-lock/releases/latest —— 页面里的 `touch-lock-v<版本>-release.apk` 即正式签名包。
- 本地构建（需 Android Studio 或 JDK 17 + SDK 34）：`Build > Build App Bundle(s) / APK(s) > Build APK(s)`，产物在 `app/build/outputs/apk/debug/app-debug.apk`；`gradle assembleRelease` 在能读到签名材料时产出签名版 release APK。

首次运行需授予"显示在其他应用上层"权限：点图标会出现引导页，设置页也提供一键跳转与各品牌路径。

## 签名

- Release 包用自管的正式签名密钥（`keystore/touch-lock-release.jks`，RSA 2048 / SHA256withRSA，有效期至 2056 年）签名，别名 `touch-lock`。**密钥库与密码不入库**（`.gitignore` 忽略 `/keystore/`），CI 从 GitHub Secrets 还原后临时签名，runner 销毁即消失。
- 需要配置的 Secrets 只有两个：`KEYSTORE_BASE64`（密钥库的 base64）与 `KEYSTORE_PASSWORD`。缺失时 tag 构建会明确失败，不会静默发布未签名包（另有 apksigner 硬验签一步）。
- 从 debug 包换到 release 包、或从 v1.0/v1.1.0 的 debug 包升级，都会因签名不同而报"与已安装应用签名冲突"，**先卸载一次**再装 release 包即可；此后各版本之间可正常覆盖升级。
- 历史版本的 debug 包签名互不相同，是因为每次 CI 构建都在全新 runner 上自动生成一份临时 `~/.android/debug.keystore`，这属于 debug 签名的固有行为，不是本项目的 bug。
- **务必备份 `keystore/` 目录与密码**：丢失后无法用同一签名继续发版，老用户只能卸载重装。若将来上架 Google Play，可把这份密钥作为 upload key，或直接改用 Play App Signing 托管。

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
- **长按临时点亮**：`LockRootLayout.dispatchTouchEvent` 旁听手势（不拦截，事件照常下发给滑块），`GestureDetector.onLongPress` 触发时把 `screenBrightness` 改为 `-1.0f`（跟随系统亮度）并 `updateViewLayout`，`ACTION_UP/CANCEL` 改回 `0.0f`。因为只是旁听，拖动滑块超过 touchSlop 时不会触发长按，解锁手势不受干扰。
- **解锁条位置**：`overlay_lock.xml` 用上下两个 `Space`（`layout_weight` 3:1）把解锁条压在垂直约 75% 处，自适应各种屏幕比例。
- **通知交互**：`contentIntent` 直接绑定解锁（锁定阶段）/ 取消（倒计时阶段），与 action 按钮共用同一 `PendingIntent`；设置页入口只保留长按图标快捷方式与未授权引导页，避开“锁定态下绕过锁”的可能。
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

- push 到 `main`：GitHub Actions 自动构建 debug APK 并上传为构建产物（仅用于验证能编过，不作为发布物）。
- push `v*` 标签：额外执行 `assembleRelease` 用正式密钥签名 → `apksigner verify` 校验 → 自动创建 GitHub Release 并附上 `touch-lock-<tag>-release.apk`。
- 仓库未提交 `gradle-wrapper.jar`，CI 会从 `gradle/wrapper/gradle-wrapper.properties` 解析版本号后安装同版本 Gradle，再执行 `gradle assembleDebug/assembleRelease`。详见 `.github/workflows/android.yml`。

本地构建 release 包：把 `keystore/touch-lock-release.jks` 与 `keystore/keystore.properties`（含 `storePassword`）放在仓库根目录的 `keystore/` 下即可被自动读取，无需设置环境变量。

发版：

```bash
git tag v1.2.0
git push origin v1.2.0
```

## 版本历史

- **1.2**（versionCode 3）：改用正式签名密钥发布 release APK（历史 debug 包每次 CI 都换签名，导致覆盖安装报签名冲突）；倒计时卡片点“设置”即取消本次锁定；通知点击由跳设置改为直接解锁/取消；解锁条从屏幕正中移到垂直四分之三处；新增长按屏幕任意位置临时点亮。
- **1.1**（versionCode 2）：解锁方式由双击改为滑动条（防误触）；新增设置页与可调倒计时；点图标直接倒数锁定；新增长按图标进设置的快捷方式；倒计时改为顶部小卡片，期间可自由切换应用。
- **1.0**：首个版本，悬浮窗锁定 + 最低亮度 + 双击解锁。
