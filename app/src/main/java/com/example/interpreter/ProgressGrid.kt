package com.example.interpreter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

// Progress of one language combination at a glance: a row per place, the first cell its question
// list and then its 10 dialogues, coloured by state.
class ProgressGrid(context: Context) : View(context) {

    private var rows: List<IntArray> = emptyList()
    private var names: List<String> = emptyList()
    private val d = resources.displayMetrics.density
    // Cell size follows the width the dialog gives, so all 11 cells always fit on screen.
    private var cell = 18 * d
    private val gap = 3 * d
    private val labelW = 92 * d
    private val rowH get() = cell + gap
    private val box = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f * d
        color = Ui.INK_SOFT
    }

    fun set(names: List<String>, rows: List<IntArray>) {
        this.names = names
        this.rows = rows
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = (labelW + 11 * (18 * d + gap)).toInt()
        val w = resolveSize(wanted, widthMeasureSpec)
        cell = ((w - labelW - 11 * gap) / 11f).coerceIn(8 * d, 18 * d)
        val h = (rows.size * rowH + gap).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        rows.forEachIndexed { r, row ->
            val y = gap + r * rowH
            val name = names.getOrNull(r).orEmpty()
            val label = "${r + 1}. " + if (name.length > 7) name.take(7) + "…" else name
            canvas.drawText(label, 0f, y + cell * 0.75f, text)
            row.forEachIndexed { c, state ->
                val x = labelW + c * (cell + gap)
                box.set(x, y, x + cell, y + cell)
                fill.color = color(state)
                // The question list cell is drawn as a circle to set it apart from the dialogues.
                if (c == 0) canvas.drawOval(box, fill) else canvas.drawRoundRect(box, 4 * d, 4 * d, fill)
            }
        }
    }

    companion object {
        fun color(state: Int) = when (state) {
            ContentBuilder.DONE -> Color.rgb(34, 197, 94)
            ContentBuilder.NEEDS_MEANING -> Color.rgb(250, 191, 36)
            ContentBuilder.BROKEN -> Color.rgb(239, 68, 68)
            else -> Color.rgb(226, 229, 236)
        }
    }
}
