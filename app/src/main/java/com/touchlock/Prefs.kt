package com.touchlock

import android.content.Context

/**
 * 极简偏好存储：目前只保存"点击图标后多少秒锁定"。
 * 用 object 单例封装，避免各处散落 SharedPreferences 键名。
 */
object Prefs {

    /** 默认倒计时秒数 */
    const val DEFAULT_DELAY = 5
    const val MIN_DELAY = 0
    const val MAX_DELAY = 60

    private const val FILE_NAME = "touch_lock_prefs"
    private const val KEY_DELAY = "lock_delay_seconds"

    fun delaySeconds(context: Context): Int =
        prefs(context).getInt(KEY_DELAY, DEFAULT_DELAY).coerceIn(MIN_DELAY, MAX_DELAY)

    fun setDelaySeconds(context: Context, seconds: Int) {
        prefs(context).edit()
            .putInt(KEY_DELAY, seconds.coerceIn(MIN_DELAY, MAX_DELAY))
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
