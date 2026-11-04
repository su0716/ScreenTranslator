package com.nahyunung.screentranslator

import android.content.Context
import android.graphics.Rect
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** 원문 언어 목록. 번역 결과는 항상 한국어. */
enum class SourceLang(
    val label: String,
    val short: String,
    val englishName: String,
    val mlkitCode: String,
    val joinWithSpace: Boolean
) {
    EN("영어", "EN", "English", TranslateLanguage.ENGLISH, true),
    JA("일본어", "JP", "Japanese", TranslateLanguage.JAPANESE, false),
    ZH("중국어", "CN", "Chinese", TranslateLanguage.CHINESE, false);

    fun recognizer(): TextRecognizer = when (this) {
        EN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        JA -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        ZH -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    fun translator(): Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(mlkitCode)
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build()
    )
}

/** 번역 엔진 */
enum class Engine(val label: String, val short: String, val usesGemini: Boolean) {
    GEMINI("AI 정밀", "AI", true),      // 이미지째 보냄: 세로쓰기 OK, 느림
    HYBRID("AI 빠름", "빠름", true),     // 폰에서 글자 찾고 글자만 보냄: 빠름, 세로쓰기 약함
    MLKIT("오프라인", "오프", false),    // 전부 폰에서: 인터넷 X, 품질 낮음
    NANO("나노(실험)", "나노", false);   // 폰 안의 Gemini Nano로 번역: 인터넷 X, 실험 중

    fun next(): Engine = values()[(ordinal + 1) % values().size]
}

object Prefs {
    private const val FILE = "settings"
    private const val KEY_SRC = "source_lang"
    private const val KEY_ENGINE = "engine"
    private const val KEY_API = "gemini_api_key"
    private const val KEY_MODEL = "gemini_model"
    const val DEFAULT_MODEL = "gemini-flash-lite-latest"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun engine(ctx: Context): Engine =
        runCatching { Engine.valueOf(sp(ctx).getString(KEY_ENGINE, Engine.GEMINI.name)!!) }
            .getOrDefault(Engine.GEMINI)

    fun setEngine(ctx: Context, e: Engine) = sp(ctx).edit().putString(KEY_ENGINE, e.name).apply()

    fun apiKey(ctx: Context): String = sp(ctx).getString(KEY_API, "") ?: ""
    fun setApiKey(ctx: Context, key: String) = sp(ctx).edit().putString(KEY_API, key.trim()).apply()

    fun model(ctx: Context): String =
        sp(ctx).getString(KEY_MODEL, DEFAULT_MODEL)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
    fun setModel(ctx: Context, m: String) = sp(ctx).edit().putString(KEY_MODEL, m.trim()).apply()

    /** 영역 선택 모드: 버튼 탭 → 영역 드래그 → 버튼 다시 탭하면 전송 */
    fun regionMode(ctx: Context): Boolean = sp(ctx).getBoolean("region_mode", false)
    fun setRegionMode(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("region_mode", v).apply()

    /** 마지막으로 고른 영역 (다음에 그대로 다시 쓰기 좋게) */
    fun lastRegion(ctx: Context): Rect? = sp(ctx).getString("last_region", null)
        ?.split(",")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 }
        ?.let { Rect(it[0], it[1], it[2], it[3]) }
    fun setLastRegion(ctx: Context, r: Rect) =
        sp(ctx).edit().putString("last_region", "${r.left},${r.top},${r.right},${r.bottom}").apply()

    /** AI 안전 필터 완화 (게임 채팅 욕설 등도 번역) */
    fun relaxedSafety(ctx: Context): Boolean = sp(ctx).getBoolean("relaxed_safety", true)
    fun setRelaxedSafety(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("relaxed_safety", v).apply()

    /** 번역 끝나면 걸린 시간 표시 (속도 확인용) */
    fun showTiming(ctx: Context): Boolean = sp(ctx).getBoolean("show_timing", true)

    fun source(ctx: Context): SourceLang {
        val name = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_SRC, SourceLang.EN.name)
        return runCatching { SourceLang.valueOf(name!!) }.getOrDefault(SourceLang.EN)
    }

    fun setSource(ctx: Context, lang: SourceLang) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_SRC, lang.name).apply()
    }
}

/** 한 번 번역한 문장은 기억해 뒀다가 즉시 재사용 (게임 UI처럼 반복되는 글). 메인 스레드 전용. */
object TranslationCache {
    private const val MAX = 800
    private val map = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX
    }

    private fun key(lang: SourceLang, text: String) = "${lang.name}|${text.trim()}"
    fun get(lang: SourceLang, text: String, ns: String = ""): String? = map[ns + key(lang, text)]
    fun put(lang: SourceLang, text: String, ko: String, ns: String = "") { map[ns + key(lang, text)] = ko }
}
