package com.touchlock

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * 入口 Activity（launcher），只做路由：
 * - 已授权悬浮窗：直接启动前台服务开始倒数锁定，随后 finish。点图标即触发，
 *   这是专注类应用的主流交互；下拉栏快捷磁贴（[LockTileService]）是同一动作的第二个入口。
 *   设置入口因此改由两处提供：长按桌面图标的快捷方式、倒计时卡片上的齿轮；
 *   未授权时的引导页上也保留「设置」按钮。
 * - 未授权：显示引导页，引导开启"显示在其他应用上层"，并可进入设置。
 */
class MainActivity : Activity() {

    private var statusText: TextView? = null
    private var permissionButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Settings.canDrawOverlays(this)) {
            startLockCountdown()
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        permissionButton = findViewById(R.id.permissionButton)
        permissionButton?.setOnClickListener { openOverlayPermissionSettings() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // 已授权路径在 onCreate 就 finish 了，走不到这里；此处只服务引导页
        val status = statusText ?: return
        if (Settings.canDrawOverlays(this)) {
            status.text = getString(R.string.status_ready)
            permissionButton?.visibility = View.GONE
        } else {
            status.text = getString(R.string.status_need_permission)
            permissionButton?.visibility = View.VISIBLE
        }
    }

    /** 把设置里的倒计时秒数交给前台服务，由服务执行"倒数卡片 -> 全屏锁定" */
    private fun startLockCountdown() {
        // Android 8+ 启动前台服务必须用 startForegroundService
        startForegroundService(
            Intent(this, TouchLockService::class.java)
                .putExtra(TouchLockService.EXTRA_DELAY_SECONDS, Prefs.delaySeconds(this))
        )
    }

    private fun openOverlayPermissionSettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }
}
