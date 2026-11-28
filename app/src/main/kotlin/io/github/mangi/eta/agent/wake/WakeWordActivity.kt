package io.github.mangi.eta.agent.wake

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.github.mangi.eta.R

/**
 * [Eta-Fork] 语音唤醒设置页。
 *
 * 刻意使用传统 View 而不是 Compose：本页只做开关与说明，
 * 避免引入主题/依赖耦合，也便于整个目录删除时零残留。
 */
class WakeWordActivity : Activity() {

    private lateinit var toggle: Switch
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        // [Eta-Fork] 用确定存在的系统主题，避免引用不存在的 DayNight_NoActionBar 变体
        setTheme(android.R.style.Theme_DeviceDefault_NoActionBar)
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.wake_word_title)
            textSize = 22f
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.wake_word_description)
            textSize = 14f
            setPadding(0, dp(12), 0, dp(20))
        })

        toggle = Switch(this).apply {
            text = getString(R.string.wake_word_toggle)
            isChecked = WakeWordSettings.isEnabled(this@WakeWordActivity)
            setOnCheckedChangeListener { _, checked -> onToggle(checked) }
        }
        root.addView(toggle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        status = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(10), 0, dp(18))
            gravity = Gravity.START
        }
        root.addView(status)

        root.addView(TextView(this).apply {
            text = getString(R.string.wake_word_presets_title)
            textSize = 16f
        })

        for (keyword in WakeWordKeywords.PRESETS) {
            root.addView(TextView(this).apply {
                text = getString(
                    R.string.wake_word_preset_item,
                    keyword.text,
                    WakeWordKeywords.THRESHOLD.toString(),
                )
                textSize = 14f
                setPadding(0, dp(8), 0, 0)
            })
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.wake_word_note)
            textSize = 12f
            setPadding(0, dp(20), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
        refreshStatus()
    }

    private fun onToggle(enabled: Boolean) {
        if (enabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        applyToggle(enabled)
    }

    private fun applyToggle(enabled: Boolean) {
        WakeWordSettings.setEnabled(this, enabled)
        if (enabled) {
            runCatching { WakeWordService.start(this) }
                .onFailure { status.text = it.message }
        } else {
            runCatching { WakeWordService.stop(this) }
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        status.text = getString(
            if (WakeWordSettings.isEnabled(this)) {
                R.string.wake_word_status_on
            } else {
                R.string.wake_word_status_off
            },
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            toggle.isChecked = granted
            if (granted) applyToggle(true) else refreshStatus()
        }
    }

    private companion object {
        const val REQ_AUDIO = 4201
    }
}
