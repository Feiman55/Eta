package io.github.mangi.eta.agent.wake

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File

/**
 * [Eta-Fork] sherpa-onnx 关键词识别引擎封装。
 *
 * 模型来自 assets/wake/kws（构建期由工作流下载并解压），首次使用复制到私有目录，
 * 之后直接用文件路径加载，避免 AssetManager 在子进程中的兼容问题。
 */
internal class KwsEngine(private val context: Context) {

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    /** 加载模型并创建流；失败返回 false。 */
    fun start(): Boolean {
        val dir = ensureModel() ?: return false
        return try {
            // [Eta-Fork] 自动发现模型文件，兼容不同模型包的文件命名（int8 / fp32）
            val encoder = findModel(dir, "encoder")
            val decoder = findModel(dir, "decoder")
            val joiner = findModel(dir, "joiner")
            val tokens = File(dir, "tokens.txt")

            if (encoder == null || decoder == null || joiner == null || !tokens.exists()) {
                AndroidAgentLogger.error("WakeWord: model files missing in ${dir.absolutePath}")
                return false
            }

            // [Eta-Fork] 严格校验唤醒词 token：任一 token 不在词表中，native 端会终止进程
            val supported = WakeWordKeywords.filterSupported(dir)
            if (supported.isEmpty()) {
                AndroidAgentLogger.error("WakeWord: no supported keyword in model vocab")
                return false
            }
            keywordsFile(dir).writeText(WakeWordKeywords.keywordsFileContent(supported))

            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoder!!.absolutePath,
                        decoder = decoder!!.absolutePath,
                        joiner = joiner!!.absolutePath,
                    ),
                    tokens = tokens.absolutePath,
                    modelType = "zipformer2",
                ),
                maxActivePaths = 4,
                keywordsFile = keywordsFile(dir).absolutePath,
                keywordsScore = 1.5f,
                // [Eta-Fork] 全局阈值，见 WakeWordKeywords.THRESHOLD
                keywordsThreshold = WakeWordKeywords.THRESHOLD,
                numTrailingBlanks = 2,
            )

            val created = KeywordSpotter(assetManager = null, config = config)
            spotter = created
            stream = created.createStream()
            AndroidAgentLogger.info("WakeWord: engine ready, keywords=${supported.joinToString { it.text }}")
            true
        } catch (t: Throwable) {
            AndroidAgentLogger.error("WakeWord: engine init failed", t)
            release()
            false
        }
    }

    /**
     * 送入一帧 16kHz 采样，命中唤醒词时返回关键词文本，否则返回 null。
     */
    fun accept(samples: FloatArray): String? {
        val engine = spotter ?: return null
        val s = stream ?: return null
        return try {
            s.acceptWaveform(samples, SAMPLE_RATE)
            while (engine.isReady(s)) {
                engine.decode(s)
                val result = engine.getResult(s)
                if (result.keyword.isNotEmpty()) {
                    engine.reset(s)
                    return result.keyword
                }
            }
            null
        } catch (t: Throwable) {
            AndroidAgentLogger.error("WakeWord: decode failed", t)
            null
        }
    }

    fun release() {
        runCatching { stream?.release() }
        runCatching { spotter?.release() }
        stream = null
        spotter = null
    }

    // ---- 模型准备 ----

    private fun ensureModel(): File? {
        val target = File(context.filesDir, MODEL_DIR)
        val ready = File(target, "tokens.txt").exists() && findModel(target, "encoder") != null
        if (ready) return target

        return try {
            target.mkdirs()
            copyAssets("wake/kws", target)
            target
        } catch (t: Throwable) {
            AndroidAgentLogger.error("WakeWord: copy model assets failed", t)
            null
        }
    }

    /** [Eta-Fork] 按前缀查找模型文件，优先 int8 量化版本。 */
    private fun findModel(dir: File, prefix: String): File? {
        val candidates = dir.listFiles { f ->
            f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".onnx")
        }.orEmpty()
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.name.contains("int8") } ?: candidates.first()
    }

    private fun keywordsFile(dir: File): File = File(dir, "keywords.txt")

    private fun copyAssets(assetPath: String, target: File) {
        val assets = context.assets
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            // 是文件
            val name = assetPath.substringAfterLast('/')
            assets.open(assetPath).use { input ->
                File(target, name).outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        for (child in children) {
            copyAssets("$assetPath/$child", target)
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000

        // [Eta-Fork] 模型目录：工作流解压 mobile 版模型到这里
        private const val MODEL_DIR = "wake_kws"
    }
}
