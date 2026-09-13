package com.touchlock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

/**
 * 抽屉守卫（可选功能，默认关闭）：锁定期间检测到通知栏 / 控制中心被拉出，立即把它关掉。
 *
 * 解决的是“放口袋挂机时误触抽屉里的开关”：飞行模式、Wi-Fi、录屏之类点一下就断网。
 * 覆盖层盖不住抽屉（TYPE_NOTIFICATION_SHADE 层级在 TYPE_APPLICATION_OVERLAY 之上），
 * 系统手势也拦不住，所以只能事后关闭 —— 抽屉停留在屏幕上的时间被压成一次闪现，
 * 误触开关所需的“抽屉停住”这个前提就不存在了。
 *
 * 三个条件同时成立才动手：
 * 1. [SUPPORTED]：GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE 是 API 31 新增。更低版本只能
 *    退化成 GLOBAL_ACTION_BACK，而误判时 BACK 会送给前台应用（对挂机中的游戏可能是致命的），
 *    所以宁可不支持，也不用危险的降级。
 * 2. [Prefs.shadeGuardEnabled]：设置页的开关，作为不进系统设置也能关掉的急停。
 * 3. [TouchLockService.isLocked]：本服务由系统常驻绑定，生命周期与锁定毫无关系。
 *    不锁定时也去关抽屉，用户平时就用不了通知栏 —— 这是本方案唯一会造成灾难性体验的错误。
 *
 * 权限面刻意压到最小：xml 配置里 canRetrieveWindowContent=false，本服务读不到任何界面内容，
 * 只能收到“哪个包的窗口状态变了”这一层元数据；也不声明 canPerformGestures，不注入手势。
 *
 * 检测只认 packageName == com.android.systemui，不认 className：各 ROM 的抽屉类名完全不同
 * （AOSP 是 NotificationPanelView，MIUI 是 MiuiNotificationPanelView），而 SystemUI 的包名
 * 各家都叫 com.android.systemui。误报的代价很低 —— 抽屉没开时这个 global action 是空操作，
 * 音量条、截屏动画等其它 SystemUI 窗口触发一次也无副作用。
 */
class ShadeGuardService : AccessibilityService() {

    /** 上次动手的时间（uptimeMillis），用于 [ACTION_THROTTLE_MS] 节流 */
    private var lastActionAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // 热路径上的判断全部是本地读值，不查系统服务：这个回调在锁定期间可能很密集
        if (!SUPPORTED || !Prefs.shadeGuardEnabled(this) || !TouchLockService.isLocked) return
        if (event.packageName?.toString() != SYSTEMUI_PACKAGE) return
        dismissShade()
    }

    override fun onInterrupt() {
        // 本服务不朗读、不震动，没有需要中断的反馈
    }

    /**
     * 关闭抽屉，分两步：
     *
     * 1. GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE —— 精确、无副作用，抽屉没开时是空操作。
     * 2. GLOBAL_ACTION_BACK —— 兜底。部分机型的控制中心是与通知栏并列的独立窗口，
     *    第 1 步关不掉它（实测表现：通知栏能关、控制中心关不掉），返回键可以。
     *    误发也无害：锁定期间覆盖层窗口持有焦点，而 LockRootLayout.dispatchKeyEvent
     *    会吞掉返回键，事件不会落到底层应用 —— 也就是说锁定期间返回键本来就是失效的。
     *
     * 节流的原因：音量条、截屏动画、横幅通知等 SystemUI 窗口也会触发本回调，
     * 它们并不需要任何动作；抽屉被关掉的过程本身也会再发一次窗口事件。
     */
    private fun dismissShade() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val now = SystemClock.uptimeMillis()
        if (now - lastActionAt < ACTION_THROTTLE_MS) return
        lastActionAt = now
        runCatching { performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) }
        runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
    }

    companion object {

        /** 最低系统版本：Android 12（API 31），关闭抽屉的 global action 从这一版才有 */
        val SUPPORTED: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        private const val SYSTEMUI_PACKAGE = "com.android.systemui"

        /**
         * 两次动作的最小间隔。取 300ms：比抽屉展开动画短（不会漏掉紧接着拉出的控制中心），
         * 又能把口袋里的连续摩擦、以及我们自己关掉抽屉引发的后续事件压成一次。
         */
        private const val ACTION_THROTTLE_MS = 300L

        /**
         * 功能是否真正生效：系统版本支持 + 用户在设置页打开 + 系统里启用了本服务。
         * 只用于设置页与通知文案，不要放进 [onAccessibilityEvent] 的热路径
         * （isEnabled 要跨进程查一次无障碍服务列表）。
         */
        fun isActive(context: Context): Boolean =
            SUPPORTED && Prefs.shadeGuardEnabled(context) && isEnabled(context)

        /** 本服务是否已被系统启用，设置页据此显示状态与「去开启」按钮 */
        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? AccessibilityManager ?: return false
            val self = ComponentName(context, ShadeGuardService::class.java)
            return runCatching {
                manager
                    .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .any { ComponentName.unflattenFromString(it.id) == self }
            }.getOrDefault(false)
        }
    }
}
