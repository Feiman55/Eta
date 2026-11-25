package io.github.mangi.eta.agent.voice

import android.app.Activity
import android.os.Bundle

/**
 * 供快捷设置开关使用的瞬时桥接 Activity。
 *
 * 为什么需要它：SystemUI 只在 tile 调用 `startActivityAndCollapse(...)` 时自动收起控制中心面板，
 * 直接调 `requestSession()` / `show()` 不会收起。本 Activity 使用透明主题、独立 taskAffinity，
 * 启动后立即执行唤醒并 finish，因此不会把 Eta 主任务栈带到前台，也不会留下界面。
 *
 * [EtaVoiceAssistActivity] 不能直接复用于此：它与 MainActivity 共用默认 taskAffinity，
 * 启动会把主任务栈提到前台。
 */
class EtaVoiceAssistBridgeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 首选 voice interaction 会话（与长按电源键一致），失败回退浮层，两者都不启动其它界面。
        if (!EtaVoiceInteractionService.requestSession()) {
            EtaAssistantOverlayService.show(this, null)
        }
        finish()
    }
}
