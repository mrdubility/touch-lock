package com.touchlock

import android.content.Context

/**
 * 极简偏好存储：倒计时秒数、抽屉守卫开关，共两项。
 * 用 object 单例封装，避免各处散落 SharedPreferences 键名。
 */
object Prefs {

    /** 默认倒计时秒数 */
    const val DEFAULT_DELAY = 5
    const val MIN_DELAY = 0

    /** 上限 30 秒：再长就失去“切过去坐好”的意义，反而容易忘记锁 */
    const val MAX_DELAY = 30

    /**
     * 抽屉守卫默认关闭：它要用户额外授予无障碍权限（Android 上最高的应用权限之一），
     * 并且会让“点通知解锁”这条路失效。值不值得由用户自己判断，不替他决定。
     */
    const val DEFAULT_SHADE_GUARD = false

    private const val FILE_NAME = "touch_lock_prefs"
    private const val KEY_DELAY = "lock_delay_seconds"
    private const val KEY_SHADE_GUARD = "shade_guard_enabled"

    fun delaySeconds(context: Context): Int =
        prefs(context).getInt(KEY_DELAY, DEFAULT_DELAY).coerceIn(MIN_DELAY, MAX_DELAY)

    fun setDelaySeconds(context: Context, seconds: Int) {
        prefs(context).edit()
            .putInt(KEY_DELAY, seconds.coerceIn(MIN_DELAY, MAX_DELAY))
            .apply()
    }

    /** 抽屉守卫开关：打开后还需在系统设置里启用无障碍服务才真正生效 */
    fun shadeGuardEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHADE_GUARD, DEFAULT_SHADE_GUARD)

    fun setShadeGuardEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_SHADE_GUARD, enabled)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
