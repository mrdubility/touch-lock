package com.touchlock

import android.Manifest
import android.app.Activity
import android.content.ComponentName
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
import android.widget.Switch
import android.widget.TextView

/**
 * 设置页：
 * 1. 悬浮窗权限状态 + 一键跳系统授权页 + 分厂商路径说明（多条字符串在代码里用换行拼接）；
 * 2. 锁定倒计时秒数（SeekBar 0..30 与 4 个快捷按钮），写入 [Prefs]；
 * 3. 通知权限（仅 Android 13+ 显示），说明它是滑动条之外的备用解锁出口；
 * 4. 抽屉守卫（仅 Android 12+ 显示），把开与不开的代价写清楚，由用户自己决定要不要用；
 * 5. 能力边界与版本号（只读）。
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
    private lateinit var guardCard: LinearLayout
    private lateinit var guardStatusText: TextView
    private lateinit var guardSwitch: Switch
    private lateinit var guardGoButton: Button

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
        guardCard = findViewById(R.id.guardCard)
        guardStatusText = findViewById(R.id.guardStatusText)
        guardSwitch = findViewById(R.id.guardSwitch)
        guardGoButton = findViewById(R.id.guardGoButton)

        findViewById<TextView>(R.id.overlayPathsText).text = vendorOverlayPaths()
        findViewById<TextView>(R.id.aboutVersionText).text =
            getString(R.string.settings_about_version, versionName())

        overlayGoButton.setOnClickListener { openOverlayPermissionSettings() }
        notifyGoButton.setOnClickListener { requestNotificationPermission() }
        setupDelayControls()
        setupGuardControls()
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
        findViewById<Button>(R.id.delay1Button).setOnClickListener { applyDelay(1) }
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

        refreshGuardState()
    }

    /**
     * 抽屉守卫的状态行要把两件事分开说清楚：本页的开关只是“用户想不想用”，
     * 真正生效还得在系统无障碍页面里启用服务 —— 只看开关会误以为已经生效。
     */
    private fun refreshGuardState() {
        // Android 12 以下没有关闭抽屉的 global action，整张卡片不显示（与通知权限卡片同样处理）
        if (!ShadeGuardService.SUPPORTED) {
            guardCard.visibility = View.GONE
            return
        }
        guardCard.visibility = View.VISIBLE

        val wanted = Prefs.shadeGuardEnabled(this)
        // 先比对再赋值：值没变就不触发监听器，避免绕一圈又写回同一个值
        if (guardSwitch.isChecked != wanted) guardSwitch.isChecked = wanted

        val serviceEnabled = ShadeGuardService.isEnabled(this)
        guardStatusText.text = getString(
            when {
                !wanted -> R.string.settings_guard_status_off
                !serviceEnabled -> R.string.settings_guard_status_need_service
                else -> R.string.settings_guard_status_on
            }
        )
        // 只有“用户想用但系统里还没启用”时才需要这个按钮，其它情况下它是噪声
        guardGoButton.visibility = if (wanted && !serviceEnabled) View.VISIBLE else View.GONE
    }

    private fun setupGuardControls() {
        findViewById<TextView>(R.id.guardDiffText).text = joinLines(
            R.string.settings_guard_diff_off,
            R.string.settings_guard_diff_on,
            R.string.settings_guard_diff_same
        )
        findViewById<TextView>(R.id.guardCostText).text = joinLines(
            R.string.settings_guard_cost_warn,
            R.string.settings_guard_cost_restricted,
            R.string.settings_guard_cost_rom,
            R.string.settings_guard_cost_battery
        )
        guardSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setShadeGuardEnabled(this, checked)
            refreshGuardState()
        }
        guardGoButton.setOnClickListener { openAccessibilitySettings() }
    }

    /** 厂商路径拆成多条单行字符串，在此拼接，避免 strings.xml 里的裸换行被 aapt2 折叠成空格 */
    private fun vendorOverlayPaths(): String = joinLines(
        R.string.settings_overlay_path_aosp,
        R.string.settings_overlay_path_miui,
        R.string.settings_overlay_path_emui,
        R.string.settings_overlay_path_other
    )

    private fun joinLines(vararg resIds: Int): String =
        resIds.joinToString("\n") { getString(it) }

    /**
     * 跳系统无障碍设置页。带上 fragment arg key 能让原生系统直接定位到本服务那一项；
     * 定制系统不认这个 extra 时退化成普通列表页，用户自己找「抽屉守卫」。
     */
    private fun openAccessibilitySettings() {
        val target = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).putExtra(
            FRAGMENT_ARG_KEY,
            ComponentName(this, ShadeGuardService::class.java).flattenToString()
        )
        runCatching { startActivity(target) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
    }

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

        /**
         * 系统设置页用来定位并高亮某一项的 extra key。
         *
         * AOSP 里它就是 Settings.EXTRA_FRAGMENT_ARG_KEY，但那个常量标了 @hide，
         * 公开 SDK 里拿不到（直接引用会报 Unresolved reference），只能写字面值 ——
         * 与本项目此前踩过的 SCREEN_BRIGHTNESS_DEFAULT 同一类坑。
         */
        private const val FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    }
}
