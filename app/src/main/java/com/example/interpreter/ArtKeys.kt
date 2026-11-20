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

        text("Cloudflare Workers AI", 16f, true)
        text(t("하루 무료 사용량 안에서 150장 정도 그릴 수 있어요 (매일 오전 9시에 다시 채워져요).\n" +
                "① 아래 버튼으로 Cloudflare를 열고 이메일로 가입·로그인하세요.\n" +
                "② 메뉴에서 AI → Workers AI로 가서 'REST API 사용'을 누르세요.\n" +
                "③ 'Workers AI API 토큰 만들기'로 토큰을 만들고, 그 화면의 계정 ID와 토큰을 아래에 붙여넣으세요.",
            "1日の無料枠で約150枚描けます（毎日9時（韓国時間）にリセット）。\n" +
                "① 下のボタンでCloudflareを開き、メールで登録・ログイン。\n" +
                "② メニューの AI → Workers AI で「REST APIを使用」を押します。\n" +
                "③「Workers AI APIトークンを作成」でトークンを作り、アカウントIDとトークンを下に貼り付けます。",
            "每天的免费额度约可画 150 张（每天 UTC 0 点重置）。\n" +
                "① 用下面的按钮打开 Cloudflare，用邮箱注册并登录。\n" +
                "② 在菜单 AI → Workers AI 中点“使用 REST API”。\n" +
                "③ 点“创建 Workers AI API 令牌”，把页面上的账户 ID 和令牌粘贴到下面。",
            "About 150 pictures a day within the free allowance (resets daily at 00:00 UTC).\n" +
                "① Open Cloudflare with the button below and sign up with your email.\n" +
                "② In the menu go to AI → Workers AI and tap 'Use REST API'.\n" +
                "③ Tap 'Create a Workers AI API Token', then paste the account ID and the token below."))
        link(t("Cloudflare 열기", "Cloudflareを開く", "打开 Cloudflare", "Open Cloudflare"), "https://dash.cloudflare.com/?to=/:account/ai/workers-ai")
        val cfAccount = field(t("계정 ID (Account ID)", "アカウントID", "账户 ID", "Account ID"), ImageGen.cfAccount(prefs))
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
                    .putString("cf_account", cfAccount.text.toString().trim())
                    .putString("cf_token", cfToken.text.toString().trim())
                    .putString("poll_key", poll.text.toString().trim())
                    .apply()
                Toast.makeText(a, t("저장했어요", "保存しました", "已保存", "Saved"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(I18n.t("close"), null)
            .show().also { Ui.watch(it) }
    }
}
