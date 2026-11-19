package com.example.interpreter

import android.content.Context

/**
 * Ready answers to what the other person just said, without internet or AI:
 *  1. the situation dialogues DB: lines of staff similar to what they said, and what the traveler
 *     answered next there (in both languages, so no translation is needed),
 *  2. answers by kind of question (how many, when, where, pay, name...), in my language,
 *  3. a few that always help (please repeat, slowly...).
 */
object OfflineReplies {
    class Reply(val mine: String, val theirs: String?)

    private class Pair2(val staff: String, val replies: List<Reply>)

    // Staff lines and the traveler's answers after them, per foreign language, home language and place.
    private val cache = HashMap<String, List<Pair2>>()

    private fun pairs(ctx: Context, lang: String, home: String, places: List<Int>): List<Pair2> {
        val out = ArrayList<Pair2>()
        for (place in places) {
            val key = "$lang|$home|$place"
            val list = synchronized(cache) { cache[key] } ?: run {
                val found = ArrayList<Pair2>()
                val src = try { BasePack.source(ctx, lang, place, home) } catch (_: Exception) { null }
                val ds = src?.optJSONArray("dialogues")
                if (ds != null) for (d in 0 until ds.length()) {
                    val lines = ds.optJSONObject(d)?.optJSONArray("lines") ?: continue
                    for (i in 0 until lines.length() - 1) {
                        val l = lines.optJSONObject(i) ?: continue
                        if (l.optString("who") != "B") continue
                        val next = lines.optJSONObject(i + 1) ?: continue
                        if (next.optString("who") != "A") continue
                        val meaning = next.optJSONObject("m")?.optString(home).orEmpty()
                        val text = next.optString("text")
                        if (meaning.isBlank() || text.isBlank()) continue
                        found += Pair2(l.optString("text"), listOf(Reply(meaning, text)))
                    }
                }
                synchronized(cache) { cache[key] = found }
                found
            }
            out += list
        }
        return out
    }

    // Similarity of two sentences by shared character pairs (works for Japanese and Chinese too).
    private fun grams(s: String): Set<String> {
        val t = s.lowercase().filter { it.isLetterOrDigit() }
        return if (t.length < 2) setOf(t) else (0 until t.length - 1).map { t.substring(it, it + 2) }.toSet()
    }

    private fun similarity(a: String, b: String): Double {
        val x = grams(a); val y = grams(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size
    }

    /** Up to [max] answers. Runs on a background thread (reads the DB the first time). */
    fun suggest(ctx: Context, said: String, saidLang: String, inMine: String, home: String, scene: Int?, max: Int = 10): List<Reply> {
        val out = ArrayList<Reply>()
        fun add(r: Reply) { if (out.size < max && out.none { it.mine == r.mine }) out += r }

        // 1. The DB: the scene's place first, then every place.
        if (said.isNotBlank()) {
            val places = if (scene != null && scene >= 0) listOf(scene) else Places.all.indices.toList()
            val ranked = pairs(ctx, saidLang, home, places)
                .map { it to similarity(said, it.staff) }
                .filter { it.second >= 0.22 }
                .sortedByDescending { it.second }
            for ((p, _) in ranked.take(8)) p.replies.forEach { add(it) }
            if (out.size < 3 && scene != null && scene >= 0) {
                pairs(ctx, saidLang, home, Places.all.indices.toList())
                    .map { it to similarity(said, it.staff) }.filter { it.second >= 0.3 }
                    .sortedByDescending { it.second }.take(4).forEach { (p, _) -> p.replies.forEach { add(it) } }
            }
        }
        val dbCount = out.size

        // 2. By kind of question, from my-language translation of what they said.
        val q = (inMine + " " + said).lowercase()
        for ((keys, answers) in ReplyBank.kinds()) {
            if (keys.any { q.contains(it) }) answers.forEach { add(Reply(it, null)) }
            if (out.size >= dbCount + 5) break
        }
        if (out.size == dbCount && (said.trim().endsWith("?") || said.trim().endsWith("？") || q.contains("까요") || q.contains("나요"))) {
            ReplyBank.yesNo().forEach { add(Reply(it, null)) }
        }

        // 3. Always useful.
        ReplyBank.always().forEach { add(Reply(it, null)) }
        return out
    }
}

// Answers by kind of question. Korean, Japanese and English; other languages use English.
object ReplyBank {
    private fun lang() = when (I18n.lang) { "ko" -> 0; "ja" -> 1; else -> 2 }
    private fun pick(vararg v: List<String>) = v[lang()]

    fun yesNo() = pick(
        listOf("네, 맞아요", "아니요, 괜찮아요", "네, 부탁드려요"),
        listOf("はい、そうです", "いいえ、大丈夫です", "はい、お願いします"),
        listOf("Yes, that's right", "No, thank you", "Yes, please"))

    fun always() = pick(
        listOf("다시 한 번 말씀해 주세요", "천천히 말씀해 주세요", "잘 모르겠어요", "감사합니다"),
        listOf("もう一度言ってください", "ゆっくり話してください", "よく分かりません", "ありがとうございます"),
        listOf("Could you say that again?", "Could you speak more slowly?", "I'm not sure", "Thank you"))

    /** (words that show the kind of question, in any of my languages and English) to answers. */
    fun kinds(): List<Pair<List<String>, List<String>>> = listOf(
        listOf("몇 분", "몇 명", "인원", "何名", "何人", "how many people", "party of") to pick(
            listOf("한 명이에요", "두 명이에요", "세 명이에요", "네 명이에요"),
            listOf("一人です", "二人です", "三人です", "四人です"),
            listOf("Just one", "Two people", "Three people", "Four people")),
        listOf("결제", "카드", "현금", "계산", "支払", "カード", "現金", "pay", "card", "cash") to pick(
            listOf("카드로 할게요", "현금으로 할게요", "따로 계산해 주세요"),
            listOf("カードでお願いします", "現金でお願いします", "別々にお願いします"),
            listOf("By card, please", "In cash, please", "Separate checks, please")),
        listOf("드시고", "포장", "가져가", "店内", "持ち帰り", "for here", "to go", "take away") to pick(
            listOf("여기서 먹을게요", "포장해 주세요"),
            listOf("ここで食べます", "持ち帰りでお願いします"),
            listOf("For here, please", "To go, please")),
        listOf("언제", "몇 시", "며칠", "いつ", "何時", "when", "what time") to pick(
            listOf("오늘이요", "내일이요", "지금 바로요", "오후 세 시쯤이요"),
            listOf("今日です", "明日です", "今すぐです", "午後三時ごろです"),
            listOf("Today", "Tomorrow", "Right now", "Around 3 p.m.")),
        listOf("어디", "どこ", "どちら", "where") to pick(
            listOf("여기로 가 주세요", "지도에서 보여 드릴게요", "역 근처예요", "호텔로 가요"),
            listOf("ここへお願いします", "地図で見せます", "駅の近くです", "ホテルへ行きます"),
            listOf("Here, please", "Let me show you on the map", "Near the station", "To my hotel")),
        listOf("이름", "성함", "여권", "예약", "名前", "パスポート", "予約", "name", "passport", "reservation", "booking") to pick(
            listOf("여기 여권이요", "예약했어요", "예약 안 했어요", "이름은 이거예요"),
            listOf("パスポートです", "予約しています", "予約していません", "名前はこれです"),
            listOf("Here's my passport", "I have a reservation", "I don't have a reservation", "Here's my name")),
        listOf("뭐", "무엇", "어떤", "어느", "주문", "何", "どれ", "どの", "注文", "what", "which", "order") to pick(
            listOf("이걸로 할게요", "추천해 주세요", "메뉴 좀 볼게요", "잠시만요"),
            listOf("これにします", "おすすめは何ですか", "メニューを見せてください", "少し待ってください"),
            listOf("I'll have this one", "What do you recommend?", "Can I see the menu?", "Just a moment, please")),
        listOf("사이즈", "크기", "サイズ", "size") to pick(
            listOf("작은 걸로요", "중간으로요", "큰 걸로요"),
            listOf("小さいのでお願いします", "中くらいでお願いします", "大きいのでお願いします"),
            listOf("Small, please", "Medium, please", "Large, please")),
        listOf("얼마", "가격", "いくら", "値段", "how much", "price") to pick(
            listOf("얼마예요?", "조금 깎아 주실 수 있나요?", "괜찮아요, 살게요"),
            listOf("いくらですか", "少し安くなりますか", "大丈夫です、買います"),
            listOf("How much is it?", "Could you make it cheaper?", "OK, I'll take it")),
        listOf("괜찮", "도와", "필요", "大丈夫", "手伝", "必要", "help", "anything else", "need") to pick(
            listOf("괜찮아요, 감사합니다", "네, 도와주세요", "이거면 돼요"),
            listOf("大丈夫です、ありがとう", "はい、手伝ってください", "これで大丈夫です"),
            listOf("I'm fine, thank you", "Yes, please help me", "That's all, thanks"))
    )
}
