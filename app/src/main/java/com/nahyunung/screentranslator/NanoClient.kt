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
import kotlinx.coroutines.withTimeout

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

    /** true면 FAST 모델 설정, false면 기본 설정 (기기마다 FAST가 없을 수 있어서 기본값 사용) */
    var useFast = false

    private fun client(): GenerativeModel = model ?: (
        if (useFast) Generation.getClient(
            generationConfig {
                modelConfig = modelConfig {
                    releaseStage = ModelReleaseStage.STABLE
                    preference = ModelPreference.FAST
                }
            }
        ) else Generation.getClient()
        ).also { model = it }

    /** 모델을 메모리에 미리 올림 (첫 호출이 느린 걸 줄임) */
    suspend fun warmup() = client().warmup()

    /** 스트리밍 없이 한 번에 받기 (진단용) */
    suspend fun generateOnce(prompt: String): String {
        val r = client().generateContent(generateContentRequest(TextPart(prompt)) { temperature = 0.1f })
        return r.candidates.firstOrNull()?.text ?: ""
    }

    /** 에러를 사람이 읽을 수 있게: 클래스 + 에러코드 + 메시지 */
    fun describe(e: Throwable): String {
        val code = (e as? GenAiException)?.errorCode?.let { " 코드 $it" } ?: ""
        return "${e.javaClass.simpleName}$code: ${e.message ?: "-"}"
    }

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
        stream: Boolean = true,
        batchTimeoutMs: Long = 25_000,
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
            // 한 묶음이 너무 오래 걸리면 포기 (멈춤 방지)
            withTimeout(batchTimeoutMs) {
                if (stream) {
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
                } else {
                    buf.append(client().generateContent(request).candidates.firstOrNull()?.text ?: "")
                    buf.split("\n").forEach { handleLine(it) }
                    buf.clear()
                }
            }
            if (buf.isNotBlank()) handleLine(buf.toString())
        }
        return count
    }
}
