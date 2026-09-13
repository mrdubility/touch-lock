package com.touchlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView

/**
 * 覆盖层根布局：吞掉返回键，防止锁定期间被 BACK 误退出。
 * 需窗口可聚焦（不加 FLAG_NOT_FOCUSABLE）才能收到按键事件。
 */
class LockRootLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            return true // 消费返回键
        }
        return super.dispatchKeyEvent(event)
    }
}

/**
 * 触摸锁前台服务，分两个阶段：
 *
 * 1. 倒计时阶段：屏幕顶部一张小卡片浮层。窗口只有卡片那么高，卡片以外的触摸会落到底层应用，
 *    因此用户可以照常切换到要保护的应用；卡片上提供取消按钮与进入设置的入口。
 * 2. 锁定阶段：全屏 TYPE_APPLICATION_OVERLAY 覆盖层，消费所有触摸、吞掉返回键，
 *    窗口 screenBrightness=0.0f 把背光压到最低；屏幕正中央是滑动解锁条，拖到底才解锁。
 *
 * 解锁与取消都会 stopSelf，onDestroy 负责移除全部浮层，亮度随窗口移除自动恢复。
 */
class TouchLockService : Service() {

    companion object {
        const val ACTION_UNLOCK = "com.touchlock.action.UNLOCK"
        const val ACTION_CANCEL_COUNTDOWN = "com.touchlock.action.CANCEL_COUNTDOWN"
        const val EXTRA_DELAY_SECONDS = "com.touchlock.extra.DELAY_SECONDS"
        private const val CHANNEL_ID = "touch_lock_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private var countdownView: View? = null
    private var lockView: View? = null
    private var countDownTimer: CountDownTimer? = null
    private var locked = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        // 必须在 startForegroundService 后 5 秒内调用 startForeground
        startForegroundCompat(buildCountdownNotification(Prefs.delaySeconds(this)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UNLOCK, ACTION_CANCEL_COUNTDOWN -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val seconds = intent?.getIntExtra(EXTRA_DELAY_SECONDS, Prefs.DEFAULT_DELAY)
            ?: Prefs.DEFAULT_DELAY
        if (seconds <= 0) {
            enterLockedState()
        } else {
            showCountdown(seconds)
        }
        // 不用 START_STICKY：被系统回收后若自动重启，会以空 Intent 再次锁屏，属于意外行为
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        countDownTimer?.cancel()
        countDownTimer = null
        removeCountdown()
        removeLock()
        super.onDestroy()
    }

    // ---------- 阶段一：倒计时卡片 ----------

    private fun showCountdown(seconds: Int) {
        // 倒计时中重复触发（例如又点了一次图标）不重置，避免锁定被无限推迟
        if (countdownView != null) return

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.overlay_countdown, null)

        val secondsText = view.findViewById<TextView>(R.id.countdownSecondsText)
        secondsText.text = seconds.toString()
        view.findViewById<Button>(R.id.cancelButton).setOnClickListener { stopSelf() }
        view.findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE：不抢焦点、不会弹输入法；不加 NOT_TOUCHABLE，卡片上的按钮才能点。
            // 窗口只覆盖顶部卡片区域，其余屏幕的触摸自然落到底层应用，用户可正常切换应用。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight()
        }

        windowManager.addView(view, params)
        countdownView = view

        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val left = ((millisUntilFinished + 999) / 1000).toInt()
                secondsText.text = left.toString()
            }

            override fun onFinish() {
                enterLockedState()
            }
        }.start()
    }

    private fun statusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    private fun removeCountdown() {
        countdownView?.let { v -> runCatching { windowManager.removeView(v) } }
        countdownView = null
    }

    // ---------- 阶段二：全屏锁定 ----------

    private fun enterLockedState() {
        countDownTimer?.cancel()
        countDownTimer = null
        removeCountdown()
        if (!locked) {
            showLock()
            locked = true
        }
        notifyState(buildLockNotification())
    }

    private fun showLock() {
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.overlay_lock, null)
        view.isFocusableInTouchMode = true

        // 中央滑动解锁条：拖到底才解锁，替代原先易误触的双击
        view.findViewById<SlideToUnlockView>(R.id.slideToUnlock).onUnlocked = { stopSelf() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            // 可聚焦以拦截返回键；不加 FLAG_NOT_TOUCHABLE，保证触摸被覆盖层消费而不下传
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 亮度压到最低（0.0 = 最暗）。个别 ROM 若不生效可改为 0.01f。
            screenBrightness = 0.0f
        }

        windowManager.addView(view, params)
        lockView = view
        view.requestFocus()
    }

    private fun removeLock() {
        lockView?.let { v -> runCatching { windowManager.removeView(v) } }
        lockView = null
        locked = false
    }

    // ---------- 前台服务与通知 ----------

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** 倒计时阶段的通知：提供取消入口 */
    private fun buildCountdownNotification(seconds: Int): Notification =
        baseNotificationBuilder()
            .setContentTitle(getString(R.string.notify_countdown_title))
            .setContentText(getString(R.string.notify_countdown_text, seconds))
            .addAction(
                buildAction(
                    R.string.notify_cancel,
                    servicePendingIntent(ACTION_CANCEL_COUNTDOWN, 0)
                )
            )
            .build()

    /** 锁定阶段的通知：滑动条之外的备用解锁出口 */
    private fun buildLockNotification(): Notification =
        baseNotificationBuilder()
            .setContentTitle(getString(R.string.notify_title))
            .setContentText(getString(R.string.notify_text))
            .addAction(
                buildAction(
                    R.string.notify_unlock,
                    servicePendingIntent(ACTION_UNLOCK, 1)
                )
            )
            .build()

    private fun baseNotificationBuilder(): Notification.Builder =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(settingsPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)

    private fun buildAction(labelRes: Int, pendingIntent: PendingIntent): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notification),
            getString(labelRes),
            pendingIntent
        ).build()

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, TouchLockService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** 点通知进设置页：点图标已是直接锁定，通知点击再触发锁定会造成误锁 */
    private fun settingsPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            2,
            Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun notifyState(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }
}
