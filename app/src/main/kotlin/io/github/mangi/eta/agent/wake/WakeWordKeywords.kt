package io.github.mangi.eta.agent.wake

import java.io.File

/**
 * [Eta-Fork] 唤醒词与灵敏度配置。
 *
 * 重要：wenetspeech KWS 3.3M 模型的建模单元是**拼音（声母 + 韵母）**，
 * 不是汉字，因此唤醒词必须写成拼音 token 串（音节内用空格分隔声母与韵母），
 * 例如「你好瓦力」= `n ǐ h ǎo w ǎ l ì`。
 *
 * 若 token 不在模型 tokens.txt 中，native 端会 Encode 失败并直接终止进程，
 * 所以 KwsEngine 会在启动前用 [filterSupported] 做一次严格校验。
 *
 * 需要调整唤醒词时只改本文件即可。
 */
internal object WakeWordKeywords {

    /** [Eta-Fork] 单个唤醒词定义。 */
    data class Keyword(
        /** 展示用文本。 */
        val text: String,
        /** 拼音 token 串；必须能在模型 tokens.txt 中全部找到。 */
        val tokens: String,
        /** 命中阈值参考值（当前仅作展示，实际阈值见 [THRESHOLD]）。 */
        val threshold: Float,
        /** 命中加权，默认 1.5f。 */
        val boost: Float = 1.5f,
    )

    /**
     * [Eta-Fork] 全局命中阈值。
     *
     * sherpa-onnx 的阈值是全局的（keywords 文件里只能逐条设置 boost），
     * 这里取 0.30 兼顾「你好瓦力」的灵敏度与「瓦力」的误触发。
     */
    const val THRESHOLD = 0.30f

    /** [Eta-Fork] 预设唤醒词：拼音 token 串 + 加权。 */
    val PRESETS: List<Keyword> = listOf(
        Keyword(text = "你好瓦力", tokens = "n ǐ h ǎo w ǎ l ì", threshold = 0.30f),
        Keyword(text = "瓦力", tokens = "w ǎ l ì", threshold = 0.40f),
    )

    /**
     * [Eta-Fork] 过滤出模型词表实际支持的唤醒词。
     *
     * 任一 token 不在 tokens.txt 中即整条丢弃，避免 native 端终止进程。
     */
    fun filterSupported(dir: File): List<Keyword> {
        val tokensFile = File(dir, "tokens.txt")
        if (!tokensFile.exists()) return emptyList()
        val known = HashSet<String>(2048)
        tokensFile.forEachLine { line ->
            val t = line.substringBeforeLast(' ', "").trim()
            if (t.isNotEmpty()) known.add(t)
        }
        return PRESETS.filter { keyword ->
            keyword.tokens.split(' ').filter { it.isNotEmpty() }.all { it in known }
        }
    }

    /** 生成 keywords.txt 内容：每行 `tokens :boost`。 */
    fun keywordsFileContent(keywords: List<Keyword>): String =
        keywords.joinToString("\n") { "${it.tokens} :${it.boost}" }
}
