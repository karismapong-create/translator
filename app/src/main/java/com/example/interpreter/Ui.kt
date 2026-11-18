package com.example.interpreter

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

// Shared look of the app: colours, rounded surfaces, and icons.
//
// Icons are Google Material Symbols (Apache License 2.0, free for commercial use; the licence
// text ships in assets/licenses). Many labels in the app start with an emoji; iconize() swaps
// that emoji for the matching vector icon, so every screen gets the same crisp, consistent
// icon set without rewriting each translated string.
object Ui {
    val PRIMARY = Color.rgb(47, 91, 234)
    val PRIMARY_DARK = Color.rgb(30, 63, 184)
    val PRIMARY_SOFT = Color.rgb(234, 240, 255)
    val ACCENT = Color.rgb(255, 176, 32)
    val INK = Color.rgb(26, 31, 54)
    val INK_SOFT = Color.rgb(91, 97, 120)
    val LINE = Color.rgb(227, 230, 239)
    val SURFACE = Color.WHITE
    val PAGE = Color.rgb(245, 247, 252)
    val GOOD = Color.rgb(22, 163, 74)
    val BAD = Color.rgb(220, 38, 38)

    private val icons: Map<String, Int> = mapOf(
        // actions
        "🔊" to R.drawable.ic_volume_up, "☆" to R.drawable.ic_star, "★" to R.drawable.ic_star_fill,
        "💡" to R.drawable.ic_lightbulb, "⚠" to R.drawable.ic_warning, "🎙" to R.drawable.ic_mic,
        "🎤" to R.drawable.ic_mic, "⬇" to R.drawable.ic_download, "📥" to R.drawable.ic_download,
        "🔍" to R.drawable.ic_search, "📋" to R.drawable.ic_content_copy, "📍" to R.drawable.ic_location_on,
        "⏹" to R.drawable.ic_stop, "▶" to R.drawable.ic_play_arrow, "⏸" to R.drawable.ic_pause,
        "🧑‍💼" to R.drawable.ic_support_agent, "🙋" to R.drawable.ic_person_raised_hand,
        "📷" to R.drawable.ic_photo_camera, "🌐" to R.drawable.ic_language,
        "🔴" to R.drawable.ic_radio_button_checked, "🔑" to R.drawable.ic_key, "🤖" to R.drawable.ic_smart_toy,
        "✏" to R.drawable.ic_edit, "✍" to R.drawable.ic_edit_note, "←" to R.drawable.ic_arrow_back,
        "🎭" to R.drawable.ic_theater_comedy, "👏" to R.drawable.ic_thumb_up, "✕" to R.drawable.ic_close,
        "⇄" to R.drawable.ic_swap_horiz, "📖" to R.drawable.ic_menu_book, "📦" to R.drawable.ic_inventory_2,
        "🛠" to R.drawable.ic_build, "✔" to R.drawable.ic_check_circle, "✘" to R.drawable.ic_cancel,
        "ℹ" to R.drawable.ic_info, "⚙" to R.drawable.ic_settings,
        "🗣" to R.drawable.ic_record_voice_over, "🔗" to R.drawable.ic_link, "⛓" to R.drawable.ic_link_off,
        "🕘" to R.drawable.ic_history, "🎧" to R.drawable.ic_headphones, "✨" to R.drawable.ic_auto_awesome,
        "💬" to R.drawable.ic_forum,
        // places
        "✈" to R.drawable.ic_flight_takeoff, "🛂" to R.drawable.ic_badge, "🧳" to R.drawable.ic_luggage,
        "🛍" to R.drawable.ic_shopping_bag, "💱" to R.drawable.ic_currency_exchange, "🚇" to R.drawable.ic_subway,
        "🚌" to R.drawable.ic_directions_bus, "🚆" to R.drawable.ic_train, "🚕" to R.drawable.ic_local_taxi,
        "🚗" to R.drawable.ic_car_rental, "🏨" to R.drawable.ic_hotel, "🍽" to R.drawable.ic_restaurant,
        "☕" to R.drawable.ic_local_cafe, "🏪" to R.drawable.ic_storefront, "🛒" to R.drawable.ic_shopping_cart,
        "👕" to R.drawable.ic_apparel, "💊" to R.drawable.ic_medication, "🏥" to R.drawable.ic_local_hospital,
        "🚻" to R.drawable.ic_wc, "🏛" to R.drawable.ic_museum, "🎢" to R.drawable.ic_attractions,
        "🏊" to R.drawable.ic_pool, "♨" to R.drawable.ic_hot_tub, "🏖" to R.drawable.ic_beach_access,
        "⛰" to R.drawable.ic_landscape, "🥾" to R.drawable.ic_hiking, "🏞" to R.drawable.ic_kayaking,
        "👮" to R.drawable.ic_local_police, "💇" to R.drawable.ic_content_cut
    )
    // Longest keys first, so a multi-character emoji wins over its first character.
    private val keys = icons.keys.sortedByDescending { it.length }

    private fun clean(s: CharSequence) = s.toString().replace("️", "")

    // The icon for a text that starts with a known emoji, with the rest of the text.
    fun split(text: CharSequence): Pair<Int, String>? {
        val t = clean(text)
        val k = keys.firstOrNull { t.startsWith(it) } ?: return null
        return icons.getValue(k) to t.substring(k.length).trimStart(' ', ' ')
    }

    fun iconRes(emoji: String): Int? = icons[clean(emoji)]

    fun icon(ctx: Context, res: Int, tint: Int, sizePx: Int): Drawable =
        ctx.getDrawable(res)!!.mutate().apply {
            setTint(tint)
            setBounds(0, 0, sizePx, sizePx)
        }

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    // Rounded box with an optional border.
    fun rounded(fill: Int, radiusDp: Int, ctx: Context, stroke: Int = 0, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (stroke != 0) setStroke(dp(ctx, strokeDp), stroke)
        }

    fun card(ctx: Context) = rounded(SURFACE, 18, ctx, LINE)

    // A rounded surface that ripples when touched.
    fun pressable(ctx: Context, fill: Int = SURFACE, radiusDp: Int = 18, stroke: Int = LINE): Drawable =
        android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.argb(40, 47, 91, 234)),
            rounded(fill, radiusDp, ctx, stroke),
            null
        )

    // An icon centred in a soft blue circle, for the place grid and screen titles.
    fun circleIcon(ctx: Context, res: Int, sizeDp: Int): Drawable {
        val size = dp(ctx, sizeDp)
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(PRIMARY_SOFT)
        }
        val glyph = ctx.getDrawable(res)!!.mutate().apply { setTint(PRIMARY) }
        val inset = size / 4
        return android.graphics.drawable.LayerDrawable(arrayOf(circle, glyph)).apply {
            setLayerInset(1, inset, inset, inset, inset)
            setBounds(0, 0, size, size)
        }
    }

    // Turns a button into the solid main action.
    fun primary(b: Button) {
        b.setBackgroundResource(R.drawable.btn_primary_bg)
        b.setTextColor(Color.WHITE)
        b.compoundDrawables.forEach { it?.setTint(Color.WHITE) }
        iconize(b)
    }

    // Label each view was given by iconize(), to tell our own icon from a later text change.
    private val given = java.util.WeakHashMap<TextView, String>()

    // Replaces a leading emoji with a tinted vector icon. "emoji\nname" puts the icon on top.
    fun iconize(tv: TextView) {
        val text = tv.text ?: return
        val first = if (text.isEmpty()) ' ' else text[0]
        val maybeEmoji = text.isNotEmpty() && !Character.isLetterOrDigit(first) && first > '\u007F'
        val hit = if (maybeEmoji) split(text) else null
        if (hit == null) {
            // Text replaced by one without an emoji: drop the icon we added before.
            val mine = given[tv]
            if (mine != null && mine != text.toString()) {
                tv.setCompoundDrawables(null, null, null, null)
                given.remove(tv)
            }
            return
        }
        val (res, rest) = hit
        val top = rest.startsWith("\n")
        val label = rest.trimStart('\n')
        val size = if (top) dp(tv.context, 30) else (tv.textSize * 1.25f).toInt()
        val tint = if (top) PRIMARY else tv.currentTextColor
        val d = icon(tv.context, res, tint, size)
        given[tv] = label
        tv.text = label
        tv.compoundDrawablePadding = if (label.isEmpty()) 0 else dp(tv.context, if (top) 8 else 6)
        if (top) tv.setCompoundDrawables(null, d, null, null) else tv.setCompoundDrawables(d, null, null, null)
    }

    // Iconizes every text in a view tree.
    fun iconizeTree(v: View) {
        if (v is TextView && v !is android.widget.EditText) iconize(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) iconizeTree(v.getChildAt(i))
    }

    // Keeps a screen iconized as its views and labels change.
    fun watch(root: View) {
        iconizeTree(root)
        root.viewTreeObserver.addOnGlobalLayoutListener { iconizeTree(root) }
    }

    fun watch(dialog: Dialog): Dialog {
        dialog.window?.decorView?.let { watch(it) }
        return dialog
    }

    fun bold(tv: TextView) = tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
}
