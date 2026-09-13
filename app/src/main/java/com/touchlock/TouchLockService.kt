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
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import kotlin.math.abs

/**
 * 覆盖层根布局，承担三件事：
 * 1. 吞掉返回键，防止锁定期间被 BACK 误退出（需窗口可聚焦，不加 FLAG_NOT_FOCUSABLE）；
 * 2. clickable 消费所有触摸，使事件不下传到底层应用；
 * 3. 在 dispatchTouchEvent 里“旁听”手势：按住屏幕任意位置（含滑块上）临时点亮，
 *    松手压回最暗 —— 解决最低背光下看不清滑块的问题。按住满 [PEEK_TRIGGER_DELAY_MS]
 *    即点亮，一旦开始拖动更是当场点亮（拖滑块时最需要看清位置）。
 *    旁听而非拦截：事件照常交给子 View，滑动解锁不受影响。手写计时而不用 GestureDetector，
 *    是因为 SimpleOnGestureListener 同时实现 OnGestureListener 与 OnDoubleTapListener，
 *    在 Kotlin 里会造成 GestureDetector 构造函数的重载歧义。
 */
class LockRootLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 亮度临时恢复回调：true = 按住中（点亮），false = 松手（压回最暗） */
    var onBrightnessPeek: ((Boolean) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var peeking = false
    private var downX = 0f
    private var downY = 0f
    private val peekRunnable = Runnable { startPeek() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            return true // 消费返回键
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                postDelayed(peekRunnable, PEEK_TRIGGER_DELAY_MS)
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                    // 一开始拖动就点亮：拖滑块时正是最需要看清位置的时候，
                    // 不该再等按住满多少毫秒（startPeek 幂等，已亮则重复调用无影响）
                    startPeek()
                }
            }

            // 多指按下时取消尚未触发的点亮计时（点亮本身无副作用，松手即灭）
            MotionEvent.ACTION_POINTER_DOWN -> cancelPeekTimer()

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPeekTimer()
                stopPeek()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        cancelPeekTimer()
        stopPeek()
        super.onDetachedFromWindow()
    }

    private fun cancelPeekTimer() {
        removeCallbacks(peekRunnable)
    }

    private fun startPeek() {
        if (peeking) return
        peeking = true
        onBrightnessPeek?.invoke(true)
    }

    private fun stopPeek() {
        if (!peeking) return
        peeking = false
        onBrightnessPeek?.invoke(false)
    }

    private companion object {
        /**
         * 按住多久开始点亮。系统默认的长按阈值是 500ms，再叠加 SurfaceFlinger
         * 对背光变化的固定渐变，观感接近按了一秒才亮；压到 200ms 就是“按下即有反应”。
         * 想改成接触瞬间点亮则填 0。
         */
        const val PEEK_TRIGGER_DELAY_MS = 200L
    }
}

/**
 * 触摸锁前台服务，分两个阶段：
 *
 * 1. 倒计时阶段：屏幕顶部一张小卡片浮层。窗口只有卡片那么高，卡片以外的触摸会落到底层应用，
 *    因此用户可以照常切换到要保护的应用；卡片上提供取消按钮与设置入口（进设置即放弃本次锁定）。
 * 2. 锁定阶段：全屏 TYPE_APPLICATION_OVERLAY 覆盖层，消费所有触摸、吞掉返回键，
 *    窗口 screenBrightness=0.0f 把背光压到最低；滑动解锁条放在屏幕垂直四分之三处（拇指更好够到），
 *    按住屏幕任意位置（或一开始拖动滑块）即临时点亮以便看清滑块，松手重新变暗。
 *
 * 解锁、取消、点通知都会 stopSelf，onDestroy 负责移除全部浮层，亮度随窗口移除自动恢复。
 * 触发锁定的入口有两个：桌面图标（[MainActivity]）与下拉栏快捷磁贴（[LockTileService]），
 * 二者都走 startForegroundService 并带上设置里存的倒计时秒数；已处于锁定态时新的锁定请求会被忽略。
 */
class TouchLockService : Service() {

    companion object {
        const val ACTION_UNLOCK = "com.touchlock.action.UNLOCK"
        const val ACTION_CANCEL_COUNTDOWN = "com.touchlock.action.CANCEL_COUNTDOWN"
        const val EXTRA_DELAY_SECONDS = "com.touchlock.extra.DELAY_SECONDS"
        private const val CHANNEL_ID = "touch_lock_channel"
        private const val NOTIFICATION_ID = 1001

        /** 锁定态亮度：0.0 = 最暗（个别 ROM 不生效可改 0.01f） */
        private const val DIM_BRIGHTNESS = 0.0f

        /**
         * 按住点亮时的亮度：显式给到最亮。
         *
         * 不用 -1.0f（AOSP 的 SCREEN_BRIGHTNESS_DEFAULT，它是 @hide，公开 SDK 只有
         * OVERRIDE_OFF / OVERRIDE_FULL）：-1.0f 的含义是“交回系统当前亮度”，
         * 而自动亮度在暗环境里本身就压得很低、还会以秒为单位缓慢爬升，
         * 结果就是“按了半天亮得又慢又暗”。显式 1.0f 锁定的是本窗口的背光，不依赖系统状态。
         */
        private const val PEEK_BRIGHTNESS = 1.0f

        /**
         * 是否处于锁定态，供 [ShadeGuardService] 判定“现在该不该关抽屉”。
         *
         * 用进程内共享标志而不是 bindService：守卫服务由系统常驻绑定，生命周期与锁定无关，
         * 为它维持一次绑定反而更脆弱。两者同进程，回调也都在主线程。
         */
        @Volatile
        var isLocked: Boolean = false
    }

    private lateinit var windowManager: WindowManager
    private var countdownView: View? = null
    private var lockView: View? = null
    private var countDownTimer: CountDownTimer? = null

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
        if (isLocked) {
            // 已锁定时忽略新的锁定请求：没开抽屉守卫时锁定期间通知栏仍可下拉，
            // 此时再点磁贴/图标会在锁屏之上又叠一层倒计时卡片，属于多余状态
            return START_NOT_STICKY
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
            // 进设置就是放弃本次锁定：倒计时若继续跑，用户改完参数回来就被锁住，属于意外行为
            stopSelf()
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
        if (!isLocked) {
            showLock()
            isLocked = true
        }
        notifyState(buildLockNotification())
    }

    private fun showLock() {
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.overlay_lock, null) as LockRootLayout
        view.isFocusableInTouchMode = true

        // 滑动解锁条：拖到底才解锁，替代原先易误触的双击
        view.findViewById<SlideToUnlockView>(R.id.slideToUnlock).onUnlocked = { stopSelf() }

        // 按住屏幕任意位置（含滑块上）临时点亮，便于看清滑块位置；松手压回最暗
        val peekHint = view.findViewById<TextView>(R.id.peekHintText)
        view.onBrightnessPeek = { peek ->
            applyScreenBrightness(if (peek) PEEK_BRIGHTNESS else DIM_BRIGHTNESS)
            peekHint.setText(
                if (peek) R.string.overlay_peek_active else R.string.overlay_peek_hint
            )
        }

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
            screenBrightness = DIM_BRIGHTNESS
        }

        windowManager.addView(view, params)
        lockView = view
        view.requestFocus()
    }

    /** 切换锁定窗口亮度：[PEEK_BRIGHTNESS] = 点亮，[DIM_BRIGHTNESS] = 压到最暗 */
    private fun applyScreenBrightness(value: Float) {
        val view = lockView ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        if (lp.screenBrightness == value) return
        lp.screenBrightness = value
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    private fun removeLock() {
        lockView?.let { v -> runCatching { windowManager.removeView(v) } }
        lockView = null
        isLocked = false
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

    /** 倒计时阶段的通知：点通知本体与“取消”按钮等价，都是放弃本次锁定 */
    private fun buildCountdownNotification(seconds: Int): Notification {
        val cancelIntent = servicePendingIntent(ACTION_CANCEL_COUNTDOWN, 0)
        return baseNotificationBuilder(cancelIntent)
            .setContentTitle(getString(R.string.notify_countdown_title))
            .setContentText(getString(R.string.notify_countdown_text, seconds))
            .addAction(buildAction(R.string.notify_cancel, cancelIntent))
            .build()
    }

    /**
     * 锁定阶段的通知：整条通知即解锁入口，不再另设“解锁”按钮。
     * 点击通知本体已占满通知宽度，再摆一个语义相同的按钮只是噪声；
     * 倒计时阶段保留“取消”按钮，因为那是一次性、不可逆的放弃动作，值得一个明确入口。
     */
    private fun buildLockNotification(): Notification =
        baseNotificationBuilder(servicePendingIntent(ACTION_UNLOCK, 1))
            .setContentTitle(getString(R.string.notify_title))
            // 开了抽屉守卫后抽屉会被立即关闭，“点通知解锁”这条路实际用不上，文案如实说明
            .setContentText(
                getString(
                    if (ShadeGuardService.isActive(this)) {
                        R.string.notify_text_guard
                    } else {
                        R.string.notify_text
                    }
                )
            )
            .build()

    /**
     * contentIntent 直接绑定解锁/取消：点图标已是“直接开始锁定”，
     * 通知点击再跳设置既不符合直觉，也白白多一层操作。
     */
    private fun baseNotificationBuilder(contentIntent: PendingIntent): Notification.Builder =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
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

    private fun notifyState(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }
}
