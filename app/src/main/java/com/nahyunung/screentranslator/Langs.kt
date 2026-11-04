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
enum class SourceLang(val label: String, val short: String, val mlkitCode: String, val joinWithSpace: Boolean) {
    EN("영어", "EN", TranslateLanguage.ENGLISH, true),
    JA("일본어", "JP", TranslateLanguage.JAPANESE, false),
    ZH("중국어", "CN", TranslateLanguage.CHINESE, false);

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

object Prefs {
    private const val FILE = "settings"
    private const val KEY_SRC = "source_lang"

    fun source(ctx: Context): SourceLang {
        val name = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_SRC, SourceLang.EN.name)
        return runCatching { SourceLang.valueOf(name!!) }.getOrDefault(SourceLang.EN)
    }

    fun setSource(ctx: Context, lang: SourceLang) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_SRC, lang.name).apply()
    }
}
