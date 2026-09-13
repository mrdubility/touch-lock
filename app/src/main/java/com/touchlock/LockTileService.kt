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
     * Android 12+ 限制后台启动前台服务，磁贴点击是否算豁免各 ROM 并不一致，
     * 一旦不被豁免会抛 ForegroundServiceStartNotAllowedException。
     * 因此失败时退回"拉起 [MainActivity] 由它在前台启动服务"这条一定合法的路径，
     * 代价只是一闪而过的 Activity，总好于磁贴点了没反应或直接崩溃。
     */
    private fun startLock() {
        val intent = Intent(this, TouchLockService::class.java)
            .putExtra(TouchLockService.EXTRA_DELAY_SECONDS, Prefs.delaySeconds(this))
        runCatching { startForegroundService(intent) }.onFailure { startLockViaActivity() }
    }

    /**
     * 兜底路径：拉起 [MainActivity]，由它在前台启动服务。
     * MainActivity 自己会从 [Prefs] 读秒数，无需额外传参。
     */
    private fun startLockViaActivity() {
        startActivityAndCollapseActivity(Intent(this, MainActivity::class.java))
    }

    private fun openOverlayPermission() {
        startActivityAndCollapseActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    /**
     * 从磁贴拉起 Activity 必须用 startActivityAndCollapse：先收起下拉面板再跳转。
     * API 29 起 Intent 重载被标记废弃、改用 PendingIntent 重载，
     * 低版本仍需用旧重载，故按版本分支。
     */
    private fun startActivityAndCollapseActivity(intent: Intent) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val request = PendingIntent.getActivity(
                    applicationContext,
                    REQ_COLLAPSE,
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    flags
                )
                startActivityAndCollapse(request)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private companion object {
        const val REQ_COLLAPSE = 3
    }
}
