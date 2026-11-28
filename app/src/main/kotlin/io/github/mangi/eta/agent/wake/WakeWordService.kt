package io.github.mangi.eta.agent.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.agent.voice.EtaVoiceInteractionService
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [Eta-Fork] 语音唤醒前台服务。
 *
 * 生命周期与设置页总开关严格绑定：开关打开时启动，关闭时停止并释放模型。
 *
 * 运行在 `:voice` 进程，与 EtaVoiceInteractionService 同进程，
 * 因此命中后可以直接走 requestSession()（与长按电源键同一链路），
 * 失败再回退到浮层。
 */
class WakeWordService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var engine: KwsEngine? = null
    private var audio: WakeWordAudio? = null
    private var overlayReceiver: BroadcastReceiver? = null

    @Volatile
    private var paused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                WakeWordSettings.setEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForegroundCompat()
        if (loopJob == null) startLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        loopJob = null
        unregisterOverlayReceiver()
        runCatching { audio?.close() }
        audio = null
        runCatching { engine?.release() }
        engine = null
        scope.cancel()
        AndroidAgentLogger.info("WakeWord: service stopped")
        super.onDestroy()
    }

    // ---- 监听循环 ----

    private fun startLoop() {
        loopJob = scope.launch {
            AndroidAgentLogger.info("WakeWord: starting engine")
            val kws = KwsEngine(this@WakeWordService)
            if (!kws.start()) {
                AndroidAgentLogger.error("WakeWord: engine start failed, stop service")
                stopSelf()
                return@launch
            }
            engine = kws

            val rec = WakeWordAudio()
            if (!rec.open()) {
                AndroidAgentLogger.error("WakeWord: audio open failed, stop service")
                stopSelf()
                return@launch
            }
            audio = rec

            var audioOpened = true
            try {
                while (isActive) {
                    // [Eta-Fork] 唤醒后暂停：释放麦克风让给 ASR，等浮层关闭再恢复
                    if (paused) {
                        if (audioOpened) {
                            rec.close()
                            audioOpened = false
                        }
                        delay(PAUSE_POLL_MS)
                        continue
                    }
                    if (!audioOpened) {
                        if (!rec.open()) {
                            delay(PAUSE_POLL_MS)
                            continue
                        }
                        audioOpened = true
                    }

                    val n = rec.readFrame()
                    if (n <= 0) {
                        delay(20)
                        continue
                    }

                    // [Eta-Fork] 能量门控：静音帧不推理，显著降低 CPU 占用
                    if (rec.rms(n) < WakeWordAudio.ENERGY_GATE) continue

                    val keyword = kws.accept(rec.toFloatArray(n))
                    if (keyword != null) {
                        AndroidAgentLogger.info("WakeWord: hit '$keyword'")
                        onWakeWord(keyword)
                    }
                }
            } finally {
                runCatching { rec.close() }
            }
        }
    }

    // ---- 唤醒分发 ----

    private fun onWakeWord(keyword: String) {
        paused = true

        // 与快捷开关完全同一链路：优先 voice interaction 会话，失败回退浮层
        val sessionStarted = runCatching { EtaVoiceInteractionService.requestSession() }
            .getOrElse {
                AndroidAgentLogger.error("WakeWord: requestSession failed", it)
                false
            }

        if (!sessionStarted) {
            runCatching { EtaAssistantOverlayService.show(this, null) }
                .onFailure { AndroidAgentLogger.error("WakeWord: overlay show failed", it) }
        }

        listenOverlayClosed()
    }

    /**
     * [Eta-Fork] 浮层关闭后恢复监听；60 秒兜底防止广播丢失导致永久暂停。
     */
    private fun listenOverlayClosed() {
        unregisterOverlayReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_OVERLAY_CLOSED) {
                    resumeListening("overlay-closed")
                }
            }
        }
        overlayReceiver = receiver
        registerReceiver(receiver, IntentFilter(ACTION_OVERLAY_CLOSED), RECEIVER_NOT_EXPORTED)

        scope.launch {
            withTimeoutOrNull(RESUME_FALLBACK_MS) {
                while (isActive) {
                    if (!paused) return@withTimeoutOrNull
                    delay(1_000)
                }
            }
            if (paused) resumeListening("timeout")
        }
    }

    private fun resumeListening(reason: String) {
        if (!paused) return
        paused = false
        unregisterOverlayReceiver()
        AndroidAgentLogger.info("WakeWord: resume listening ($reason)")
    }

    private fun unregisterOverlayReceiver() {
        overlayReceiver?.let { runCatching { unregisterReceiver(it) } }
        overlayReceiver = null
    }

    // ---- 通知 ----

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.wake_word_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wake_word)
            .setContentTitle(getString(R.string.wake_word_notification_title))
            .setContentText(getString(R.string.wake_word_notification_text))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_wake_word, getString(R.string.wake_word_action_stop), stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "io.github.mangi.eta.action.STOP_WAKE_WORD"

        /** [Eta-Fork] 浮层关闭广播，由 EtaAssistantOverlayService 在退出路径发出。 */
        const val ACTION_OVERLAY_CLOSED = "io.github.mangi.eta.action.VOICE_OVERLAY_CLOSED"

        private const val CHANNEL_ID = "eta_wake_word"
        private const val NOTIFICATION_ID = 4219
        private const val RESUME_FALLBACK_MS = 60_000L
        private const val PAUSE_POLL_MS = 500L

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            // [Eta-Fork] 用 stopService 而不是带 ACTION 的 startService，
            // 避免应用处于后台时再次启动服务触发后台启动限制。
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
