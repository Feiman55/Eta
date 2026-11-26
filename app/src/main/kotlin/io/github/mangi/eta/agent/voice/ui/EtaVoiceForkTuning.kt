package io.github.mangi.eta.agent.voice.ui

/**
 * [Eta-Fork] 本地定制参数集中定义。
 *
 * 所有 fork 侧可调数值只放在本文件，EtaVoicePanel.kt 中仅保留对常量的引用。
 * 这样上游重构面板代码时，rebase 只需重新贴回两处引用，冲突面最小；
 * 若后续不再需要定制，删除本文件并还原那两处引用即可整体回退。
 */
internal object EtaVoiceForkTuning {

    /**
     * [Eta-Fork] 浮层初始高度（dp）。上游默认为 304f。
     *
     * 调小可减少唤醒时对后方应用的遮挡；用户仍可上滑扩展到大高度。
     */
    const val BASE_HEIGHT_DP = 220f

    /**
     * [Eta-Fork] 面板本体不透明度（0f~1f，1f = 与上游一致的完全不透明）。
     *
     * 取 0.7f 即「透明度 0.3」，可透过面板看到后方画面。
     * 浅色与深色主题共用此系数：面板底色来自主题色 colors.content，
     * 相乘后自动适配两套主题，无需分别定义。
     */
    const val SHEET_ALPHA = 0.7f
}
