package com.example.interpreter

import android.content.SharedPreferences

// A language the app can listen to, translate and speak.
data class Lang(val code: String, val speechTag: String, val native: String, val english: String)

class Country(val flag: String, val name: String, val lang: String)

// Supported languages, countries, and the user's own language.
object Languages {

    val all = listOf(
        Lang("ko", "ko-KR", "한국어", "Korean"),
        Lang("ja", "ja-JP", "日本語", "Japanese"),
        Lang("en", "en-US", "English", "English"),
        Lang("fr", "fr-FR", "Français", "French"),
        Lang("es", "es-ES", "Español", "Spanish"),
        Lang("de", "de-DE", "Deutsch", "German"),
        Lang("zh", "zh-CN", "中文", "Simplified Chinese")
    )

    val countries = listOf(
        Country("🇰🇷", "대한민국", "ko"),
        Country("🇯🇵", "日本", "ja"),
        Country("🇺🇸", "United States", "en"),
        Country("🇬🇧", "United Kingdom", "en"),
        Country("🇨🇦", "Canada", "en"),
        Country("🇦🇺", "Australia", "en"),
        Country("🇫🇷", "France", "fr"),
        Country("🇪🇸", "España", "es"),
        Country("🇲🇽", "México", "es"),
        Country("🇩🇪", "Deutschland", "de"),
        Country("🇦🇹", "Österreich", "de"),
        Country("🇨🇭", "Schweiz", "de"),
        Country("🇨🇳", "中国", "zh")
    )

    fun byCode(code: String): Lang = all.firstOrNull { it.code == code } ?: all.first()

    fun hasChosen(prefs: SharedPreferences) = prefs.contains("home")

    fun home(prefs: SharedPreferences): Lang = byCode(prefs.getString("home", "ko") ?: "ko")

    fun countryLabel(prefs: SharedPreferences): String = prefs.getString("country", null) ?: "🇰🇷 대한민국"

    fun setHome(prefs: SharedPreferences, c: Country) {
        prefs.edit().putString("home", c.lang).putString("country", "${c.flag} ${c.name}").apply()
    }
}