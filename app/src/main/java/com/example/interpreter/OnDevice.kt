package com.example.interpreter

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

// On-device AI translation with Gemini Nano (ML Kit Prompt API). Translates directly between two languages
// without going through English, works offline, and costs nothing per request. Only on supported phones.
object OnDevice {
    const val ID = "nano"
    private const val UNKNOWN = -1

    @Volatile var status: Int = UNKNOWN
        private set
    // Error message from AICore when the check fails, e.g. "606-FEATURE_NOT_FOUND".
    @Volatile var lastError: String = ""
        private set
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val model: GenerativeModel? by lazy {
        try { Generation.getClient() } catch (_: Throwable) { null }
    }

    val ready: Boolean get() = status == FeatureStatus.AVAILABLE

    fun label(): String = NanoText.t("label")

    // Runs on a background thread.
    fun checkNow(): Int {
        val m = model
        lastError = ""
        status = if (m == null) {
            lastError = "ML Kit GenAI client could not be created"
            FeatureStatus.UNAVAILABLE
        } else try {
            runBlocking { m.checkStatus() }
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            FeatureStatus.UNAVAILABLE
        }
        return status
    }

    fun refresh() {
        worker.execute { checkNow() }
    }

    private fun statusText(s: Int): String = NanoText.t(
        when (s) {
            FeatureStatus.AVAILABLE -> "st_available"
            FeatureStatus.DOWNLOADABLE -> "st_downloadable"
            FeatureStatus.DOWNLOADING -> "st_downloading"
            UNKNOWN -> "st_checking"
            else -> "st_unavailable"
        }
    )

    private fun clean(s: String) =
        s.replace(Regex("(?s)<think>.*?</think>"), "").trim().trim('"', '“', '”', '「', '」').trim()

    // Runs on a background thread. Throws on failure.
    fun translate(text: String, source: String, target: String, context: String): String {
        val m = model ?: throw IllegalStateException("Gemini Nano unavailable")
        val s = Languages.byCode(source).english
        val t = Languages.byCode(target).english
        val prompt = buildString {
            append("You are a professional interpreter. Translate the $s text below into natural, polite spoken $t. ")
            append("It may be an unfinished fragment of speech; translate it as it is. ")
            append("Keep codes such as XZ1 exactly as written. Reply with the $t translation only, ")
            append("with no notes, quotes or romanization.\n")
            if (context.isNotBlank()) append("\nEarlier conversation (context only, do not translate):\n$context\n")
            append("\n$s text:\n$text\n\n$t translation:")
        }
        val response = runBlocking {
            m.generateContent(generateContentRequest(TextPart(prompt)) { temperature = 0.1f })
        }
        val out = clean(response.candidates.firstOrNull()?.text.orEmpty())
        if (out.isBlank()) throw IllegalStateException("empty")
        return out
    }

    /* ---------- Settings window ---------- */

    // Shows what this engine is, the supported phones, and whether it works on this phone.
    fun openDialog(activity: Activity, onUse: (String) -> Unit) {
        val density = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * density).toInt()
        fun text(value: String, size: Float, color: Int = Color.DKGRAY, bold: Boolean = false) = TextView(activity).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, px(6), 0, px(2))
        }
        val green = Color.rgb(46, 160, 67)
        val red = Color.rgb(207, 34, 46)

        val statusView = text(statusText(status), 15f, Color.BLACK, true)
        val helpView = text("", 13f)
        val aicoreButton = Button(activity).apply {
            text = NanoText.t("btn_aicore")
            isAllCaps = false
            setOnClickListener {
                val pkg = "com.google.android.aicore"
                try {
                    activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$pkg")))
                } catch (_: Exception) {
                    activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
                }
            }
        }
        val resultView = text("", 14f)
        val downloadButton = Button(activity).apply {
            text = NanoText.t("btn_download")
            isAllCaps = false
            visibility = View.GONE
        }
        val useButton = Button(activity).apply {
            text = NanoText.t("btn_use")
            isAllCaps = false
        }
        val testButton = Button(activity).apply {
            text = NanoText.t("btn_test")
            isAllCaps = false
        }

        fun show(s: Int) {
            statusView.text = statusText(s)
            statusView.setTextColor(
                when (s) {
                    FeatureStatus.AVAILABLE -> green
                    FeatureStatus.UNAVAILABLE -> red
                    else -> Color.BLACK
                }
            )
            downloadButton.visibility = if (s == FeatureStatus.DOWNLOADABLE) View.VISIBLE else View.GONE
            val unavailable = s == FeatureStatus.UNAVAILABLE
            aicoreButton.visibility = if (unavailable) View.VISIBLE else View.GONE
            helpView.visibility = if (unavailable) View.VISIBLE else View.GONE
            helpView.text = NanoText.t("help_unavailable") +
                if (lastError.isNotBlank()) "\n\n" + NanoText.f("err_detail", lastError) else ""
            useButton.isEnabled = s == FeatureStatus.AVAILABLE
            testButton.isEnabled = s == FeatureStatus.AVAILABLE
        }
        fun check() {
            statusView.text = statusText(UNKNOWN)
            worker.execute {
                val s = checkNow()
                main.post { show(s) }
            }
        }

        downloadButton.setOnClickListener {
            downloadButton.isEnabled = false
            resultView.text = NanoText.t("dl_started")
            worker.execute {
                val m = model
                var error = ""
                try {
                    if (m != null) runBlocking {
                        m.download().collect { d ->
                            when (d) {
                                is DownloadStatus.DownloadProgress -> {
                                    val mb = d.totalBytesDownloaded / 1_000_000
                                    main.post { resultView.text = NanoText.f("dl_progress", mb) }
                                }
                                is DownloadStatus.DownloadFailed -> error = d.e.message ?: "?"
                                else -> {}
                            }
                        }
                    }
                } catch (e: Throwable) {
                    error = e.message ?: e.toString()
                }
                val s = checkNow()
                main.post {
                    downloadButton.isEnabled = true
                    resultView.text = if (s == FeatureStatus.AVAILABLE) NanoText.t("dl_done")
                                      else NanoText.f("dl_failed", error)
                    show(s)
                }
            }
        }
        testButton.setOnClickListener {
            val home = Languages.home(activity.getSharedPreferences("interpreter", Activity.MODE_PRIVATE)).code
            val target = if (home == "ja") "ko" else "ja"
            val sample = NanoText.t("sample")
            resultView.text = NanoText.t("testing")
            worker.execute {
                val r: String = try {
                    "${Languages.byCode(target).native}: " + translate(sample, home, target, "")
                } catch (e: Throwable) {
                    NanoText.f("dl_failed", e.message ?: e.toString())
                }
                main.post { resultView.text = r }
            }
        }

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), px(8))
            addView(text(NanoText.t("intro"), 14f))
            addView(text(NanoText.t("languages"), 13f, Color.GRAY))
            addView(text(NanoText.f("this_phone", "${Build.MANUFACTURER} ${Build.MODEL}"), 14f, Color.BLACK))
            addView(statusView)
            addView(helpView)
            addView(aicoreButton)
            addView(Button(activity).apply {
                text = NanoText.t("btn_check")
                isAllCaps = false
                setOnClickListener { check() }
            })
            addView(downloadButton)
            addView(testButton)
            addView(useButton)
            addView(resultView)
            addView(text(NanoText.t("supported_title"), 15f, Color.BLACK, true).apply { setPadding(0, px(16), 0, px(4)) })
            addView(text(SUPPORTED, 12f))
            addView(text(NanoText.t("supported_note"), 12f, Color.GRAY))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(NanoText.t("label"))
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton(I18n.t("close"), null)
            .show()
        useButton.setOnClickListener {
            dialog.dismiss()
            onUse(ID)
        }
        show(status)
        check()
    }

    // Phones Google lists for the ML Kit Prompt API (developers.google.com/ml-kit/genai).
    private const val SUPPORTED =
        "Samsung: Galaxy S25, S25+, S25 Edge, S25 Ultra, S26, S26+, S26 FE, S26 Ultra, " +
        "Galaxy Z Flip7, Z Flip8, Z Fold7, Z Fold8, Z Fold8 Ultra, Z TriFold, Galaxy Tab S12+, Tab S12 Ultra\n" +
        "Google: Pixel 9, 9 Pro, 9 Pro XL, 9 Pro Fold, Pixel 10, 10 Pro, 10 Pro XL, 10 Pro Fold, " +
        "Pixel 11, 11 Pro, 11 Pro XL, 11 Pro Fold\n" +
        "Xiaomi: Xiaomi 15, 15T, 15T Pro, 15 Ultra, 17, 17T, 17T Pro, 17 Ultra, Pad Mini, Leitzphone\n" +
        "POCO / Redmi: POCO F7 Ultra, F8 Pro, F8 Ultra, F9 Pro, X7 Pro, X8 Pro, X8 Pro Max, Redmi Turbo 5\n" +
        "OPPO: Find N5, N6, X8, X8 Pro, X9, X9 Pro, X9s, X9 Ultra, X10, X10 Pro Max, " +
        "Reno 14 Pro, Reno 15 Pro / Pro Mini / Pro Max, Reno 16 Pro\n" +
        "OnePlus: 13, 13s, 15, 15R, Pad 3\n" +
        "vivo / iQOO: vivo X200, X200 Pro, X200T, X200 FE, X300, X300 FE, X300 Pro, X300 Ultra, " +
        "X500 Pro, X500 Pro Max, X Fold3 Pro, X Fold5, X Fold6, T4 Ultra, iQOO 13, iQOO 15\n" +
        "Honor: 600 Pro, Magic 7, Magic 7 Pro, Magic 7 RSR, Magic 8 Pro, Magic V5, Magic V6, MagicPad 4\n" +
        "Motorola: Edge 70 Pro, Edge 70 Pro+, Razr 60 Ultra, Razr Ultra 2025, Razr Fold, Signature\n" +
        "realme: GT 7, GT 7T, GT 7 Pro, GT 8 Pro\n" +
        "Sony: Xperia 1 VIII · Sharp: AQUOS R11\n" +
        "Lenovo: Idea Tab Pro Gen 2, Legion Tab Gen 5, Yoga Tab Plus Gen 2"
}

// Screen text for the on-device AI engine. Order in m(): ko, ja, en, fr, es, de, zh.
private object NanoText {

    fun t(key: String): String = table[key]?.let { it[I18n.lang] ?: it["en"] ?: it["ko"] } ?: key

    fun f(key: String, vararg args: Any): String = String.format(t(key), *args)

    private fun m(ko: String, ja: String, en: String, fr: String, es: String, de: String, zh: String) =
        mapOf("ko" to ko, "ja" to ja, "en" to en, "fr" to fr, "es" to es, "de" to de, "zh" to zh)

    private val table: Map<String, Map<String, String>> = mapOf(
        "label" to m("기기 AI (Gemini Nano)", "端末AI (Gemini Nano)", "On-device AI (Gemini Nano)", "IA sur l'appareil (Gemini Nano)", "IA en el dispositivo (Gemini Nano)", "Geräte-KI (Gemini Nano)", "设备端 AI (Gemini Nano)"),
        "intro" to m(
            "폰 안의 AI(Gemini Nano)로 번역해요. 인터넷 없이 동작하고, 요금이 없고, 대화 내용이 폰 밖으로 나가지 않아요. 기본 기기 번역과 달리 영어를 거치지 않고 두 언어 사이를 바로 번역해요. 지원되는 폰에서만 쓸 수 있어요.",
            "スマホ内のAI(Gemini Nano)で翻訳します。インターネット不要・無料で、会話内容は端末の外に出ません。標準の端末内翻訳と違い、英語を経由せず2言語間を直接翻訳します。対応機種でのみ使えます。",
            "Translates with the AI inside your phone (Gemini Nano). It works offline, is free, and your conversation never leaves the phone. Unlike the basic on-device translation, it translates directly between the two languages without going through English. Only on supported phones.",
            "Traduit avec l'IA intégrée au téléphone (Gemini Nano). Fonctionne hors ligne, gratuitement, et vos conversations restent sur le téléphone. Contrairement à la traduction de base, elle traduit directement sans passer par l'anglais. Uniquement sur les téléphones compatibles.",
            "Traduce con la IA del teléfono (Gemini Nano). Funciona sin conexión, es gratis y tus conversaciones no salen del teléfono. A diferencia de la traducción básica, traduce directamente sin pasar por el inglés. Solo en teléfonos compatibles.",
            "Übersetzt mit der KI im Handy (Gemini Nano). Funktioniert offline, kostenlos, und Gespräche verlassen das Handy nicht. Anders als die einfache Geräteübersetzung übersetzt sie direkt, ohne Umweg über Englisch. Nur auf unterstützten Handys.",
            "使用手机内置 AI(Gemini Nano)翻译。无需联网、免费，对话内容不会离开手机。与基础设备翻译不同，它在两种语言之间直接翻译，不经过英语。仅支持部分机型。"
        ),
        "languages" to m(
            "Google이 검증한 언어는 영어와 한국어예요. 일본어 등 다른 언어는 품질을 직접 확인해 보세요.",
            "Googleが検証済みの言語は英語と韓国語です。日本語などは品質を実際に確かめてください。",
            "Google has validated English and Korean. Check the quality yourself for other languages such as Japanese.",
            "Google a validé l'anglais et le coréen. Vérifiez vous-même la qualité pour les autres langues.",
            "Google ha validado inglés y coreano. Comprueba tú mismo la calidad en otros idiomas.",
            "Google hat Englisch und Koreanisch geprüft. Prüfe die Qualität für andere Sprachen selbst.",
            "Google 已验证英语和韩语。日语等其他语言请自行确认质量。"
        ),
        "this_phone" to m("이 폰: %s", "この端末: %s", "This phone: %s", "Ce téléphone : %s", "Este teléfono: %s", "Dieses Handy: %s", "本机：%s"),
        "st_checking" to m("확인 중…", "確認中…", "Checking…", "Vérification…", "Comprobando…", "Wird geprüft…", "检查中…"),
        "st_available" to m("✔ 이 폰에서 사용할 수 있어요", "✔ この端末で使えます", "✔ Available on this phone", "✔ Disponible sur ce téléphone", "✔ Disponible en este teléfono", "✔ Auf diesem Handy verfügbar", "✔ 本机可用"),
        "st_downloadable" to m("이 폰에서 쓸 수 있어요. 먼저 AI 모델을 받아야 해요.", "この端末で使えます。先にAIモデルをダウンロードしてください。", "Supported. The AI model needs to be downloaded first.", "Compatible. Le modèle d'IA doit d'abord être téléchargé.", "Compatible. Primero hay que descargar el modelo de IA.", "Unterstützt. Das KI-Modell muss zuerst geladen werden.", "本机支持，需要先下载 AI 模型。"),
        "st_downloading" to m("AI 모델을 받는 중이에요. 잠시 후 다시 확인하세요.", "AIモデルをダウンロード中です。しばらくして再確認してください。", "The AI model is downloading. Check again in a while.", "Téléchargement du modèle en cours. Revérifiez plus tard.", "Descargando el modelo. Vuelve a comprobar en un rato.", "Modell wird geladen. Später erneut prüfen.", "正在下载 AI 模型，请稍后再检查。"),
        "st_unavailable" to m("✘ 지금은 이 폰에서 쓸 수 없어요", "✘ 現在この端末では使えません", "✘ Not available on this phone right now", "✘ Indisponible sur ce téléphone pour le moment", "✘ No disponible en este teléfono por ahora", "✘ Auf diesem Handy derzeit nicht verfügbar", "✘ 本机目前不可用"),
        "help_unavailable" to m(
            "지원 폰인데 이렇게 나오면 순서대로 해보세요.\n① 아래 버튼으로 Play 스토어의 'Android AICore'를 최신으로 업데이트하세요.\n② 와이파이에 연결한 채 폰을 다시 시작하세요. AICore가 설정을 받는 데 몇 분에서 몇 시간 걸릴 수 있어요.\n③ 그래도 안 되면 이 앱을 지우고 다시 설치하세요(오류 번호 601일 때).\n④ 잠금 해제된(루팅·부트로더 해제) 폰에서는 쓸 수 없어요.",
            "対応機種なのにこう表示される場合は、順に試してください。\n① 下のボタンでPlayストアの「Android AICore」を最新に更新します。\n② Wi-Fiにつないだまま端末を再起動します。AICoreの設定取得には数分〜数時間かかることがあります。\n③ それでもだめならこのアプリを再インストールします(エラー601の場合)。\n④ ブートローダーがアンロックされた端末では使えません。",
            "If your phone is on the list, try these in order:\n① Update “Android AICore” in the Play Store with the button below.\n② Restart the phone while on Wi-Fi. AICore can take a few minutes to a few hours to get its settings.\n③ If it still fails, reinstall this app (for error 601).\n④ Phones with an unlocked bootloader are not supported.",
            "Si votre téléphone est dans la liste, essayez dans l'ordre :\n① Mettez à jour « Android AICore » sur le Play Store avec le bouton ci-dessous.\n② Redémarrez le téléphone en Wi-Fi. AICore peut mettre de quelques minutes à quelques heures.\n③ Sinon, réinstallez cette appli (erreur 601).\n④ Les téléphones au bootloader déverrouillé ne sont pas pris en charge.",
            "Si tu teléfono está en la lista, prueba en orden:\n① Actualiza «Android AICore» en Play Store con el botón de abajo.\n② Reinicia el teléfono con Wi-Fi. AICore puede tardar de minutos a horas.\n③ Si sigue fallando, reinstala esta app (error 601).\n④ No funciona con el bootloader desbloqueado.",
            "Steht dein Handy auf der Liste, versuche der Reihe nach:\n① „Android AICore“ im Play Store über den Button unten aktualisieren.\n② Handy im WLAN neu starten. AICore braucht Minuten bis Stunden.\n③ Sonst diese App neu installieren (Fehler 601).\n④ Mit entsperrtem Bootloader nicht unterstützt.",
            "如果机型在列表中，请依次尝试：\n① 用下方按钮在 Play 商店更新“Android AICore”。\n② 连接 Wi-Fi 并重启手机。AICore 获取配置可能需要几分钟到几小时。\n③ 仍不行时重新安装本应用(错误 601)。\n④ 已解锁 bootloader 的手机不受支持。"
        ),
        "err_detail" to m("오류 내용: %s", "エラー内容: %s", "Error: %s", "Erreur : %s", "Error: %s", "Fehler: %s", "错误：%s"),
        "btn_aicore" to m("Android AICore 업데이트 (Play 스토어)", "Android AICoreを更新 (Playストア)", "Update Android AICore (Play Store)", "Mettre à jour Android AICore (Play Store)", "Actualizar Android AICore (Play Store)", "Android AICore aktualisieren (Play Store)", "更新 Android AICore (Play 商店)"),
        "btn_check" to m("다시 확인", "再確認", "Check again", "Revérifier", "Comprobar de nuevo", "Erneut prüfen", "重新检查"),
        "btn_download" to m("⬇ AI 모델 받기", "⬇ AIモデルをダウンロード", "⬇ Download AI model", "⬇ Télécharger le modèle", "⬇ Descargar modelo", "⬇ KI-Modell laden", "⬇ 下载 AI 模型"),
        "btn_use" to m("이 엔진 사용", "このエンジンを使う", "Use this engine", "Utiliser ce moteur", "Usar este motor", "Diese Engine nutzen", "使用此引擎"),
        "btn_test" to m("번역 테스트", "翻訳テスト", "Test translation", "Tester la traduction", "Probar traducción", "Übersetzung testen", "测试翻译"),
        "testing" to m("번역 중…", "翻訳中…", "Translating…", "Traduction…", "Traduciendo…", "Übersetzung…", "翻译中…"),
        "sample" to m("안녕하세요. 이 근처에 약국이 있나요?", "こんにちは。この近くに薬局はありますか？", "Hello. Is there a pharmacy near here?", "Bonjour. Y a-t-il une pharmacie près d'ici ?", "Hola. ¿Hay una farmacia cerca de aquí?", "Hallo. Gibt es hier in der Nähe eine Apotheke?", "你好。这附近有药店吗？"),
        "dl_started" to m("AI 모델 받는 중… 와이파이에 연결해 두세요.", "AIモデルをダウンロード中… Wi-Fiに接続してください。", "Downloading the AI model… Stay on Wi-Fi.", "Téléchargement du modèle… Restez en Wi-Fi.", "Descargando el modelo… Mantén la Wi-Fi.", "KI-Modell wird geladen… Im WLAN bleiben.", "正在下载 AI 模型… 请保持 Wi-Fi 连接。"),
        "dl_progress" to m("받는 중… %d MB", "ダウンロード中… %d MB", "Downloading… %d MB", "Téléchargement… %d Mo", "Descargando… %d MB", "Wird geladen… %d MB", "下载中… %d MB"),
        "dl_done" to m("✔ 준비됐어요. '번역 테스트'로 확인해 보세요.", "✔ 準備できました。「翻訳テスト」で確かめてください。", "✔ Ready. Try “Test translation”.", "✔ Prêt. Essayez « Tester la traduction ».", "✔ Listo. Prueba «Probar traducción».", "✔ Bereit. „Übersetzung testen“ ausprobieren.", "✔ 已就绪，请点“测试翻译”。"),
        "dl_failed" to m("실패: %s", "失敗: %s", "Failed: %s", "Échec : %s", "Error: %s", "Fehlgeschlagen: %s", "失败：%s"),
        "supported_title" to m("지원 폰 (Google 발표 기준)", "対応機種 (Google発表)", "Supported phones (per Google)", "Téléphones compatibles (selon Google)", "Teléfonos compatibles (según Google)", "Unterstützte Handys (laut Google)", "支持的机型(据 Google)"),
        "supported_note" to m(
            "같은 모델이라도 메모리, 안드로이드 버전, AICore 업데이트 상태에 따라 못 쓸 수 있어요. 위의 확인 결과가 가장 정확해요.",
            "同じ機種でもメモリ、Androidのバージョン、AICoreの更新状況によって使えない場合があります。上の確認結果が最も正確です。",
            "Even on a listed model, availability depends on memory, Android version and AICore updates. The check above is the most accurate.",
            "Même sur un modèle listé, la disponibilité dépend de la mémoire, de la version d'Android et des mises à jour d'AICore.",
            "Incluso en un modelo de la lista, depende de la memoria, la versión de Android y las actualizaciones de AICore.",
            "Auch bei gelisteten Modellen hängt es von Speicher, Android-Version und AICore-Updates ab.",
            "即使是列表中的机型，也取决于内存、Android 版本和 AICore 更新情况。以上检查结果最准确。"
        )
    )
}
