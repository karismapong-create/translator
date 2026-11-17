package com.example.interpreter

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.nl.translate.TranslateLanguage
import java.text.Normalizer
import java.util.concurrent.Executors

// Shows how a translated sentence sounds, written in Hangul.
object Pronouncer {
    private val worker = Executors.newSingleThreadExecutor()

    fun attach(activity: Activity, parent: LinearLayout, anchor: TextView, text: String, lang: String) {
        if (lang == TranslateLanguage.KOREAN) return
        val view = TextView(activity).apply {
            this.text = "…"
            textSize = 18f
            setTextColor(Color.rgb(47, 91, 234))
        }
        parent.addView(view, parent.indexOfChild(anchor) + 1)
        worker.execute {
            val result = try { toHangul(activity, text, lang) } catch (e: Throwable) { null }
            activity.runOnUiThread {
                if (result.isNullOrBlank()) parent.removeView(view) else view.text = "[$result]"
            }
        }
    }

    private fun toHangul(ctx: Context, text: String, lang: String): String? = when (lang) {
        TranslateLanguage.JAPANESE -> Japanese.convert(text)
        TranslateLanguage.CHINESE -> Chinese.convert(text)
        TranslateLanguage.ENGLISH -> English.convert(ctx, text)
        TranslateLanguage.SPANISH -> Spanish.convert(text)
        TranslateLanguage.FRENCH -> French.convert(text)
        else -> null
    }
}

internal object Hangul {
    fun syl(cho: Int, jung: Int, jong: Int = 0): Char = (0xAC00 + (cho * 21 + jung) * 28 + jong).toChar()
    fun isSyl(c: Char) = c.code in 0xAC00..0xD7A3
    fun cho(c: Char) = (c.code - 0xAC00) / 588
    fun jung(c: Char) = ((c.code - 0xAC00) % 588) / 28
    fun jong(c: Char) = (c.code - 0xAC00) % 28
    fun addJong(sb: StringBuilder, jong: Int): Boolean {
        val c = sb.lastOrNull() ?: return false
        if (!isSyl(c) || jong(c) != 0) return false
        sb.setCharAt(sb.length - 1, syl(cho(c), jung(c), jong))
        return true
    }
}

// Turns a list of sounds into Hangul syllables.
internal object Phon {
    private val vowels = setOf("a", "ae", "e", "eo", "o", "u", "eu", "i", "oe")
    private val plainJ = mapOf("a" to 0, "ae" to 1, "e" to 5, "eo" to 4, "o" to 8, "u" to 13, "eu" to 18, "i" to 20, "oe" to 11)
    private val yJ = mapOf("a" to 2, "ae" to 3, "e" to 7, "eo" to 6, "o" to 12, "u" to 17, "eu" to 17, "i" to 20, "oe" to 7)
    private val wJ = mapOf("a" to 9, "ae" to 10, "e" to 15, "eo" to 14, "o" to 14, "u" to 13, "eu" to 13, "i" to 16, "oe" to 11)
    private val choMap = mapOf(
        "p" to 17, "b" to 7, "t" to 16, "d" to 3, "k" to 15, "g" to 0, "f" to 17, "v" to 7,
        "s" to 9, "z" to 12, "sh" to 9, "zh" to 12, "ch" to 14, "j" to 12, "m" to 6, "n" to 2,
        "ng" to 11, "l" to 5, "r" to 5, "h" to 18, "th" to 10, "dh" to 3
    )
    private val tense = mapOf("p" to 8, "t" to 4, "k" to 1)
    private val nasalCoda = mapOf("m" to 16, "n" to 4, "ng" to 21, "l" to 8)
    private val stopCoda = mapOf("p" to 17, "k" to 1, "t" to 19)
    private val lax = setOf(1, 4, 5, 13, 20)

    fun render(ph: List<String>, useTense: Boolean, english: Boolean): String {
        val sb = StringBuilder()
        var i = 0
        while (i < ph.size) {
            val p = ph[i]
            val prevVowel = i > 0 && ph[i - 1] in vowels
            if (p in vowels) {
                sb.append(Hangul.syl(11, plainJ.getValue(p)))
                i++
                continue
            }
            val isGlide = p == "y" || p == "w"
            var cho = 11
            var j = i
            if (!isGlide) {
                cho = (if (useTense) tense[p] else null) ?: choMap[p] ?: 11
                j++
            }
            var glide: String? = null
            if (j < ph.size && (ph[j] == "y" || ph[j] == "w")) { glide = ph[j]; j++ }
            if (p == "sh" && glide == null) glide = "y"
            if (j < ph.size && ph[j] in vowels) {
                val v = ph[j]
                var jung = when (glide) {
                    "y" -> yJ.getValue(v)
                    "w" -> wJ.getValue(v)
                    else -> plainJ.getValue(v)
                }
                if (p == "sh" && v == "i") jung = 20
                if (p == "l" && glide == null && prevVowel) Hangul.addJong(sb, 8)
                sb.append(Hangul.syl(cho, jung))
                i = j + 1
                continue
            }
            if (isGlide || (english && p == "r" && prevVowel)) { i++; continue }
            val nasal = nasalCoda[p]
            if (nasal != null && prevVowel && Hangul.addJong(sb, nasal)) { i++; continue }
            val stop = stopCoda[p]
            if (english && stop != null && prevVowel) {
                val last = sb.lastOrNull()
                if (last != null && Hangul.isSyl(last) && Hangul.jung(last) in lax && Hangul.addJong(sb, stop)) { i++; continue }
            }
            val vowel = if (p == "sh" || p == "zh" || p == "ch" || p == "j") 20 else 18
            sb.append(Hangul.syl(choMap[p] ?: 11, vowel))
            i++
        }
        return sb.toString()
    }
}

internal object Japanese {
    private val tokenizer by lazy { com.atilika.kuromoji.ipadic.Tokenizer() }
    private val table: Map<Char, String> = run {
        val s = "ア아イ이ウ우エ에オ오カ카キ키ク쿠ケ케コ코ガ가ギ기グ구ゲ게ゴ고サ사シ시ス스セ세ソ소ザ자ジ지ズ즈ゼ제ゾ조" +
                "タ타チ치ツ츠テ테ト토ダ다ヂ지ヅ즈デ데ド도ナ나ニ니ヌ누ネ네ノ노ハ하ヒ히フ후ヘ헤ホ호バ바ビ비ブ부ベ베ボ보" +
                "パ파ピ피プ푸ペ페ポ포マ마ミ미ム무メ메モ모ヤ야ユ유ヨ요ラ라リ리ル루レ레ロ로ワ와ヲ오ヴ부"
        (s.indices step 2).associate { s[it] to s[it + 1].toString() }
    }

    fun convert(text: String): String {
        val out = StringBuilder()
        for (token in tokenizer.tokenize(text)) {
            val sound = listOf(token.pronunciation, token.reading, token.surface)
                .firstOrNull { it != null && it != "*" } ?: continue
            val part = kana(sound)
            if (part.isBlank()) continue
            if (out.isNotEmpty() && Hangul.isSyl(part.first())) out.append(' ')
            out.append(part)
        }
        return out.toString()
    }

    private fun kana(s: String): String {
        val sb = StringBuilder()
        for (ch0 in s) {
            val ch = if (ch0 in '\u3041'..'\u3096') ch0 + 0x60 else ch0
            val base = table[ch]
            when {
                base != null -> sb.append(base)
                ch == 'ン' -> if (!Hangul.addJong(sb, 4)) sb.append('ㄴ')
                ch == 'ッ' -> Hangul.addJong(sb, 19)
                ch == 'ー' -> {}
                ch == 'ャ' || ch == 'ュ' || ch == 'ョ' -> replaceLast(sb) { cho, _ ->
                    val plain = cho == 12 || cho == 14
                    when (ch) {
                        'ャ' -> if (plain) 0 else 2
                        'ュ' -> if (plain) 13 else 17
                        else -> if (plain) 8 else 12
                    }
                }
                ch in "ァィゥェォ" -> replaceLast(sb) { _, jung ->
                    val u = jung == 13
                    when (ch) {
                        'ァ' -> if (u) 9 else 0
                        'ィ' -> if (u) 16 else 20
                        'ゥ' -> 13
                        'ェ' -> if (u) 15 else 5
                        else -> if (u) 14 else 8
                    }
                }
                else -> sb.append(ch0)
            }
        }
        return sb.toString()
    }

    private fun replaceLast(sb: StringBuilder, f: (Int, Int) -> Int) {
        val c = sb.lastOrNull() ?: return
        if (!Hangul.isSyl(c) || Hangul.jong(c) != 0) return
        sb.setCharAt(sb.length - 1, Hangul.syl(Hangul.cho(c), f(Hangul.cho(c), Hangul.jung(c))))
    }
}

internal object Chinese {
    private val translit: android.icu.text.Transliterator? by lazy {
        if (Build.VERSION.SDK_INT >= 29) android.icu.text.Transliterator.getInstance("Han-Latin") else null
    }
    private val initials = listOf(
        "zh" to 12, "ch" to 14, "sh" to 9, "b" to 7, "p" to 17, "m" to 6, "f" to 17, "d" to 3,
        "t" to 16, "n" to 2, "l" to 5, "g" to 0, "k" to 15, "h" to 18, "j" to 12, "q" to 14,
        "x" to 9, "r" to 5, "z" to 13, "c" to 14, "s" to 10
    )
    private val finals = mapOf(
        "a" to "아", "o" to "오", "e" to "어", "i" to "이", "u" to "우", "v" to "위", "ai" to "아이",
        "ei" to "에이", "ao" to "아오", "ou" to "어우", "an" to "안", "en" to "언", "ang" to "앙",
        "eng" to "엉", "ong" to "웅", "er" to "얼", "ia" to "야", "ie" to "예", "iao" to "야오",
        "iu" to "유", "ian" to "옌", "in" to "인", "iang" to "양", "ing" to "잉", "iong" to "융",
        "ua" to "와", "uo" to "워", "uai" to "와이", "ui" to "웨이", "uan" to "완", "un" to "운",
        "uen" to "원", "uang" to "왕", "ueng" to "웡", "ue" to "웨", "ve" to "웨", "van" to "위안", "vn" to "윈"
    )
    private val yw = mapOf(
        "yi" to "i", "ya" to "ia", "ye" to "ie", "yao" to "iao", "you" to "iu", "yan" to "ian",
        "yin" to "in", "yang" to "iang", "ying" to "ing", "yong" to "iong", "yu" to "v", "yue" to "ve",
        "yuan" to "van", "yun" to "vn", "wu" to "u", "wa" to "ua", "wo" to "uo", "wai" to "uai",
        "wei" to "ui", "wan" to "uan", "wen" to "uen", "wang" to "uang", "weng" to "ueng"
    )
    private val simplify = mapOf(2 to 0, 3 to 1, 6 to 4, 7 to 5, 12 to 8, 17 to 13)

    fun convert(text: String): String? {
        val t = translit ?: return null
        val latin = Normalizer.normalize(t.transliterate(text), Normalizer.Form.NFD)
            .replace("u\u0308", "v").replace(Regex("\\p{Mn}+"), "").lowercase()
        return Regex("[a-z]+").replace(latin) { syllable(it.value) }
    }

    private fun syllable(s: String): String {
        yw[s]?.let { return finals[it] ?: s }
        val (ini, cho) = initials.firstOrNull { s.startsWith(it.first) } ?: return finals[s] ?: s
        var fin = s.substring(ini.length)
        if (ini in setOf("j", "q", "x") && fin.startsWith("u")) fin = "v" + fin.substring(1)
        if (fin == "i" && ini in setOf("zh", "ch", "sh", "r", "z", "c", "s")) return Hangul.syl(cho, 18).toString()
        val f = finals[fin] ?: return s
        var jung = Hangul.jung(f[0])
        if (cho == 12 || cho == 14 || cho == 9) jung = simplify[jung] ?: jung
        return Hangul.syl(cho, jung, Hangul.jong(f[0])).toString() + f.substring(1)
    }
}

internal object English {
    private var dict: HashMap<String, List<String>>? = null
    private val arpa = mapOf(
        "AA" to listOf("a"), "AE" to listOf("ae"), "AH" to listOf("eo"), "AO" to listOf("o"),
        "AW" to listOf("a", "u"), "AY" to listOf("a", "i"), "EH" to listOf("e"), "ER" to listOf("eo"),
        "EY" to listOf("e", "i"), "IH" to listOf("i"), "IY" to listOf("i"), "OW" to listOf("o"),
        "OY" to listOf("o", "i"), "UH" to listOf("u"), "UW" to listOf("u"),
        "B" to listOf("b"), "CH" to listOf("ch"), "D" to listOf("d"), "DH" to listOf("dh"),
        "F" to listOf("f"), "G" to listOf("g"), "HH" to listOf("h"), "JH" to listOf("j"),
        "K" to listOf("k"), "L" to listOf("l"), "M" to listOf("m"), "N" to listOf("n"),
        "NG" to listOf("ng"), "P" to listOf("p"), "R" to listOf("r"), "S" to listOf("s"),
        "SH" to listOf("sh"), "T" to listOf("t"), "TH" to listOf("th"), "V" to listOf("v"),
        "W" to listOf("w"), "Y" to listOf("y"), "Z" to listOf("z"), "ZH" to listOf("zh")
    )

    private fun load(ctx: Context): HashMap<String, List<String>> {
        dict?.let { return it }
        val map = HashMap<String, List<String>>(140000)
        try {
            ctx.assets.open("cmudict.dict").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val parts = line.split(' ')
                    if (parts.size < 2 || parts[0].contains('(')) continue
                    map[parts[0]] = parts.drop(1).takeWhile { it != "#" }.map { p -> p.filter { it.isLetter() } }
                }
            }
        } catch (_: Exception) {}
        dict = map
        return map
    }

    fun convert(ctx: Context, text: String): String {
        val d = load(ctx)
        return text.split(Regex("\\s+")).joinToString(" ") { raw ->
            val key = raw.lowercase().filter { it.isLetter() || it == '\'' }.trim('\'')
            val phones = d[key] ?: return@joinToString raw
            Phon.render(phones.flatMap { arpa[it] ?: emptyList() }, useTense = false, english = true)
        }
    }
}

internal object Spanish {
    fun convert(text: String) = text.split(Regex("\\s+")).joinToString(" ") { word(it) }

    private fun word(w0: String): String {
        val w = w0.lowercase().map {
            when (it) { 'á' -> 'a'; 'é' -> 'e'; 'í' -> 'i'; 'ó' -> 'o'; 'ú' -> 'u'; else -> it }
        }.joinToString("").filter { it.isLetter() }
        if (w.isEmpty()) return w0
        val ph = mutableListOf<String>()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            val n = w.getOrNull(i + 1)
            val n2 = w.getOrNull(i + 2)
            if (c == 'c' && n == 'h') { ph += "ch"; i += 2; continue }
            if (c == 'l' && n == 'l') { ph += "y"; i += 2; continue }
            if (c == 'r' && n == 'r') { ph += "r"; i += 2; continue }
            if (c == 'q' && n == 'u') { ph += "k"; i += 2; continue }
            if (c == 'g' && n == 'u' && (n2 == 'e' || n2 == 'i')) { ph += "g"; i += 2; continue }
            when (c) {
                'a', 'e', 'i', 'o', 'u' -> ph += c.toString()
                'ü' -> ph += "w"
                'c' -> ph += if (n == 'e' || n == 'i') "s" else "k"
                'g' -> ph += if (n == 'e' || n == 'i') "h" else "g"
                'j' -> ph += "h"
                'h' -> {}
                'ñ' -> { ph += "n"; ph += "y" }
                'y' -> ph += if (n != null && n in "aeiou") "y" else "i"
                'v' -> ph += "b"
                'z' -> ph += "s"
                'x' -> { ph += "k"; ph += "s" }
                else -> ph += c.toString()
            }
            i++
        }
        return Phon.render(ph, useTense = true, english = false)
    }
}

internal object French {
    private const val V = "aeiouyéèêëàâîïôûùœ"
    private val shortWords = mapOf(
        "les" to "lé", "des" to "dé", "mes" to "mé", "tes" to "té", "ses" to "sé",
        "ces" to "cé", "et" to "é", "est" to "è"
    )

    fun convert(text: String) = text.split(Regex("\\s+")).joinToString(" ") { word(it) }

    private fun word(w0: String): String {
        var w = w0.lowercase().filter { it.isLetter() }
        if (w.isEmpty()) return w0
        shortWords[w]?.let { w = it }
        if (w.length > 2 && (w.endsWith("er") || w.endsWith("ez"))) w = w.dropLast(2) + "é"
        if (w.length > 1 && w.last() in "stdxzp") w = w.dropLast(1)
        if (w.length > 2 && w.last() == 'e') w = w.dropLast(1)

        val ph = mutableListOf<String>()
        fun at(k: Int) = w.getOrNull(k)
        fun isV(c: Char?) = c != null && c in V
        fun nasal(k: Int) = !isV(at(k)) && at(k) != 'n' && at(k) != 'm'
        var i = 0
        while (i < w.length) {
            val r = w.substring(i)
            val n2 = nasal(i + 2)
            i += when {
                r.startsWith("eau") -> { ph += "o"; 3 }
                (r.startsWith("ain") || r.startsWith("ein")) && nasal(i + 3) -> { ph += listOf("ae", "ng"); 3 }
                r.startsWith("oin") && nasal(i + 3) -> { ph += listOf("u", "ae", "ng"); 3 }
                r.startsWith("au") -> { ph += "o"; 2 }
                r.startsWith("ou") || r.startsWith("où") -> { ph += "u"; 2 }
                r.startsWith("oi") -> { ph += listOf("u", "a"); 2 }
                r.startsWith("ai") || r.startsWith("ei") -> { ph += "e"; 2 }
                r.startsWith("eu") || r.startsWith("œu") -> { ph += "oe"; 2 }
                (r.startsWith("an") || r.startsWith("am") || r.startsWith("en") || r.startsWith("em")) && n2 -> { ph += listOf("a", "ng"); 2 }
                (r.startsWith("on") || r.startsWith("om")) && n2 -> { ph += listOf("o", "ng"); 2 }
                (r.startsWith("in") || r.startsWith("im") || r.startsWith("un") || r.startsWith("um")) && n2 -> { ph += listOf("ae", "ng"); 2 }
                r.startsWith("gn") -> { ph += listOf("n", "y"); 2 }
                r.startsWith("ch") -> { ph += "sh"; 2 }
                r.startsWith("ph") -> { ph += "f"; 2 }
                r.startsWith("qu") -> { ph += "k"; 2 }
                r.startsWith("th") -> { ph += "t"; 2 }
                else -> { single(w, i, ph); 1 }
            }
        }
        return Phon.render(ph, useTense = true, english = false)
    }

    private fun single(w: String, i: Int, ph: MutableList<String>) {
        val c = w[i]
        val n = w.getOrNull(i + 1)
        val p = w.getOrNull(i - 1)
        val front = n != null && n in "eiyéèê"
        if (n == c && c !in V) return
        when (c) {
            'a', 'à', 'â' -> ph += "a"
            'é', 'è', 'ê', 'ë' -> ph += "e"
            'e' -> {
                val n2 = w.getOrNull(i + 2)
                ph += if (n != null && n !in V && n2 != null && n2 !in V) "e" else "eu"
            }
            'i', 'î', 'ï', 'y' -> ph += "i"
            'o', 'ô' -> ph += "o"
            'u', 'û', 'ù' -> ph += listOf("w", "i")
            'c' -> ph += if (front) "s" else "k"
            'ç' -> ph += "s"
            'g' -> ph += if (front) "zh" else "g"
            'j' -> ph += "zh"
            'h' -> {}
            's' -> ph += if (p != null && p in V && n != null && n in V) "z" else "s"
            'x' -> ph += listOf("k", "s")
            'œ' -> ph += "oe"
            else -> ph += c.toString()
        }
    }
}