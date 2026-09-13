package com.touchlock

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * 设置页：
 * 1. 悬浮窗权限状态 + 一键跳系统授权页 + 分厂商路径说明（多条字符串在代码里用换行拼接）；
 * 2. 锁定倒计时秒数（SeekBar 0..60 与 4 个快捷按钮），写入 [Prefs]；
 * 3. 通知权限（仅 Android 13+ 显示），说明它是滑动条之外的备用解锁出口；
 * 4. 能力边界与版本号（只读）。
 *
 * 权限状态在 onResume 刷新，从系统设置页返回后立刻能看到最新结果。
 */
class SettingsActivity : Activity() {

    private lateinit var overlayStatusText: TextView
    private lateinit var overlayGoButton: Button
    private lateinit var delayValueText: TextView
    private lateinit var delaySeekBar: SeekBar
    private lateinit var notifyCard: LinearLayout
    private lateinit var notifyStatusText: TextView
    private lateinit var notifyGoButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        overlayStatusText = findViewById(R.id.overlayStatusText)
        overlayGoButton = findViewById(R.id.overlayGoButton)
        delayValueText = findViewById(R.id.delayValueText)
        delaySeekBar = findViewById(R.id.delaySeekBar)
        notifyCard = findViewById(R.id.notifyCard)
        notifyStatusText = findViewById(R.id.notifyStatusText)
        notifyGoButton = findViewById(R.id.notifyGoButton)

        findViewById<TextView>(R.id.overlayPathsText).text = vendorOverlayPaths()
        findViewById<TextView>(R.id.aboutVersionText).text =
            getString(R.string.settings_about_version, versionName())

        overlayGoButton.setOnClickListener { openOverlayPermissionSettings() }
        notifyGoButton.setOnClickListener { requestNotificationPermission() }
        setupDelayControls()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStates()
    }

    private fun setupDelayControls() {
        val current = Prefs.delaySeconds(this)
        delaySeekBar.progress = current
        delayValueText.text = delayLabel(current)

        delaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                delayValueText.text = delayLabel(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Prefs.setDelaySeconds(this@SettingsActivity, delaySeekBar.progress)
            }
        })

        findViewById<Button>(R.id.delayNowButton).setOnClickListener { applyDelay(0) }
        findViewById<Button>(R.id.delay3Button).setOnClickListener { applyDelay(3) }
        findViewById<Button>(R.id.delay5Button).setOnClickListener { applyDelay(5) }
        findViewById<Button>(R.id.delay10Button).setOnClickListener { applyDelay(10) }
    }

    private fun applyDelay(seconds: Int) {
        Prefs.setDelaySeconds(this, seconds)
        delaySeekBar.progress = seconds
        delayValueText.text = delayLabel(seconds)
    }

    private fun delayLabel(seconds: Int): String =
        if (seconds <= 0) {
            getString(R.string.settings_delay_zero)
        } else {
            getString(R.string.settings_delay_value, seconds)
        }

    private fun refreshPermissionStates() {
        if (Settings.canDrawOverlays(this)) {
            overlayStatusText.text = getString(R.string.settings_overlay_on)
            overlayGoButton.visibility = View.GONE
        } else {
            overlayStatusText.text = getString(R.string.settings_overlay_off)
            overlayGoButton.visibility = View.VISIBLE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifyCard.visibility = View.VISIBLE
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            notifyStatusText.text = getString(
                if (granted) R.string.settings_notify_on else R.string.settings_notify_off
            )
            notifyGoButton.visibility = if (granted) View.GONE else View.VISIBLE
        } else {
            notifyCard.visibility = View.GONE
        }
    }

    /** 厂商路径拆成多条单行字符串，在此拼接，避免 strings.xml 里的裸换行被 aapt2 折叠成空格 */
    private fun vendorOverlayPaths(): String = listOf(
        getString(R.string.settings_overlay_path_aosp),
        getString(R.string.settings_overlay_path_miui),
        getString(R.string.settings_overlay_path_emui),
        getString(R.string.settings_overlay_path_other)
    ).joinToString("\n")

    private fun openOverlayPermissionSettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        }
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
    }.getOrDefault("-")

    companion object {
        private const val REQ_NOTIFICATION = 100
    }
}
