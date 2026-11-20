package com.example.interpreter

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService

// Translation engine picker and per-engine settings: keys, addresses and models.
class EngineSettings(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    private val net: ExecutorService,
    private val onUse: (String) -> Unit,
    private val test: (String) -> String
) {
    companion object {
        const val DEEPL_KEY_URL = "https://www.deepl.com/pro-api"
    }

    private val ids = listOf("mlkit", OnDevice.ID, "deepl") + Providers.llms.map { it.id }

    private fun p(key: String) = prefs.getString(key, "").orEmpty().trim()
    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(activity, Ui.plain(msg), Toast.LENGTH_SHORT).show()

    fun currentId(): String = prefs.getString("engine", null) ?: if (p("deepl").isNotEmpty()) "deepl" else "mlkit"

    fun ready(id: String): Boolean = when (id) {
        "deepl" -> p("deepl").isNotEmpty()
        "mlkit" -> false
        OnDevice.ID -> OnDevice.ready
        else -> Providers.ready(prefs, id)
    }

    fun active(): String = currentId().let { if (it != "mlkit" && ready(it)) it else "mlkit" }

    fun label(id: String): String = if (id == "mlkit") I18n.t("engine_device") else Providers.label(id)

    fun open() {
        val current = ids.indexOf(currentId()).coerceAtLeast(0)
        val labels = ids.map { id ->
            label(id) + if (id != "mlkit" && !ready(id)) "  " + I18n.t("e_need") else ""
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(I18n.t("e_title"))
            .setSingleChoiceItems(labels, current) { d, which ->
                d.dismiss()
                val id = ids[which]
                when (id) {
                    "mlkit" -> onUse("mlkit")
                    OnDevice.ID -> OnDevice.openDialog(activity, onUse)
                    else -> config(id)
                }
            }
            .setNegativeButton(I18n.t("close"), null)
            .show().also { Ui.watch(it) }
    }

    // A short, readable reason first; the server's own message after it.
    fun describe(e: Exception): String = when (e) {
        is Providers.HttpException -> {
            val msg = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(e.body)?.groupValues?.get(1)
                ?.replace("\\n", " ")?.trim()?.take(220) ?: e.body.take(220)
            val why = when (e.code) {
                400 -> I18n.t("err_400")
                401, 403 -> I18n.t("err_key")
                402 -> I18n.t("err_402")
                404 -> I18n.t("err_404")
                429 -> I18n.t("err_429")
                in 500..599 -> I18n.t("err_5xx")
                else -> ""
            }
            listOf("HTTP ${e.code}", why, msg).filter { it.isNotBlank() }.joinToString("\n")
        }
        is java.net.SocketTimeoutException -> I18n.t("err_timeout")
        is java.net.ConnectException, is java.net.UnknownHostException, is java.net.NoRouteToHostException ->
            I18n.t("err_connect")
        else -> e.toString()
    }

    private fun openLink(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast(I18n.t("browser_fail"))
        }
    }

    private fun pasteInto(ed: EditText) {
        val clip = (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString()?.trim()
        if (text.isNullOrEmpty()) toast(I18n.t("paste_empty")) else ed.setText(text)
    }

    private fun config(id: String) {
        val pr = Providers.find(id)
        val keyPref = if (id == "deepl") "deepl" else "${id}_key"
        val keyUrl = if (id == "deepl") DEEPL_KEY_URL else pr?.keyUrl.orEmpty()
        val help = when {
            pr == null -> I18n.t("deepl_help")
            I18n.lang == "ko" -> pr.help
            keyUrl.isNotEmpty() -> I18n.t("e_generic_help")
            else -> ""
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        // Help and test results live inside the one scrolling area, so a long error never hides
        // the fields below it.
        val status = TextView(activity).apply {
            text = help
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setTextIsSelectable(true)
            visibility = if (help.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
        box.addView(status)
        val scroll = ScrollView(activity).apply { addView(box) }
        fun say(msg: String) {
            status.text = msg
            status.visibility = android.view.View.VISIBLE
            scroll.post { scroll.smoothScrollTo(0, 0) }
        }
        fun field(label: String, value: String, hintText: String): EditText {
            box.addView(TextView(activity).apply {
                text = label
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(0, dp(10), 0, 0)
            })
            val ed = EditText(activity).apply {
                setText(value)
                setHint(hintText)
                textSize = 15f
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
            box.addView(ed)
            return ed
        }

        if (keyUrl.isNotEmpty()) {
            box.addView(Button(activity).apply {
                text = I18n.t(if (id == "local") "e_ollama" else "e_getkey")
                setOnClickListener { openLink(keyUrl) }
            })
            box.addView(TextView(activity).apply {
                text = keyUrl
                textSize = 12f
                setTextColor(Color.GRAY)
                setTextIsSelectable(true)
            })
        }
        val urlEd = if (pr?.editableUrl == true) {
            field(I18n.t("e_address"), Providers.rawUrl(prefs, id), if (id == "local") "http://192.168.0.10:11434/v1" else "https://…/v1")
        } else null
        val keyEd = field(
            I18n.t(if (pr == null || pr.needsKey) "e_key" else "e_key_opt"),
            p(keyPref),
            pr?.keyHint?.ifEmpty { "API key" } ?: "xxxxxxxx-…:fx"
        )
        box.addView(Button(activity).apply {
            text = I18n.t("e_paste")
            setOnClickListener { pasteInto(keyEd) }
        })
        val modelEd = if (pr != null) field(I18n.t("e_model"), Providers.model(prefs, id), I18n.t("e_model_hint")) else null
        val listButton = if (pr != null) Button(activity).apply { text = I18n.t("e_models") }.also { box.addView(it) } else null

        fun store() {
            val editor = prefs.edit()
            urlEd?.let { editor.putString("${id}_url", it.text.toString().trim()) }
            editor.putString(keyPref, keyEd.text.toString().trim())
            modelEd?.let { editor.putString("${id}_model", it.text.toString().trim()) }
            editor.apply()
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(label(id))
            .setView(scroll)
            .setPositiveButton(I18n.t("e_save")) { _, _ -> store(); onUse(id) }
            .setNeutralButton(I18n.t("e_test"), null)
            .setNegativeButton(I18n.t("cancel"), null)
            .show().also { Ui.watch(it) }

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            store()
            say(I18n.t("e_testing"))
            net.execute {
                val r: Any = try { test(id) } catch (ex: Exception) { ex }
                activity.runOnUiThread {
                    say(
                        if (r is String) I18n.f("e_ok", r) else I18n.f("e_fail", describe(r as Exception))
                    )
                }
            }
        }

        listButton?.setOnClickListener {
            store()
            say(I18n.t("e_loading"))
            net.execute {
                val r: Any = try { Providers.fetchModels(prefs, id) } catch (ex: Exception) { ex }
                activity.runOnUiThread {
                    val raw = (r as? List<*>)?.map { it.toString() }.orEmpty()
                    val names = if (id == "gemini") Providers.geminiOrder(raw) else raw
                    val shown = if (id == "gemini") names.map { n ->
                        Providers.geminiNote(n).let { if (it.isEmpty()) n else "$n\n   $it" }
                    } else names
                    if (names.isEmpty()) {
                        say(I18n.t("e_models_fail") + ((r as? Exception)?.let { "\n" + describe(it) } ?: ""))
                    } else {
                        say(I18n.f("e_found", names.size))
                        AlertDialog.Builder(activity)
                            .setTitle(I18n.t("e_pick"))
                            .setItems(shown.toTypedArray()) { _, w ->
                                modelEd?.setText(names[w])
                                say(I18n.f("e_selected", names[w]))
                            }
                            .show().also { Ui.watch(it) }
                    }
                }
            }
        }
    }
}