package io.github.mangi.eta.agent.wake

import android.content.Context

/**
 * [Eta-Fork] 语音唤醒的持久化配置。
 *
 * 单独用 SharedPreferences，避免改动上游的 DataStore / Settings 数据模型，
 * 便于整体删除本目录时不留残留。
 */
internal object WakeWordSettings {

    private const val FILE = "eta_wake_word"
    private const val KEY_ENABLED = "enabled"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
