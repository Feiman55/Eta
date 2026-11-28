package io.github.mangi.eta.agent.wake

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlin.math.sqrt

/**
 * [Eta-Fork] 唤醒专用的极简录音封装：16kHz / 单声道 / PCM16。
 *
 * 只做采集与能量计算，不做重采样与降噪，尽量降低常驻监听的开销。
 */
internal class WakeWordAudio {

    private var record: AudioRecord? = null
    private var buffer = ShortArray(FRAME_SAMPLES)

    val frameSamples: Int get() = FRAME_SAMPLES
    val sampleRate: Int get() = KwsEngine.SAMPLE_RATE

    @SuppressLint("MissingPermission")
    fun open(): Boolean {
        close()
        return try {
            val minBuffer = AudioRecord.getMinBufferSize(
                KwsEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(FRAME_SAMPLES * 2 * 4)

            val created = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                KwsEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer,
            )
            if (created.state != AudioRecord.STATE_INITIALIZED) {
                AndroidAgentLogger.warn("WakeWord: AudioRecord init failed")
                created.release()
                return false
            }
            created.startRecording()
            record = created
            true
        } catch (t: Throwable) {
            AndroidAgentLogger.error("WakeWord: open audio failed", t)
            close()
            false
        }
    }

    /** 阻塞读取一帧，返回读到的采样数；小于 0 表示失败。 */
    fun readFrame(): Int {
        val r = record ?: return -1
        val n = r.read(buffer, 0, FRAME_SAMPLES)
        return if (n < 0) -1 else n
    }

    /** 把当前帧转换为 FloatArray（[-1,1]）。 */
    fun toFloatArray(count: Int): FloatArray {
        val out = FloatArray(count)
        for (i in 0 until count) {
            out[i] = buffer[i] / 32768f
        }
        return out
    }

    /** 当前帧 RMS，用于能量门控。 */
    fun rms(count: Int): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val v = buffer[i] / 32768.0
            sum += v * v
        }
        return sqrt(sum / count).toFloat()
    }

    fun close() {
        val r = record ?: return
        record = null
        runCatching { r.stop() }
        runCatching { r.release() }
    }

    companion object {
        /** [Eta-Fork] 100ms 一帧：16kHz * 0.1s */
        const val FRAME_SAMPLES = 1600

        /** [Eta-Fork] 能量门控阈值：低于该 RMS 直接跳过推理，是最主要的省电手段。 */
        const val ENERGY_GATE = 0.012f
    }
}
