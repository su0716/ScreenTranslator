package com.nahyunung.screentranslator

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 제미나이에 화면 캡처를 통째로 보내서
 * 글자 위치(box_2d) + 원문 + 한국어 번역을 한 번에 받아옴.
 * 세로쓰기·말풍선도 이미지로 직접 읽어서 ML Kit보다 훨씬 정확함.
 * (네트워크 호출이라 반드시 백그라운드 스레드에서 호출)
 */
object GeminiClient {

    class GeminiException(message: String, val retryable: Boolean = false) : Exception(message)

    /** 붐빌 때(503 등) 차례로 시도할 예비 모델 */
    private val FALLBACK_MODELS = listOf("gemini-flash-lite-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite")

    private fun prompt(lang: SourceLang) = """
        This is a screenshot of a phone screen. Find every piece of foreign (non-Korean) text on it,
        mainly ${lang.englishName}, including UI labels, paragraphs, speech bubbles and VERTICAL text
        (read vertical Japanese/Chinese columns top-to-bottom, right-to-left).
        Group text into natural units: one speech bubble, one paragraph, one button/label, one list item.
        Do not merge separate bubbles or separate UI elements.
        For each unit return:
          - box_2d: [ymin, xmin, ymax, xmax] tightly around the original text, normalized to 0-1000
          - text: the original text
          - ko: a natural, fluent Korean translation, using the context of the whole screen
            (keep names consistent; for game/manga dialogue keep the tone and speech style)
        Skip text that is already Korean, and skip lone numbers/symbols.
        Return only a JSON array. Return [] if there is nothing to translate.
    """.trimIndent()

    private fun requestBody(jpegBase64: String, lang: SourceLang): String {
        val itemSchema = JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("box_2d", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "INTEGER")))
                .put("text", JSONObject().put("type", "STRING"))
                .put("ko", JSONObject().put("type", "STRING")))
            .put("required", JSONArray().put("box_2d").put("text").put("ko"))

        return JSONObject()
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray()
                    .put(JSONObject().put("inline_data", JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", jpegBase64)))
                    .put(JSONObject().put("text", prompt(lang))))))
            .put("generationConfig", JSONObject()
                .put("temperature", 0.2)
                .put("responseMimeType", "application/json")
                .put("responseSchema", JSONObject().put("type", "ARRAY").put("items", itemSchema)))
            .toString()
    }

    /**
     * 붐비면 잠깐 쉬고 재시도 → 그래도 안 되면 예비 모델로 시도.
     * @param onStatus 진행 상황 문구 (버튼 표시용, 백그라운드 스레드에서 호출됨)
     * @return 화면 좌표로 변환된 번역 결과 목록
     */
    fun translate(
        bitmap: Bitmap,
        lang: SourceLang,
        apiKey: String,
        model: String,
        screenW: Int,
        screenH: Int,
        onStatus: (String) -> Unit = {}
    ): List<OverlayItem> {
        val jpeg = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
        val body = requestBody(jpeg, lang)

        val models = (listOf(model.trim()) + FALLBACK_MODELS).distinct()
        var lastError: GeminiException? = null
        for ((mi, m) in models.withIndex()) {
            // 주 모델은 2번 재시도, 예비 모델은 1번씩
            val attempts = if (mi == 0) 3 else 1
            for (attempt in 0 until attempts) {
                if (mi > 0) onStatus("우회중")
                else if (attempt > 0) onStatus("재시도")
                try {
                    return requestOnce(body, m, apiKey, screenW, screenH)
                } catch (e: GeminiException) {
                    lastError = e
                    if (!e.retryable) throw e
                    if (attempt < attempts - 1) Thread.sleep(1500L * (attempt + 1))
                }
            }
        }
        throw lastError ?: GeminiException("제미나이 오류")
    }

    private fun requestOnce(
        body: String,
        model: String,
        apiKey: String,
        screenW: Int,
        screenH: Int
    ): List<OverlayItem> {
        val modelId = URLEncoder.encode(model.trim().removePrefix("models/"), "UTF-8")
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey.trim())
        }

        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                val apiMsg = runCatching {
                    JSONObject(resp).getJSONObject("error").getString("message")
                }.getOrDefault(resp.take(200))
                throw GeminiException(
                    when (code) {
                        400, 401, 403 -> "API 키나 모델 이름 확인해줘 ($code): $apiMsg"
                        404 -> "모델($model)을 못 찾았어 ($code)"
                        429 -> "무료 사용량 초과. 잠깐 뒤에 다시 (429)"
                        500, 502, 503, 504 -> "구글 서버가 붐벼. 잠깐 뒤에 다시 눌러봐 ($code)"
                        else -> "제미나이 오류 ($code): $apiMsg"
                    },
                    // 서버 혼잡·일시 오류·모델 없음·모델별 한도 초과는 다른 모델로 넘어감
                    retryable = code in listOf(404, 429, 500, 502, 503, 504)
                )
            }
            return parse(resp, screenW, screenH)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String, screenW: Int, screenH: Int): List<OverlayItem> {
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val reason = root.optJSONObject("promptFeedback")?.optString("blockReason")
            throw GeminiException("응답이 비었어${if (!reason.isNullOrEmpty()) " ($reason)" else ""}")
        }
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: throw GeminiException("응답 형식이 이상해")
        val text = buildString {
            for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text"))
        }.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        val arr = JSONArray(text)
        val items = mutableListOf<OverlayItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ko = o.optString("ko").trim()
            val b = o.optJSONArray("box_2d") ?: continue
            if (ko.isEmpty() || b.length() < 4) continue
            val ymin = b.optInt(0).coerceIn(0, 1000)
            val xmin = b.optInt(1).coerceIn(0, 1000)
            val ymax = b.optInt(2).coerceIn(0, 1000)
            val xmax = b.optInt(3).coerceIn(0, 1000)
            if (xmax <= xmin || ymax <= ymin) continue
            items += OverlayItem(
                rect = Rect(
                    xmin * screenW / 1000, ymin * screenH / 1000,
                    xmax * screenW / 1000, ymax * screenH / 1000
                ),
                text = ko,
                lineCount = 1
            )
        }
        return items
    }
}
