package com.touchlock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
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

    private fun dismissShade() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        // 抽屉没开时是空操作；失败也不重试，下一个窗口事件还会再来一次
        runCatching { performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) }
    }

    companion object {

        /** 最低系统版本：Android 12（API 31），关闭抽屉的 global action 从这一版才有 */
        val SUPPORTED: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        private const val SYSTEMUI_PACKAGE = "com.android.systemui"

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
