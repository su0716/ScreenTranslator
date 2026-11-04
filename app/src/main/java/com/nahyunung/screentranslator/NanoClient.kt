package com.nahyunung.screentranslator

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.flow.Flow

/**
 * [실험] 폰 안의 Gemini Nano로 번역 (인터넷·서버 대기 없음).
 * 글자 위치는 ML Kit이 찾고, 나노는 글자 번역만 함.
 *
 * 주의: 나노는 우리 앱이 "맨 앞 화면"일 때만 동작함 → NanoGate가 투명 화면을 잠깐 띄워서 해결 시도.
 */
object NanoClient {

    /** 앱이 뒤에 있어서 거부됨 (ErrorCode.BACKGROUND_USE_BLOCKED) */
    private const val BACKGROUND_USE_BLOCKED = 30

    private var model: GenerativeModel? = null

    private fun client(): GenerativeModel = model ?: Generation.getClient(
        generationConfig {
            modelConfig = modelConfig {
                releaseStage = ModelReleaseStage.STABLE
                preference = ModelPreference.FAST // 품질보다 속도
            }
        }
    ).also { model = it }

    /** FeatureStatus 값 (UNAVAILABLE / DOWNLOADABLE / DOWNLOADING / AVAILABLE) */
    suspend fun status(): Int = client().checkStatus()

    fun download(): Flow<DownloadStatus> = client().download()

    fun close() {
        runCatching { model?.close() }
        model = null
    }

    fun isBackgroundBlocked(e: Throwable): Boolean =
        e is GenAiException && e.errorCode == BACKGROUND_USE_BLOCKED

    /** 한 번에 보내는 양 (작은 모델이라 한 번에 너무 많이 주면 출력이 잘림) */
    private const val BATCH_ITEMS = 6
    private const val BATCH_CHARS = 400

    private fun batches(texts: List<String>): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        var cur = mutableListOf<Int>()
        var chars = 0
        for (i in texts.indices) {
            val len = texts[i].length
            if (cur.isNotEmpty() && (cur.size >= BATCH_ITEMS || chars + len > BATCH_CHARS)) {
                out += cur
                cur = mutableListOf()
                chars = 0
            }
            cur += i
            chars += len
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun prompt(lang: SourceLang, items: List<String>) = buildString {
        append("Translate each numbered ${lang.englishName} line into natural Korean.\n")
        append("They are text from one phone screen (game or app). Keep names consistent.\n")
        append("Answer with exactly one line per item in the form NUMBER|KOREAN, nothing else.\n\n")
        items.forEachIndexed { i, t -> append(i + 1).append('|').append(t.replace('\n', ' ')).append('\n') }
    }

    private val lineRegex = Regex("""^\s*\[?(\d+)\]?\s*[|:.)\-]\s*(.+?)\s*$""")

    /**
     * 글자들을 번역. 한 줄 완성될 때마다 onTranslated(원래 순번, 번역문).
     * 메인 스레드에서 호출해도 됨 (나노 호출은 내부에서 비동기 처리).
     */
    suspend fun translateLines(
        texts: List<String>,
        lang: SourceLang,
        onTranslated: (Int, String) -> Unit
    ): Int {
        var count = 0
        for (batch in batches(texts)) {
            val done = HashSet<Int>()
            val request = generateContentRequest(TextPart(prompt(lang, batch.map { texts[it] }))) {
                temperature = 0.1f
                topK = 1
            }
            val buf = StringBuilder()
            fun handleLine(line: String) {
                val m = lineRegex.find(line) ?: return
                val n = m.groupValues[1].toIntOrNull() ?: return
                val ko = m.groupValues[2].trim()
                val local = n - 1
                if (local !in batch.indices || ko.isEmpty() || !done.add(local)) return
                onTranslated(batch[local], ko)
                count++
            }
            client().generateContentStream(request).collect { chunk ->
                val t = chunk.candidates.firstOrNull()?.text ?: return@collect
                buf.append(t)
                while (true) {
                    val nl = buf.indexOf("\n")
                    if (nl < 0) break
                    handleLine(buf.substring(0, nl))
                    buf.delete(0, nl + 1)
                }
            }
            if (buf.isNotBlank()) handleLine(buf.toString())
        }
        return count
    }
}
