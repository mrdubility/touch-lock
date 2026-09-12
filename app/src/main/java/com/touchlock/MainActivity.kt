package com.touchlock

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * 主界面：
 * 1. 检查/引导"显示在其他应用上层"（悬浮窗）权限；
 * 2. Android 13+ 申请通知权限（前台服务通知需要）；
 * 3. 点击"锁定屏幕"后 3 秒倒计时，便于切换到目标应用，倒计时结束启动前台服务显示覆盖层。
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var lockButton: Button
    private lateinit var permissionButton: Button

    private var countDownTimer: CountDownTimer? = null
    private var counting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        lockButton = findViewById(R.id.lockButton)
        permissionButton = findViewById(R.id.permissionButton)

        permissionButton.setOnClickListener { openOverlayPermissionSettings() }
        lockButton.setOnClickListener { startLockCountdown() }

        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (!counting) updateUiByPermission()
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        countDownTimer = null
        super.onDestroy()
    }

    private fun updateUiByPermission() {
        if (Settings.canDrawOverlays(this)) {
            permissionButton.visibility = View.GONE
            lockButton.isEnabled = true
            statusText.text = getString(R.string.status_ready)
        } else {
            permissionButton.visibility = View.VISIBLE
            lockButton.isEnabled = false
            statusText.text = getString(R.string.status_need_permission)
        }
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        }
    }

    private fun startLockCountdown() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlayPermissionSettings()
            return
        }
        counting = true
        lockButton.isEnabled = false
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(COUNTDOWN_MS, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = ((millisUntilFinished + 999) / 1000).toInt()
                statusText.text = getString(R.string.countdown, seconds)
            }

            override fun onFinish() {
                counting = false
                startLockService()
                // 覆盖层已遮住本界面；重置为就绪态，解锁后返回时 UI 正确
                updateUiByPermission()
            }
        }.start()
    }

    private fun startLockService() {
        // Android 8+ 后台/前台启动前台服务都需用 startForegroundService
        startForegroundService(Intent(this, TouchLockService::class.java))
    }

    companion object {
        private const val COUNTDOWN_MS = 3000L
        private const val REQ_NOTIFICATION = 100
    }
}
