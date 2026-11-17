package com.example.interpreter

import android.os.Build

// Latin-letter readings for learners who don't read the script: Korean, Japanese and Chinese.
object Romanizer {

    fun convert(text: String, code: String): String? = when (code) {
        "ko" -> korean(text)
        "ja" -> japanese(text)
        "zh" -> chinese(text)
        else -> null
    }

    /* ---------- Korean: Revised Romanization, with linking before a silent initial ---------- */

    private val initials = listOf(
        "g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h"
    )
    private val medials = listOf(
        "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi",
        "yu", "eu", "ui", "i"
    )
    private val codas = listOf(
        "", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p", "t", "t",
        "ng", "t", "t", "k", "t", "p", "t"
    )
    // Before a vowel the final consonant moves to the next syllable: (what stays, what moves).
    private val links = mapOf(
        1 to ("" to "g"), 2 to ("" to "kk"), 3 to ("k" to "s"), 4 to ("" to "n"), 5 to ("n" to "j"),
        6 to ("" to "n"), 7 to ("" to "d"), 8 to ("" to "r"), 9 to ("l" to "g"), 10 to ("l" to "m"),
        11 to ("l" to "b"), 12 to ("l" to "s"), 13 to ("l" to "t"), 14 to ("l" to "p"), 15 to ("" to "r"),
        16 to ("" to "m"), 17 to ("" to "b"), 18 to ("p" to "s"), 19 to ("" to "s"), 20 to ("" to "ss"),
        21 to ("ng" to ""), 22 to ("" to "j"), 23 to ("" to "ch"), 24 to ("" to "k"), 25 to ("" to "t"),
        26 to ("" to "p"), 27 to ("" to "")
    )

    private fun isSyllable(c: Char?) = c != null && c.code in 0xAC00..0xD7A3

    private fun korean(text: String): String {
        val sb = StringBuilder()
        var carry: String? = null
        for ((i, c) in text.withIndex()) {
            if (!isSyllable(c)) {
                sb.append(c)
                carry = null
                continue
            }
            val idx = c.code - 0xAC00
            val cho = idx / 588
            val jung = (idx % 588) / 28
            val jong = idx % 28
            val next = text.getOrNull(i + 1)
            val nextSilent = isSyllable(next) && (next!!.code - 0xAC00) / 588 == 11
            sb.append(carry ?: initials[cho])
            carry = null
            sb.append(medials[jung])
            if (jong != 0) {
                if (nextSilent) {
                    val (stay, move) = links.getValue(jong)
                    sb.append(stay)
                    carry = move
                } else {
                    sb.append(codas[jong])
                }
            }
        }
        return sb.toString()
    }

    /* ---------- Japanese: Hepburn romaji from the dictionary pronunciation ---------- */

    private val tokenizer by lazy { com.atilika.kuromoji.ipadic.Tokenizer() }

    private val kana: Map<String, String> = (
        "キャ kya キュ kyu キョ kyo シャ sha シュ shu ショ sho チャ cha チュ chu チョ cho ニャ nya ニュ nyu " +
        "ニョ nyo ヒャ hya ヒュ hyu ヒョ hyo ミャ mya ミュ myu ミョ myo リャ rya リュ ryu リョ ryo ギャ gya " +
        "ギュ gyu ギョ gyo ジャ ja ジュ ju ジョ jo ビャ bya ビュ byu ビョ byo ピャ pya ピュ pyu ピョ pyo " +
        "ファ fa フィ fi フェ fe フォ fo ティ ti ディ di シェ she ジェ je チェ che ウィ wi ウェ we ウォ wo " +
        "ア a イ i ウ u エ e オ o カ ka キ ki ク ku ケ ke コ ko ガ ga ギ gi グ gu ゲ ge ゴ go " +
        "サ sa シ shi ス su セ se ソ so ザ za ジ ji ズ zu ゼ ze ゾ zo タ ta チ chi ツ tsu テ te ト to " +
        "ダ da ヂ ji ヅ zu デ de ド do ナ na ニ ni ヌ nu ネ ne ノ no ハ ha ヒ hi フ fu ヘ he ホ ho " +
        "バ ba ビ bi ブ bu ベ be ボ bo パ pa ピ pi プ pu ペ pe ポ po マ ma ミ mi ム mu メ me モ mo " +
        "ヤ ya ユ yu ヨ yo ラ ra リ ri ル ru レ re ロ ro ワ wa ヲ o ン n ヴ vu " +
        "ァ a ィ i ゥ u ェ e ォ o ャ ya ュ yu ョ yo"
    ).split(" ").chunked(2).associate { it[0] to it[1] }

    private fun romaji(s0: String): String {
        val s = s0.map { if (it in '\u3041'..'\u3096') it + 0x60 else it }.joinToString("")
        val sb = StringBuilder()
        var double = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == 'ッ') {
                double = true
                i++
                continue
            }
            if (c == 'ー') {
                sb.lastOrNull { it in "aeiou" }?.let { sb.append(it) }
                i++
                continue
            }
            val two = if (i + 1 < s.length) kana[s.substring(i, i + 2)] else null
            val r = two ?: kana[c.toString()]
            if (r == null) {
                sb.append(c)
                i++
                continue
            }
            if (double) {
                sb.append(if (r.startsWith("ch")) 't' else r[0])
                double = false
            }
            sb.append(r)
            i += if (two != null) 2 else 1
        }
        return sb.toString()
    }

    private fun japanese(text: String): String = tokenizer.tokenize(text)
        .filter { it.partOfSpeechLevel1 != "記号" }
        .mapNotNull { t ->
            listOf(t.pronunciation, t.reading, t.surface).firstOrNull { it != null && it != "*" }
        }
        .map { romaji(it) }
        .filter { it.isNotBlank() }
        .joinToString(" ")

    /* ---------- Chinese: pinyin with tone marks ---------- */

    private val han: android.icu.text.Transliterator? by lazy {
        if (Build.VERSION.SDK_INT >= 29) android.icu.text.Transliterator.getInstance("Han-Latin") else null
    }

    private fun chinese(text: String): String? = han?.transliterate(text)
}