package com.vnceviri.app

import com.google.mlkit.nl.translate.TranslateLanguage

data class Lang(
    val label: String,   // ekranda görünen ad
    val mlkit: String,   // ML Kit çeviri kodu
    val google: String,  // Google çevrimiçi çeviri kodu
    val script: Int      // OCR için yazı türü
)

object Langs {
    const val LATIN = 0
    const val JA = 1
    const val ZH = 2
    const val KO = 3

    val all: List<Lang> = listOf(
        Lang("İngilizce", TranslateLanguage.ENGLISH, "en", LATIN),
        Lang("Japonca", TranslateLanguage.JAPANESE, "ja", JA),
        Lang("Çince", TranslateLanguage.CHINESE, "zh-CN", ZH),
        Lang("Korece", TranslateLanguage.KOREAN, "ko", KO),
        Lang("İspanyolca", TranslateLanguage.SPANISH, "es", LATIN),
        Lang("Fransızca", TranslateLanguage.FRENCH, "fr", LATIN),
        Lang("Almanca", TranslateLanguage.GERMAN, "de", LATIN),
        Lang("Portekizce", TranslateLanguage.PORTUGUESE, "pt", LATIN),
        Lang("İtalyanca", TranslateLanguage.ITALIAN, "it", LATIN),
        Lang("Endonezce", TranslateLanguage.INDONESIAN, "id", LATIN)
    )
}
