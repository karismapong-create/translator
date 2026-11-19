package com.example.interpreter

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Export and import of content packages, reachable from Settings and from the book shelf. */
object PackUi {
    /** Set after an import, so open screens can refresh. */
    @Volatile var changed = false

    /** Settings entry: choose export or import. */
    fun menu(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle(StudyText.t("pack_title"))
            .setItems(arrayOf(StudyText.t("pack_export"), StudyText.t("pack_import"))) { _, which ->
                if (which == 0) export(activity) else activity.startActivity(Intent(activity, PackImportActivity::class.java))
            }
            .setNegativeButton(I18n.t("close"), null)
            .show().also { Ui.watch(it) }
    }

    fun export(activity: Activity, onStatus: (String) -> Unit = {}) {
        val books = BookStore.shelf(activity)
        val labels = (books.map { "📚 " + it.title } + StudyText.t("pack_situations")).toTypedArray()
        val checked = BooleanArray(labels.size) { true }
        AlertDialog.Builder(activity)
            .setTitle(StudyText.t("pack_export_title"))
            .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
            .setPositiveButton(StudyText.t("pack_export_go")) { _, _ ->
                val ids = books.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                val situations = checked.last()
                if (ids.isEmpty() && !situations) return@setPositiveButton
                val app = activity.applicationContext
                val main = Handler(Looper.getMainLooper())
                onStatus(StudyText.t("pack_working"))
                Toast.makeText(app, StudyText.t("pack_working"), Toast.LENGTH_SHORT).show()
                Thread {
                    val msg = try {
                        val name = ContentPack.export(app, ids, situations) { p -> main.post { onStatus(StudyText.t("pack_working") + " " + p) } }
                        StudyText.f("pack_exported", name)
                    } catch (e: Exception) { StudyText.f("failed", e.message ?: "") }
                    main.post {
                        onStatus(msg)
                        Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            .setNegativeButton(I18n.t("close"), null)
            .show().also { Ui.watch(it) }
    }
}

// Opens the file picker for a content package, imports it in the background, then closes.
class PackImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        try {
            @Suppress("DEPRECATION") startActivityForResult(pick, 1)
        } catch (_: Exception) {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { finish(); return }
        val app = applicationContext
        val main = Handler(Looper.getMainLooper())
        Toast.makeText(app, StudyText.t("pack_working"), Toast.LENGTH_SHORT).show()
        Thread {
            val msg = try {
                val s = app.contentResolver.openInputStream(uri)?.use { ContentPack.import(app, it) }
                    ?: throw IllegalStateException("can't open")
                if (s.isEmpty()) StudyText.t("pack_nothing")
                else StudyText.f("pack_imported", s.books, s.lessons, s.pictures, s.situations)
            } catch (e: Exception) { StudyText.f("failed", e.message ?: "") }
            PackUi.changed = true
            main.post { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
        }.start()
        finish()
    }
}
