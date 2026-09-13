package com.touchlock

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 下拉栏快捷磁贴：点一下即开始倒数锁定，与点桌面图标等价，
 * 但不必先回桌面再翻图标 —— 对"起身离开前锁一下"这个动作更顺手。
 *
 * 两条路径（锁定、未授权时跳授权页）都通过 [startActivityFromTile] 拉起 Activity，
 * 因此点完磁贴下拉面板会自动收起；面板若继续挂在锁屏之上，看上去就像锁没生效。
 *
 * 未授予悬浮窗权限时锁定不可能生效，所以这里退化为跳系统授权页，
 * 而不是静默拉起一个什么都拦不住的锁屏。
 */
class LockTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // 磁贴是一次性动作而不是开关，锁定与否不回显，因此恒为未激活态
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_label)
            updateTile()
        }
    }

    override fun onClick() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlayPermission()
            return
        }
        startLock()
    }

    /**
     * 走"拉起 [MainActivity]"而不是直接 startForegroundService，有两个理由：
     * 1. 只有 startActivityAndCollapse 会让系统收起下拉面板，直接启动服务不会收起。
     * 2. MainActivity 在前台启动服务，天然规避 Android 12+ 的后台启动前台服务限制
     *    （磁贴点击是否算豁免各 ROM 并不一致，直接启动可能抛异常）。
     *
     * MainActivity 已授权分支在 onCreate 里启动服务后立即 finish，且没走 setContentView，
     * 因此不会画出任何窗口，用户只看到面板收起、屏幕锁定。
     * （极端情况下权限在这两步之间被关掉，MainActivity 会显示引导页 —— 反而是合理结果。）
     */
    private fun startLock() {
        if (startActivityFromTile(Intent(this, MainActivity::class.java), REQ_LOCK)) return
        // 兜底：Activity 没拉起来时仍直接启动服务，面板不收起也总好于点了没反应
        runCatching {
            startForegroundService(
                Intent(this, TouchLockService::class.java)
                    .putExtra(TouchLockService.EXTRA_DELAY_SECONDS, Prefs.delaySeconds(this))
            )
        }
    }

    private fun openOverlayPermission() {
        startActivityFromTile(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ),
            REQ_PERMISSION
        )
    }

    /**
     * 从磁贴拉起 Activity 必须用 startActivityAndCollapse：先收起下拉面板再跳转。
     * API 29 起 Intent 重载被标记废弃、改用 PendingIntent 重载，而本项目 minSdk 26，
     * 两个重载都要覆盖，故按版本分支。
     *
     * 返回是否成功启动，而不是把失败静默吞掉：调用方还要据此决定要不要走兜底路径。
     * 方法名与父类的 startActivityAndCollapse 重载故意错开，避免读起来像递归。
     */
    private fun startActivityFromTile(intent: Intent, requestCode: Int): Boolean =
        runCatching {
            val flagged = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val request =
                    PendingIntent.getActivity(applicationContext, requestCode, flagged, flags)
                startActivityAndCollapse(request)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(flagged)
            }
            true
        }.getOrDefault(false)

    private companion object {
        // 两条路径的 Intent 组件不同，本不会互相覆盖；分开 requestCode 只为读起来更明确
        const val REQ_LOCK = 3
        const val REQ_PERMISSION = 4
    }
}
