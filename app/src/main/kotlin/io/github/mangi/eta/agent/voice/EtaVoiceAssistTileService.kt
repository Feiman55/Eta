package io.github.mangi.eta.agent.voice

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 快捷设置开关：点击唤醒 Eta 语音助理浮层，行为对齐长按电源键。
 *
 * 关键设计：
 * - 通过 [startActivityAndCollapse] 启动透明的 [EtaVoiceAssistBridgeActivity]，
 *   由系统收起控制中心面板；该 Activity 带独立 taskAffinity，不会把主界面带到前台。
 * - 不声明独立进程，跟随主进程：SystemUI 枚举可用 tile 时需要能绑定到该服务。
 * - 80ms 防抖，拦截面板内快速连点。
 * - 无常驻开销：只在面板打开或点击时被系统绑定。
 */
class EtaVoiceAssistTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // 一次性触发器，不表示持久状态。
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.eta_voice_tile_label)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val now = SystemClock.elapsedRealtime()
        if (now - lastInvokeAt < INVOKE_DEBOUNCE_MS) {
            AndroidAgentLogger.info("Voice tile: duplicate click ignored")
            return
        }
        lastInvokeAt = now

        runCatching { startActivityAndCollapse(bridgePendingIntent()) }
            .onFailure { AndroidAgentLogger.error("Voice tile: launch failed", it) }
    }

    private fun bridgePendingIntent(): PendingIntent {
        val intent = Intent(this, EtaVoiceAssistBridgeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().apply {
            // 从 SystemUI 触发启动 Activity 属于后台启动，Android 14+ 需显式放行。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                pendingIntentCreatorBackgroundActivityStartMode =
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
        }.toBundle()
        return PendingIntent.getActivity(
            this,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            options,
        )
    }

    private companion object {
        /** 连点防抖窗口：只拦截面板内的极快重复点击。 */
        const val INVOKE_DEBOUNCE_MS = 80L
        const val REQUEST_CODE = 0x7E7A

        @Volatile
        private var lastInvokeAt = 0L
    }
}
