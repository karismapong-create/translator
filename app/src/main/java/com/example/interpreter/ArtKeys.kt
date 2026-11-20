package com.example.interpreter

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Settings → picture keys: the free picture services (Cloudflare Workers AI, Pollinations), each with
 * how to get a key, a button to the sign-up page and the fields to paste into. Gemini and OpenAI keys
 * from the AI engine settings are still used after these.
 */
object ArtKeys {
    private fun t(ko: String, ja: String, zh: String, en: String) = when (I18n.lang) {
        "ko" -> ko; "ja" -> ja; "zh" -> zh; else -> en
    }

    fun label() = "🔑 " + t("그림 그리기 키 (무료)", "絵を描くキー（無料）", "绘图密钥（免费）", "Picture keys (free)")

    fun show(a: Activity) {
        val prefs = a.getSharedPreferences("interpreter", Activity.MODE_PRIVATE)
        fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fun text(s: String, size: Float = 14f, bold: Boolean = false) = TextView(a).apply {
            this.text = s
            textSize = size
            setTextColor(Ui.INK)
            if (bold) Ui.bold(this)
            setPadding(0, dp(if (bold) 14 else 4), 0, dp(4))
        }.also { box.addView(it) }
        fun field(hint: String, value: String) = EditText(a).apply {
            this.hint = hint
            setText(value)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            textSize = 14f
        }.also { box.addView(it) }
        fun link(label: String, url: String) = Button(a).apply {
            this.text = label
            isAllCaps = false
            setOnClickListener { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }.also { box.addView(it) }

        text(t("문장 그림은 아래 무료 서비스부터 그려요. 하나만 넣어도 돼요. 둘 다 카드 등록이 필요 없어요.",
            "文の絵は下の無料サービスから描きます。どちらか一つで大丈夫です。どちらもカード登録は不要です。",
            "句子插图优先使用下面的免费服务，填一个即可，都不需要绑定信用卡。",
            "Sentence pictures are drawn with these free services first. One is enough; neither needs a card."))

        text(t("그림 엔진", "絵のエンジン", "绘图引擎", "Picture engine"), 16f, true)
        val engines = listOf(
            "auto" to t("자동 — 무료 서비스 먼저", "自動 — 無料サービスを先に", "自动 — 先用免费服务", "Auto — free services first"),
            "nano" to t("나노 바나나 먼저 (Gemini 키, 장당 약 50원)", "Nano Banana を先に（Geminiキー、1枚約5円）", "先用 Nano Banana（Gemini 密钥，每张约 0.04 美元）", "Nano Banana first (Gemini key, about 4¢ a picture)"),
            "pro" to t("나노 바나나 Pro 먼저 (가장 좋음, 장당 약 200원)", "Nano Banana Pro を先に（最高品質、1枚約20円）", "先用 Nano Banana Pro（最佳，每张约 0.15 美元）", "Nano Banana Pro first (best, about 15¢ a picture)"),
            "mac" to t("맥북 로컬 Qwen (영상 만들기, 무료)", "MacBookのローカルQwen（動画作成、無料）", "MacBook 本地 Qwen（制作视频，免费）", "MacBook local Qwen (making videos, free)"))
        val group = android.widget.RadioGroup(a)
        val current = ImageGen.engine(prefs)
        engines.forEachIndexed { k, (id, name) ->
            group.addView(android.widget.RadioButton(a).apply {
                this.id = 1000 + k
                this.text = name
                textSize = 14f
                isChecked = id == current
            })
        }
        box.addView(group)
        text(t("나노 바나나는 이전 그림을 참고해서 인물과 화풍을 이어 그리는 힘이 가장 좋아요. 설정 → AI 엔진에 결제가 켜진 Gemini 키가 필요해요.",
            "Nano Banana は前の絵を参考に人物と画風をつなげるのが最も得意です。設定→AIエンジンに課金設定済みのGeminiキーが必要です。",
            "Nano Banana 参考上一张图延续人物与画风的能力最强，需要在设置→AI 引擎中填写已开通付费的 Gemini 密钥。",
            "Nano Banana is best at keeping characters and style from the previous picture. It needs a Gemini key with billing in Settings → AI engine."), 12f)

        // Nano Banana runs on a Gemini API key (the same key as the Gemini AI engine).
        text(t("나노 바나나 (Gemini) 키", "Nano Banana（Gemini）キー", "Nano Banana（Gemini）密钥", "Nano Banana (Gemini) key"), 16f, true)
        text(t("① 아래 버튼으로 Google AI Studio를 열고 'API 키 만들기'를 누르세요.\n" +
                "② 그림을 그리려면 그 키의 프로젝트에 결제를 켜야 해요 (AI Studio의 '결제 설정'). 무료 키는 그림 한도가 0이에요.\n" +
                "③ 키를 복사해 아래에 붙여넣으세요. AI 엔진의 Gemini 키와 같은 키예요.",
            "① 下のボタンでGoogle AI Studioを開き「APIキーを作成」を押します。\n" +
                "② 絵を描くには、そのキーのプロジェクトで課金を有効にする必要があります。無料キーは絵の枠が0です。\n" +
                "③ キーを下に貼り付けます（AIエンジンのGeminiキーと同じです）。",
            "① 用下面的按钮打开 Google AI Studio，点“创建 API 密钥”。\n" +
                "② 绘图需要为该密钥的项目开通结算，免费密钥的绘图额度为 0。\n" +
                "③ 把密钥粘贴到下面（与 AI 引擎的 Gemini 密钥相同）。",
            "① Open Google AI Studio with the button below and tap 'Create API key'.\n" +
                "② Drawing needs billing turned on for the key's project; free keys can't draw pictures.\n" +
                "③ Paste the key below (the same key as the Gemini AI engine)."))
        link(t("Google AI Studio 열기", "Google AI Studioを開く", "打开 Google AI Studio", "Open Google AI Studio"), "https://aistudio.google.com/apikey")
        val gemini = field(t("Gemini API 키 (AIza…)", "Gemini APIキー（AIza…）", "Gemini API 密钥（AIza…）", "Gemini API key (AIza…)"), Providers.key(prefs, "gemini"))

        // Owl Studio on the user's Mac (tools/owl-studio): local Qwen draws a part's pictures for its video.
        text(t("맥북 (로컬 Qwen)", "MacBook（ローカルQwen）", "MacBook（本地 Qwen）", "MacBook (local Qwen)"), 16f, true)
        text(t("맥북에서 Owl Studio를 실행하면, 영상 만들기의 그림을 맥북의 Qwen이 그려서 한 장씩 바로 보내 줘요. 폰을 꺼도 맥북은 계속 그려요. 설치 스크립트가 알려 주는 주소(WireGuard IP:8765)와 토큰을 넣으세요.",
            "MacでOwl Studioを動かすと、動画作成の絵をMacのQwenが描いて1枚ずつ送ります。スマホを切ってもMacは描き続けます。インストーラーが表示するアドレスとトークンを入れてください。",
            "在 Mac 上运行 Owl Studio 后，制作视频的插图由 Mac 上的 Qwen 绘制并逐张发送，手机关屏 Mac 也会继续。请填写安装脚本显示的地址和令牌。",
            "Run Owl Studio on your Mac and its local Qwen draws the pictures for making a video, sending each as soon as it's done; the Mac keeps going if the phone sleeps. Enter the address (WireGuard IP:8765) and token the installer shows."), 13f)
        val macUrl = field(t("주소 (예: 10.0.0.2:8765)", "アドレス（例: 10.0.0.2:8765）", "地址（如 10.0.0.2:8765）", "Address (e.g. 10.0.0.2:8765)"), prefs.getString("mac_url", "").orEmpty())
        val macToken = field(t("토큰", "トークン", "令牌", "Token"), prefs.getString("mac_token", "").orEmpty())
        Button(a).apply {
            text = t("연결 확인", "接続を確認", "测试连接", "Test the connection")
            isAllCaps = false
            setOnClickListener {
                val url = MacStudio.base(macUrl.text.toString())
                val tok = macToken.text.toString().trim()
                Thread {
                    val msg = try { MacStudio.health(url, tok) } catch (e: Exception) { "✘ " + (e.message ?: e.javaClass.simpleName) }
                    a.runOnUiThread { Toast.makeText(a, Ui.plain(msg), Toast.LENGTH_LONG).show() }
                }.start()
            }
        }.also { box.addView(it) }

        text("Cloudflare Workers AI", 16f, true)
        text(t("하루 무료 사용량 안에서 100장 넘게 그릴 수 있어요 (매일 오전 9시에 다시 채워져요).\n" +
                "① 아래 버튼으로 Cloudflare를 열고 이메일로 가입·로그인하세요.\n" +
                "② 메뉴에서 AI → Workers AI로 가서 'REST API 사용'을 누르세요.\n" +
                "③ 'Workers AI API 토큰 만들기'로 토큰을 만들어 아래에 붙여넣으세요. 계정 ID(32자리 영문·숫자, 이메일 아님)는 비워 두면 자동으로 찾아요.",
            "1日の無料枠で100枚以上描けます（毎日9時（韓国時間）にリセット）。\n" +
                "① 下のボタンでCloudflareを開き、メールで登録・ログイン。\n" +
                "② メニューの AI → Workers AI で「REST APIを使用」を押します。\n" +
                "③「Workers AI APIトークンを作成」でトークンを作り、アカウントIDとトークンを下に貼り付けます。",
            "每天的免费额度可画 100 多张（每天 UTC 0 点重置）。\n" +
                "① 用下面的按钮打开 Cloudflare，用邮箱注册并登录。\n" +
                "② 在菜单 AI → Workers AI 中点“使用 REST API”。\n" +
                "③ 点“创建 Workers AI API 令牌”，把页面上的账户 ID 和令牌粘贴到下面。",
            "Over 100 pictures a day within the free allowance (resets daily at 00:00 UTC).\n" +
                "① Open Cloudflare with the button below and sign up with your email.\n" +
                "② In the menu go to AI → Workers AI and tap 'Use REST API'.\n" +
                "③ Tap 'Create a Workers AI API Token', then paste the account ID and the token below."))
        link(t("Cloudflare 열기", "Cloudflareを開く", "打开 Cloudflare", "Open Cloudflare"), "https://dash.cloudflare.com/?to=/:account/ai/workers-ai")
        val cfAccount = field(t("계정 ID (32자리, 비워 두면 자동)", "アカウントID（32桁、空欄で自動）", "账户 ID（32 位，留空自动）", "Account ID (32 characters; blank = automatic)"), ImageGen.cfAccount(prefs))
        val cfToken = field(t("API 토큰", "APIトークン", "API 令牌", "API token"), ImageGen.cfToken(prefs))

        text("Pollinations", 16f, true)
        text(t("무료 크레딧이 시간마다 조금씩 채워져요. 그림 한 장에 드는 양이 아주 적어요.\n" +
                "① 아래 버튼으로 Pollinations를 열고 GitHub 계정으로 로그인하세요.\n" +
                "② 키를 만들어(sk_ 또는 pk_로 시작) 복사해서 아래에 붙여넣으세요.",
            "無料クレジットが少しずつ補充されます。1枚あたりの消費はごくわずかです。\n" +
                "① 下のボタンでPollinationsを開き、GitHubアカウントでログイン。\n" +
                "② キー（sk_ または pk_ で始まる）を作り、下に貼り付けます。",
            "免费额度会定时少量补充，每张图消耗很少。\n" +
                "① 用下面的按钮打开 Pollinations，用 GitHub 账号登录。\n" +
                "② 创建密钥（以 sk_ 或 pk_ 开头），粘贴到下面。",
            "Free credit refills a little at a time; a picture uses very little.\n" +
                "① Open Pollinations with the button below and sign in with GitHub.\n" +
                "② Create a key (starts with sk_ or pk_) and paste it below."))
        link(t("Pollinations 열기", "Pollinationsを開く", "打开 Pollinations", "Open Pollinations"), "https://enter.pollinations.ai")
        val poll = field(t("Pollinations 키", "Pollinationsキー", "Pollinations 密钥", "Pollinations key"), ImageGen.pollKey(prefs))

        text(t("※ 설정 → AI 엔진의 Gemini·OpenAI 키도 그 다음 순서로 쓰여요 (그림은 결제가 켜진 키만 돼요).",
            "※ 設定→AIエンジンのGemini・OpenAIキーもその次に使われます（画像は課金設定済みのキーのみ）。",
            "※ 设置→AI 引擎中的 Gemini、OpenAI 密钥会在其后使用（绘图需开通付费）。",
            "※ Gemini and OpenAI keys from Settings → AI engine are tried after these (pictures need billing there)."), 12f)

        AlertDialog.Builder(a)
            .setTitle(Ui.plain(label()))
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton(t("저장", "保存", "保存", "Save")) { _, _ ->
                prefs.edit()
                    .putString("art_engine", engines.getOrNull(group.checkedRadioButtonId - 1000)?.first ?: "auto")
                    .putString("cf_account", cfAccount.text.toString().trim())
                    .putString("cf_token", cfToken.text.toString().trim())
                    .putString("poll_key", poll.text.toString().trim())
                    .putString("gemini_key", gemini.text.toString().trim())
                    .putString("mac_url", macUrl.text.toString().trim())
                    .putString("mac_token", macToken.text.toString().trim())
                    .apply()
                val acc = cfAccount.text.toString().trim()
                if (acc.isNotEmpty() && !ImageGen.validAccount(acc)) {
                    // An email or name in the account field: cleared, so the token finds the account itself.
                    prefs.edit().putString("cf_account", "").apply()
                    Toast.makeText(a, t("계정 ID는 이메일이 아니라 32자리 영문·숫자예요. 비워 두고 토큰으로 자동으로 찾을게요.",
                        "アカウントIDはメールではなく32桁の英数字です。トークンから自動で探します。",
                        "账户 ID 不是邮箱，而是 32 位字母数字。将通过令牌自动查找。",
                        "The account ID is 32 letters and digits, not an email. It will be found from the token."), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(a, t("저장했어요", "保存しました", "已保存", "Saved"), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(I18n.t("close"), null)
            .show().also { Ui.watch(it) }
    }
}
