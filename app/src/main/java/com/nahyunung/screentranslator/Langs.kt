package com.nahyunung.screentranslator

import android.content.Context
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
enum class Engine(val label: String, val short: String) {
    GEMINI("AI (제미나이)", "AI"),
    MLKIT("오프라인 (ML Kit)", "오프");
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
