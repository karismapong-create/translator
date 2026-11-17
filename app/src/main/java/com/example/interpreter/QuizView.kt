package com.example.interpreter

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Vocabulary test: multiple choice in both directions, or flash cards.
class QuizView(
    context: Context,
    private val cards: List<Card>,
    private val mode: Int,
    private val speak: (String, String) -> Unit,
    private val onAnswer: (Card, Boolean) -> Unit,
    private val onClose: () -> Unit
) : LinearLayout(context) {

    class Card(
        val key: Int, val front: String, val back: String,
        val example: String, val exampleMeaning: String, val voice: String
    )

    companion object {
        const val PICK_MEANING = 0
        const val PICK_FOREIGN = 1
        const val FLASH = 2
        const val MAX_QUESTIONS = 20
        private val GREEN = Color.rgb(46, 160, 67)
        private val RED = Color.rgb(207, 34, 46)
    }

    private val density = resources.displayMetrics.density
    private var order: List<Card> = cards.shuffled().take(MAX_QUESTIONS)
    private var pos = 0
    private var correct = 0
    private val wrong = ArrayList<Card>()

    private val progress: TextView
    private val prompt: TextView
    private val speakButton: Button
    private val detail: TextView
    private val choices: LinearLayout
    private val nextButton: Button

    init {
        orientation = VERTICAL
        setPadding(0, dp(8), 0, 0)
        progress = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.GRAY)
        }
        prompt = TextView(context).apply {
            textSize = 30f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(8))
        }
        speakButton = Button(context).apply {
            text = "🔊 듣기"
            setOnClickListener { order.getOrNull(pos)?.let { speak(it.front, it.voice) } }
        }
        detail = TextView(context).apply {
            textSize = 15f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        choices = LinearLayout(context).apply { orientation = VERTICAL }
        nextButton = Button(context).apply {
            text = "다음"
            visibility = View.GONE
            setOnClickListener { advance() }
        }
        val quit = Button(context).apply {
            text = "그만하기"
            setOnClickListener { onClose() }
        }
        val body = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(progress)
            addView(prompt)
            addView(speakButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            addView(detail)
            addView(choices)
            addView(nextButton)
        }
        addView(ScrollView(context).apply { addView(body) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(quit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        show()
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun button(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 17f
        setOnClickListener { onClick() }
    }

    private fun explain(card: Card) = buildString {
        append(card.front).append("  =  ").append(card.back)
        if (card.example.isNotEmpty()) append("\n\n예) ").append(card.example)
        if (card.exampleMeaning.isNotEmpty()) append("\n").append(card.exampleMeaning)
    }

    private fun show() {
        val card = order.getOrNull(pos) ?: return finish()
        progress.text = "${pos + 1} / ${order.size}   ·   맞힘 $correct"
        val askForeign = mode != PICK_FOREIGN
        prompt.text = if (askForeign) card.front else card.back
        speakButton.visibility = if (askForeign) View.VISIBLE else View.GONE
        detail.text = ""
        choices.removeAllViews()
        nextButton.visibility = View.GONE

        if (mode == FLASH) {
            choices.addView(button("뜻 보기") {
                detail.text = explain(card)
                choices.removeAllViews()
                choices.addView(button("✔ 알아요") { record(card, true); advance() })
                choices.addView(button("✘ 몰라요") { record(card, false); advance() })
            })
            return
        }

        val answer = if (askForeign) card.back else card.front
        val distractors = cards.map { if (askForeign) it.back else it.front }
            .distinct().filter { it != answer }.shuffled().take(3)
        val buttons = ArrayList<Button>()
        (distractors + answer).shuffled().forEach { option ->
            val b = button(option) {}
            b.setOnClickListener {
                buttons.forEach { it.isEnabled = false }
                val ok = option == answer
                b.setBackgroundColor(if (ok) GREEN else RED)
                b.setTextColor(Color.WHITE)
                if (!ok) buttons.firstOrNull { it.text.toString() == answer }?.let {
                    it.setBackgroundColor(GREEN)
                    it.setTextColor(Color.WHITE)
                }
                detail.text = (if (ok) "정답!\n" else "오답\n") + explain(card)
                record(card, ok)
                nextButton.text = if (pos + 1 < order.size) "다음" else "결과 보기"
                nextButton.visibility = View.VISIBLE
            }
            buttons += b
            choices.addView(b)
        }
    }

    private fun record(card: Card, ok: Boolean) {
        if (ok) correct++ else wrong += card
        onAnswer(card, ok)
    }

    private fun advance() {
        pos++
        if (pos >= order.size) finish() else show()
    }

    private fun restart(list: List<Card>) {
        order = list.shuffled().take(MAX_QUESTIONS)
        wrong.clear()
        pos = 0
        correct = 0
        show()
    }

    private fun finish() {
        progress.text = "결과"
        prompt.text = "$correct / ${order.size}"
        speakButton.visibility = View.GONE
        nextButton.visibility = View.GONE
        detail.text = if (wrong.isEmpty()) "모두 맞혔어요! 👏"
                      else "틀린 단어\n\n" + wrong.joinToString("\n") { "${it.front}  =  ${it.back}" }
        choices.removeAllViews()
        if (wrong.isNotEmpty()) {
            val again = wrong.toList()
            choices.addView(button("틀린 것만 다시") { restart(again) })
        }
        choices.addView(button("처음부터 다시") { restart(cards) })
        choices.addView(button("닫기") { onClose() })
    }
}