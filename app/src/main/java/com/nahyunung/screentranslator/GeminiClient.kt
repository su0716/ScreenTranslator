package com.nahyunung.screentranslator

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/**
 * 제미나이 번역.
 * - 정밀 모드: 화면 캡처를 통째로 보내서 글자 위치(box_2d) + 번역을 받음 (세로쓰기 OK)
 * - 빠른 모드: 폰에서 찾은 글자만 보내서 번역만 받음 (이미지 처리 없음 → 빠름)
 * - 둘 다 스트리밍: 결과가 하나 완성될 때마다 바로 콜백 → 첫 결과가 빨리 뜸
 * (네트워크 호출이라 반드시 백그라운드 스레드에서 호출)
 */
object GeminiClient {

    class GeminiException(
        message: String,
        val retryable: Boolean = false,
        val code: Int = 0,
        val badThinkConfig: Boolean = false,
        val blocked: Boolean = false,
        val cancelled: Boolean = false
    ) : Exception(message)

    /** 중지 버튼용: 진행 중인 연결을 끊어서 바로 멈춤 */
    class CancelToken {
        @Volatile var cancelled = false
            private set
        @Volatile internal var conn: HttpURLConnection? = null

        fun cancel() {
            cancelled = true
            val c = conn ?: return
            // 연결 끊기는 메인 스레드에서 하면 안 돼서 별도 스레드로
            Thread { runCatching { c.disconnect() } }.start()
        }

        internal fun check() {
            if (cancelled) throw GeminiException("중지됨", cancelled = true)
        }

        /** 중지되면 바로 깨는 sleep */
        internal fun sleep(ms: Long) {
            var left = ms
            while (left > 0) {
                check()
                Thread.sleep(minOf(100L, left))
                left -= 100
            }
            check()
        }
    }

    /** 필터 차단으로 답이 끊긴 경우의 finishReason 값들 */
    private val BLOCK_REASONS = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "IMAGE_SAFETY")

    /** 붐비거나 느릴 때 차례로 시도할 예비 모델 (빠른 순) */
    private val FALLBACK_MODELS = listOf("gemini-flash-lite-latest", "gemini-2.5-flash-lite", "gemini-2.5-flash")

    private const val HOST = "https://generativelanguage.googleapis.com"

    // ---------------- 프롬프트 ----------------

    // [정밀 모드] 이미지째 보냄. 원문은 받지 않고 번역만 받음 → 응답 길이 절반
    private fun imagePrompt(lang: SourceLang) = """
        Translate this phone screenshot into Korean.
        Find every piece of foreign (non-Korean) text, mainly ${lang.englishName}: UI labels, paragraphs,
        speech bubbles and VERTICAL text (read vertical Japanese/Chinese columns top-to-bottom, right-to-left).
        Group text into natural units: one speech bubble, one paragraph, one button/label, one list item.
        Do not merge separate bubbles or separate UI elements.
        Order units from top to bottom.
        For each unit return:
          b: [ymin, xmin, ymax, xmax] tightly around the original text, normalized to 0-1000
          k: natural, fluent Korean translation using the whole screen as context
             (keep names consistent; for game/manga dialogue keep the tone and speech style)
        Skip text that is already Korean, and skip lone numbers/symbols.
        Return only a JSON array. Return [] if there is nothing to translate.
    """.trimIndent()

    // [빠른 모드] 폰에서 찾은 글자만 보냄 → 이미지 처리·좌표 출력이 없어서 빠름
    private fun textPrompt(lang: SourceLang, texts: List<String>) = """
        Translate each item of the JSON array below into natural, fluent Korean.
        The items are text blocks read by OCR from one phone screen (mainly ${lang.englishName}),
        in reading order. Use all of them together as context: keep names consistent and keep the
        tone and speech style of game/chat dialogue. OCR may contain small mistakes; translate
        what was most likely meant. If an item is already Korean or only numbers/symbols, return it unchanged.
        Return a JSON array with one object per item, in the same order:
          i: the item's index (0-based), k: the Korean translation

        Items:
        ${JSONArray(texts)}
    """.trimIndent()

    // ---------------- 요청 본문 ----------------

    /**
     * "생각(thinking)" 끄는 방식이 모델 세대마다 달라서 순서대로 시도.
     * 3.x: thinkingLevel, 2.5: thinkingBudget=0, 그 외: 설정 안 함
     */
    private enum class Think { LEVEL_MINIMAL, LEVEL_LOW, BUDGET_0, NONE }

    /** 모델별로 성공한 방식 기억 (앱 켜져 있는 동안) */
    private val thinkCache = java.util.concurrent.ConcurrentHashMap<String, Think>()

    private fun thinkOrder(model: String): List<Think> {
        thinkCache[model]?.let { return listOf(it) + Think.values().filter { t -> t != it } }
        return if (model.contains("2.5") || model.contains("2.0")) listOf(Think.BUDGET_0, Think.NONE)
        else listOf(Think.LEVEL_MINIMAL, Think.LEVEL_LOW, Think.BUDGET_0, Think.NONE)
    }

    /** 필터 완화 시 기본 4개 분류를 차단 안 함 (구글의 핵심 차단은 그대로 유지됨) */
    private fun safetySettings(relaxed: Boolean): JSONArray? {
        if (!relaxed) return null
        val arr = JSONArray()
        listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT"
        ).forEach { arr.put(JSONObject().put("category", it).put("threshold", "BLOCK_NONE")) }
        return arr
    }

    private fun prop(type: String) = JSONObject().put("type", type)

    private fun body(parts: JSONArray, itemProps: JSONObject, order: List<String>, think: Think, relaxed: Boolean): String {
        val itemSchema = JSONObject()
            .put("type", "OBJECT")
            .put("properties", itemProps)
            .put("required", JSONArray(order))
            .put("propertyOrdering", JSONArray(order))
        val gen = JSONObject()
            .put("temperature", 0.2)
            .put("responseMimeType", "application/json")
            .put("responseSchema", JSONObject().put("type", "ARRAY").put("items", itemSchema))
        when (think) {
            Think.LEVEL_MINIMAL -> gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
            Think.LEVEL_LOW -> gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            Think.BUDGET_0 -> gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            Think.NONE -> Unit
        }
        val root = JSONObject()
        safetySettings(relaxed)?.let { root.put("safetySettings", it) }
        return root
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", gen)
            .toString()
    }

    /** 긴 변 1600px로 줄임: 이미지 토큰이 줄어서 빨라짐 (글자는 충분히 읽힘) */
    private fun toJpegBase64(src: Bitmap): String {
        val maxSide = 1600
        val longSide = maxOf(src.width, src.height)
        val bmp = if (longSide > maxSide) {
            val s = maxSide.toFloat() / longSide
            Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true)
        } else src
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            if (bmp !== src) bmp.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }

    // ---------------- 공개 함수 ----------------

    /**
     * [정밀 모드] 이미지째 번역. 박스가 하나 완성될 때마다 onItem 호출.
     * @param target 이 이미지가 화면에서 차지하는 영역 (영역 선택 시 그 부분, 아니면 전체 화면)
     * @return 받은 박스 개수 (콜백은 백그라운드 스레드에서 호출됨)
     */
    fun translateImage(
        bitmap: Bitmap,
        lang: SourceLang,
        apiKey: String,
        model: String,
        target: Rect,
        relaxedSafety: Boolean,
        cancel: CancelToken = CancelToken(),
        onStatus: (String) -> Unit = {},
        onItem: (OverlayItem) -> Unit
    ): Int {
        val jpeg = toJpegBase64(bitmap)
        val parts = JSONArray()
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", jpeg)))
            .put(JSONObject().put("text", imagePrompt(lang)))
        val props = JSONObject()
            .put("b", prop("ARRAY").put("items", prop("INTEGER")))
            .put("k", prop("STRING"))
        return runWithFallback(model, apiKey, cancel, onStatus,
            bodyFor = { think -> body(parts, props, listOf("b", "k"), think, relaxedSafety) }
        ) { obj -> toItem(obj, target)?.also(onItem) != null }
    }

    /**
     * [빠른 모드] 글자만 번역. 번역이 하나 완성될 때마다 onTranslated(순번, 번역문) 호출.
     * @return 받은 번역 개수 (콜백은 백그라운드 스레드에서 호출됨)
     */
    fun translateTexts(
        texts: List<String>,
        lang: SourceLang,
        apiKey: String,
        model: String,
        relaxedSafety: Boolean,
        cancel: CancelToken = CancelToken(),
        onStatus: (String) -> Unit = {},
        onTranslated: (Int, String) -> Unit
    ): Int {
        val parts = JSONArray().put(JSONObject().put("text", textPrompt(lang, texts)))
        val props = JSONObject().put("i", prop("INTEGER")).put("k", prop("STRING"))
        val seen = HashSet<Int>()
        return runWithFallback(model, apiKey, cancel, onStatus,
            bodyFor = { think -> body(parts, props, listOf("i", "k"), think, relaxedSafety) }
        ) { obj ->
            val i = obj.optInt("i", -1)
            val k = obj.optString("k").trim()
            if (i in texts.indices && k.isNotEmpty() && seen.add(i)) {
                onTranslated(i, k)
                true
            } else false
        }
    }

    /**
     * 버튼 누르는 순간 구글 서버와 연결(보안 연결 포함)을 미리 맺어 둠.
     * 이후 실제 요청이 이 연결을 재사용해서 0.2~0.5초 절약. 실패해도 상관없음.
     */
    fun warmUp(apiKey: String) {
        runCatching {
            val conn = (URL("$HOST/v1beta/models?pageSize=1").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("x-goog-api-key", apiKey.trim())
            }
            // 응답을 끝까지 읽고 닫아야 연결이 재사용 풀에 들어감
            (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes() }
        }
    }

    // ---------------- 재시도 / 우회 ----------------

    /**
     * 붐비거나 느리면 → 같은 모델 1번 재시도(503만) → 예비 모델로 우회.
     * @param onObject 결과 객체 하나 처리. 화면에 내보냈으면 true.
     */
    private fun runWithFallback(
        model: String,
        apiKey: String,
        cancel: CancelToken,
        onStatus: (String) -> Unit,
        bodyFor: (Think) -> String,
        onObject: (JSONObject) -> Boolean
    ): Int {
        val models = (listOf(model.trim().removePrefix("models/")) + FALLBACK_MODELS).distinct()
        var lastError: GeminiException? = null

        for ((mi, m) in models.withIndex()) {
            if (mi > 0) onStatus("우회중")
            var busyRetried = false
            val order = thinkOrder(m)
            var ti = 0
            while (ti < order.size) {
                val think = order[ti]
                var delivered = 0
                cancel.check()
                try {
                    stream(bodyFor(think), m, apiKey, cancel) { if (onObject(it)) delivered++ }
                    thinkCache[m] = think
                    return delivered
                } catch (e: GeminiException) {
                    // 이미 결과를 일부 보여줬으면 다른 모델로 다시 하지 않고 거기서 끝
                    if (delivered > 0) return delivered
                    lastError = e
                    when {
                        e.cancelled -> throw e
                        e.blocked -> throw e // 필터 차단은 다른 모델도 똑같이 막힘
                        e.badThinkConfig -> ti++ // 이 모델이 그 thinking 설정을 모름 → 다음 방식
                        e.code == 503 && !busyRetried -> { // 붐빔은 같은 모델로 한 번만 더
                            busyRetried = true
                            onStatus("재시도")
                            cancel.sleep(1500)
                        }
                        e.retryable -> break // 다음 모델로
                        else -> throw e
                    }
                }
            }
        }
        throw lastError ?: GeminiException("제미나이 오류")
    }

    /** SSE 스트리밍으로 받으면서 완성된 JSON 객체마다 onObject 호출 */
    private fun stream(
        body: String,
        model: String,
        apiKey: String,
        cancel: CancelToken,
        onObject: (JSONObject) -> Unit
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val modelId = URLEncoder.encode(model, "UTF-8")
        val url = URL("$HOST/v1beta/models/$modelId:streamGenerateContent?alt=sse")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000 // 30초 동안 아무것도 안 오면 포기하고 다음 모델로
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey.trim())
            // 업로드가 멈춰도 타임아웃이 걸리도록 길이를 미리 지정
            setFixedLengthStreamingMode(bytes.size)
        }
        cancel.conn = conn

        try {
            cancel.check()
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val resp = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                throw httpError(code, resp, model)
            }

            val extractor = JsonArrayStreamer(onObject)
            var gotAnyText = false
            var blockReason: String? = null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    cancel.check()
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val json = line.substring(5).trim()
                    if (json.isEmpty()) continue
                    val root = JSONObject(json)
                    root.optJSONObject("error")?.let {
                        throw httpError(it.optInt("code", 500), json, model)
                    }
                    root.optJSONObject("promptFeedback")?.optString("blockReason")
                        ?.takeIf { it.isNotEmpty() }?.let { blockReason = it }
                    val cand = root.optJSONArray("candidates")?.optJSONObject(0)
                    cand?.optString("finishReason")?.takeIf { it in BLOCK_REASONS }?.let { blockReason = it }
                    val parts = cand?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val p = parts.getJSONObject(i)
                        if (p.optBoolean("thought")) continue
                        val t = p.optString("text")
                        if (t.isNotEmpty()) {
                            gotAnyText = true
                            extractor.feed(t)
                        }
                    }
                }
            }
            blockReason?.let { r ->
                // 일부 결과는 이미 나갔을 수도 있음 → 호출한 쪽에서 delivered로 처리
                throw GeminiException(
                    "AI 안전 필터에 걸려서 번역 못 함 ($r)" +
                        if (r == "SAFETY") " — 앱 설정의 '필터 완화'를 켜봐" else "",
                    blocked = true
                )
            }
            if (!gotAnyText) throw GeminiException("응답이 비었어", retryable = true, code = -2)
        } catch (e: SocketTimeoutException) {
            cancel.check()
            throw GeminiException("응답이 너무 느려 (30초 초과)", retryable = true, code = -1)
        } catch (e: java.io.IOException) {
            // 중지 버튼으로 연결을 끊은 경우
            cancel.check()
            throw GeminiException("연결이 끊겼어 (인터넷 확인): ${e.message}", retryable = true, code = -3)
        } finally {
            cancel.conn = null
            conn.disconnect()
        }
    }

    private fun httpError(code: Int, resp: String, model: String): GeminiException {
        val apiMsg = runCatching {
            JSONObject(resp).getJSONObject("error").getString("message")
        }.getOrDefault(resp.take(200))
        val thinkErr = code == 400 && apiMsg.contains("think", ignoreCase = true)
        return GeminiException(
            when (code) {
                400, 401, 403 -> "API 키나 모델 이름 확인해줘 ($code): $apiMsg"
                404 -> "모델($model)을 못 찾았어 ($code)"
                429 -> "무료 사용량 초과. 잠깐 뒤에 다시 (429)"
                500, 502, 503, 504 -> "구글 서버가 붐벼. 잠깐 뒤에 다시 눌러봐 ($code)"
                else -> "제미나이 오류 ($code): $apiMsg"
            },
            // 서버 혼잡·일시 오류·모델 없음·모델별 한도 초과는 다른 모델로 넘어감
            retryable = code in listOf(404, 429, 500, 502, 503, 504),
            code = code,
            badThinkConfig = thinkErr
        )
    }

    /** box_2d(0~1000, target 기준) → 화면 좌표 */
    private fun toItem(o: JSONObject, target: Rect): OverlayItem? {
        val ko = o.optString("k").trim()
        val b = o.optJSONArray("b") ?: return null
        if (ko.isEmpty() || b.length() < 4) return null
        val ymin = b.optInt(0).coerceIn(0, 1000)
        val xmin = b.optInt(1).coerceIn(0, 1000)
        val ymax = b.optInt(2).coerceIn(0, 1000)
        val xmax = b.optInt(3).coerceIn(0, 1000)
        if (xmax <= xmin || ymax <= ymin) return null
        val w = target.width()
        val h = target.height()
        return OverlayItem(
            rect = Rect(
                target.left + xmin * w / 1000, target.top + ymin * h / 1000,
                target.left + xmax * w / 1000, target.top + ymax * h / 1000
            ),
            text = ko,
            lineCount = 1
        )
    }

    /**
     * 조각조각 들어오는 JSON 배열 텍스트에서
     * 배열 바로 아래 객체 { ... } 가 완성될 때마다 꺼내 줌.
     */
    private class JsonArrayStreamer(private val onObject: (JSONObject) -> Unit) {
        private val buf = StringBuilder()
        private var pos = 0
        private var depth = 0
        private var inString = false
        private var escape = false
        private var objStart = -1

        fun feed(chunk: String) {
            buf.append(chunk)
            while (pos < buf.length) {
                val c = buf[pos]
                if (inString) {
                    when {
                        escape -> escape = false
                        c == '\\' -> escape = true
                        c == '"' -> inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '[', '{' -> {
                            if (c == '{' && depth == 1) objStart = pos
                            depth++
                        }
                        ']', '}' -> {
                            depth--
                            if (c == '}' && depth == 1 && objStart >= 0) {
                                runCatching { JSONObject(buf.substring(objStart, pos + 1)) }
                                    .getOrNull()?.let(onObject)
                                objStart = -1
                            }
                        }
                    }
                }
                pos++
            }
        }
    }
}
