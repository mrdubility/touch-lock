package com.touchlock

import android.annotation.SuppressLint
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
import android.os.IBinder
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

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
 * 触摸锁前台服务：
 * - 通过 WindowManager 添加一个全屏 TYPE_APPLICATION_OVERLAY 覆盖层；
 * - 覆盖层消费所有触摸，底层应用保持前台；
 * - 窗口 screenBrightness=0.0f 把背光压到最低；
 * - 屏幕正中央按钮双击解锁（stopSelf 移除覆盖层）；
 * - 通知栏提供"解锁"入口。
 */
class TouchLockService : Service() {

    companion object {
        const val ACTION_UNLOCK = "com.touchlock.action.UNLOCK"
        private const val CHANNEL_ID = "touch_lock_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        // 必须在 startForegroundService 后 5 秒内调用 startForeground
        startForegroundCompat(buildNotification())
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UNLOCK) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

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

    private fun buildNotification(): Notification {
        val unlockIntent = Intent(this, TouchLockService::class.java).apply {
            action = ACTION_UNLOCK
        }
        val unlockPending = PendingIntent.getService(
            this, 0, unlockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(
            this, 1, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val unlockAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notification),
            getString(R.string.notify_unlock),
            unlockPending
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notify_title))
            .setContentText(getString(R.string.notify_text))
            .setContentIntent(contentPending)
            .addAction(unlockAction)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.overlay_lock, null)
        view.isFocusableInTouchMode = true

        // 中央解锁按钮：双击解锁
        val unlockButton = view.findViewById<View>(R.id.unlockButton)
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                stopSelf()
                return true
            }
        })
        unlockButton.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
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
            // 亮度压到最低（0.0 = 最暗）。个别 ROM 若不生效可改为 0.01f。
            screenBrightness = 0.0f
        }

        windowManager.addView(view, params)
        overlayView = view
        view.requestFocus()
    }

    private fun removeOverlay() {
        overlayView?.let { v -> runCatching { windowManager.removeView(v) } }
        overlayView = null
    }
}
