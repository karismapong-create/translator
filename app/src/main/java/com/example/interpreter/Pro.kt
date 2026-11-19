package com.example.interpreter

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * "부엉이 프로": Google Play subscription (product owl_pro, any base plans such as monthly/yearly,
 * free trial set up as an offer in Play Console) and a one-time lifetime unlock (owl_lifetime).
 *
 * Until those products exist in Play Console, everything stays unlocked, so testers are never
 * blocked. Once the products have been seen, the paywall applies (remembered offline).
 * Test builds (.debug) are always unlocked.
 */
object Pro {
    const val SUB = "owl_pro"
    const val LIFETIME = "owl_lifetime"

    private var app: Context? = null
    private var prefs: SharedPreferences? = null
    private var client: BillingClient? = null
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    private var retries = 0
    private var pendingAction: (() -> Unit)? = null

    @Volatile private var subDetails: ProductDetails? = null
    @Volatile private var lifeDetails: ProductDetails? = null

    fun init(ctx: Context) {
        if (app != null) { refresh(); return }
        val a = ctx.applicationContext
        app = a
        prefs = a.getSharedPreferences("pro", Context.MODE_PRIVATE)
        if (isTestBuild(a)) return
        client = BillingClient.newBuilder(a)
            .setListener { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                    handleNew(purchases)
                }
            }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        connect()
    }

    private fun isTestBuild(ctx: Context) =
        ctx.packageName.endsWith(".debug") || (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** True when the user may use paid features. */
    fun active(ctx: Context): Boolean {
        if (app == null) init(ctx)
        val a = app ?: return true
        if (isTestBuild(a)) return true
        val p = prefs ?: return true
        if (!p.getBoolean("configured", false)) return true
        return p.getBoolean("owned", false)
    }

    fun owned(ctx: Context): Boolean {
        if (app == null) init(ctx)
        return prefs?.getBoolean("owned", false) == true
    }

    /** Runs [action] right away for Pro users, otherwise shows the paywall first. */
    fun gate(ctx: Context, action: () -> Unit) {
        if (active(ctx)) { action(); return }
        val act = ctx as? Activity
        if (act == null) { action(); return }
        showPaywall(act, action)
    }

    /* ---------------- Billing ---------------- */

    private fun connect() {
        val c = client ?: return
        if (c.isReady) { refresh(); return }
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    retries = 0
                    queryProducts()
                    queryOwned()
                }
            }
            override fun onBillingServiceDisconnected() {
                if (retries < 3) {
                    retries++
                    main.postDelayed({ connect() }, 3000L * retries)
                }
            }
        })
    }

    fun refresh() {
        val c = client ?: return
        if (!c.isReady) { retries = 0; connect(); return }
        queryProducts()
        queryOwned()
    }

    private fun queryProducts() {
        val c = client ?: return
        fun ask(id: String, type: String, done: (ProductDetails?) -> Unit) {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(
                    QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(type).build()
                )).build()
            c.queryProductDetailsAsync(params) { result, res ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) done(res.productDetailsList.firstOrNull())
            }
        }
        ask(SUB, BillingClient.ProductType.SUBS) { d ->
            subDetails = d
            if (d != null) prefs?.edit()?.putBoolean("configured", true)?.apply()
            changed()
        }
        ask(LIFETIME, BillingClient.ProductType.INAPP) { d ->
            lifeDetails = d
            if (d != null) prefs?.edit()?.putBoolean("configured", true)?.apply()
            changed()
        }
    }

    private fun queryOwned() {
        val c = client ?: return
        var answers = 0
        var ok = 0
        var found = false
        val lock = Any()
        fun one(type: String) {
            c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build()) { result, list ->
                synchronized(lock) {
                    answers++
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        ok++
                        for (p in list) if (isOurs(p) && p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                            found = true
                            acknowledge(p)
                        }
                    }
                    // Only overwrite the saved state when Play answered both questions.
                    if (answers == 2 && ok == 2) {
                        prefs?.edit()?.putBoolean("owned", found)?.apply()
                        changed()
                    } else if (answers == 2 && found) {
                        prefs?.edit()?.putBoolean("owned", true)?.apply()
                        changed()
                    }
                }
            }
        }
        one(BillingClient.ProductType.SUBS)
        one(BillingClient.ProductType.INAPP)
    }

    private fun isOurs(p: Purchase) = p.products.any { it == SUB || it == LIFETIME }

    private fun handleNew(purchases: List<Purchase>) {
        var got = false
        var pending = false
        for (p in purchases) {
            if (!isOurs(p)) continue
            when (p.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> { got = true; acknowledge(p) }
                Purchase.PurchaseState.PENDING -> pending = true
                else -> {}
            }
        }
        if (got) {
            prefs?.edit()?.putBoolean("owned", true)?.putBoolean("configured", true)?.apply()
            main.post {
                toast(ProText.t("thanks"))
                val run = pendingAction
                pendingAction = null
                run?.invoke()
            }
            changed()
        } else if (pending) {
            main.post { toast(ProText.t("pending")) }
        }
    }

    private fun acknowledge(p: Purchase) {
        if (p.isAcknowledged) return
        val c = client ?: return
        c.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { }
    }

    private fun changed() {
        main.post { listeners.toList().forEach { it() } }
    }

    private fun toast(s: String) {
        app?.let { Toast.makeText(it, Ui.plain(s), Toast.LENGTH_LONG).show() }
    }

    private fun buy(act: Activity, d: ProductDetails, offerToken: String?) {
        val c = client
        if (c == null || !c.isReady) { toast(ProText.t("unavailable")); refresh(); return }
        val b = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d)
        if (offerToken != null) b.setOfferToken(offerToken)
        c.launchBillingFlow(act, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(b.build())).build())
    }

    /* ---------------- Offers ---------------- */

    private class Plan(
        val title: String,
        val price: String,
        val note: String,
        val best: Boolean,
        val details: ProductDetails,
        val token: String?,
        val order: Int
    )

    private fun months(period: String): Int {
        val y = Regex("P(\\d+)Y").find(period)?.groupValues?.get(1)?.toIntOrNull()
        if (y != null) return y * 12
        return Regex("P(\\d+)M").find(period)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun days(period: String): Int {
        Regex("P(\\d+)D").find(period)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("P(\\d+)W").find(period)?.groupValues?.get(1)?.toIntOrNull()?.let { return it * 7 }
        return months(period) * 30
    }

    private fun plans(): List<Plan> {
        val out = mutableListOf<Plan>()
        subDetails?.let { d ->
            val offers = d.subscriptionOfferDetails ?: emptyList()
            for (base in offers.map { it.basePlanId }.distinct()) {
                val mine = offers.filter { it.basePlanId == base }
                // Offers listed are the ones this user is eligible for; prefer one with a free trial.
                val offer = mine.firstOrNull { o -> o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } }
                    ?: mine.firstOrNull { it.offerId == null } ?: mine.first()
                val phases = offer.pricingPhases.pricingPhaseList
                val regular = phases.lastOrNull() ?: continue
                val m = months(regular.billingPeriod)
                val trial = phases.firstOrNull { it.priceAmountMicros == 0L }
                val notes = mutableListOf<String>()
                if (trial != null) notes += ProText.f("trial", days(trial.billingPeriod))
                if (m >= 12) {
                    val perMonth = regular.priceAmountMicros / 1_000_000.0 / m
                    notes += ProText.f("per_month", formatMoney(perMonth, regular.priceCurrencyCode))
                }
                val title = when {
                    m >= 12 -> ProText.t("year")
                    m == 1 -> ProText.t("month")
                    else -> ProText.f("n_months", m)
                }
                out += Plan(title, regular.formattedPrice, notes.joinToString(" · "), m >= 12, d, offer.offerToken, if (m >= 12) 0 else 1)
            }
        }
        lifeDetails?.let { d ->
            val price = d.oneTimePurchaseOfferDetails?.formattedPrice ?: ""
            out += Plan(ProText.t("life"), price, ProText.t("life_note"), false, d, null, 2)
        }
        return out.sortedBy { it.order }
    }

    private fun formatMoney(v: Double, currency: String): String = try {
        val f = java.text.NumberFormat.getCurrencyInstance()
        f.currency = java.util.Currency.getInstance(currency)
        f.maximumFractionDigits = if (currency == "KRW" || currency == "JPY") 0 else 2
        f.format(if (currency == "KRW") Math.round(v / 10.0) * 10.0 else v)
    } catch (_: Exception) { String.format("%.0f %s", v, currency) }

    /* ---------------- Paywall ---------------- */

    fun showPaywall(act: Activity, after: (() -> Unit)? = null) {
        init(act)
        pendingAction = after
        val dp = { v: Int -> Ui.dp(act, v) }
        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(8))
        }
        val scroll = ScrollView(act).apply { addView(body) }
        val dialog = AlertDialog.Builder(act)
            .setView(scroll)
            .setNegativeButton(ProText.t("later"), null)
            .create()

        fun build() {
            body.removeAllViews()
            body.addView(TextView(act).apply {
                text = ProText.t("title")
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.INK)
            })
            if (owned(act)) {
                body.addView(TextView(act).apply {
                    text = ProText.t("status_on")
                    textSize = 15f
                    setTextColor(Ui.GOOD)
                    setPadding(0, dp(10), 0, dp(10))
                })
                body.addView(TextView(act).apply {
                    text = ProText.t("cancel_note")
                    textSize = 13f
                    setTextColor(Ui.INK_SOFT)
                })
                return
            }
            body.addView(TextView(act).apply {
                text = ProText.t("sub")
                textSize = 14f
                setTextColor(Ui.INK_SOFT)
                setPadding(0, dp(4), 0, dp(12))
            })
            for (k in listOf("b1", "b2", "b3", "b4")) {
                body.addView(TextView(act).apply {
                    text = "✔  " + ProText.t(k)
                    textSize = 15f
                    setTextColor(Ui.INK)
                    setPadding(0, dp(3), 0, dp(3))
                })
            }
            val list = plans()
            if (list.isEmpty()) {
                body.addView(TextView(act).apply {
                    text = ProText.t(if (prefs?.getBoolean("configured", false) == true) "loading" else "beta")
                    textSize = 14f
                    setTextColor(Ui.INK_SOFT)
                    setPadding(0, dp(16), 0, dp(8))
                })
            }
            for (p in list) {
                val card = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    background = if (p.best) Ui.rounded(Ui.PRIMARY_SOFT, 16, act, Ui.PRIMARY, 2) else Ui.pressable(act)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    isClickable = true
                    setOnClickListener { buy(act, p.details, p.token) }
                }
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(act).apply {
                    text = p.title
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Ui.INK)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (p.best) row.addView(TextView(act).apply {
                    text = ProText.t("best")
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    background = Ui.rounded(Ui.PRIMARY, 10, act)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                })
                row.addView(TextView(act).apply {
                    text = p.price
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Ui.PRIMARY_DARK)
                    setPadding(dp(10), 0, 0, 0)
                })
                card.addView(row)
                if (p.note.isNotEmpty()) card.addView(TextView(act).apply {
                    text = p.note
                    textSize = 13f
                    setTextColor(Ui.INK_SOFT)
                    setPadding(0, dp(2), 0, 0)
                })
                body.addView(card, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) })
            }
            body.addView(Button(act).apply {
                text = ProText.t("restore")
                isAllCaps = false
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Ui.PRIMARY)
                setOnClickListener {
                    refresh()
                    main.postDelayed({
                        toast(ProText.t(if (owned(act)) "restored" else "not_found"))
                    }, 2500)
                }
            })
            body.addView(TextView(act).apply {
                text = ProText.t("cancel_note")
                textSize = 12f
                setTextColor(Ui.INK_SOFT)
            })
        }

        build()
        val listener: () -> Unit = {
            if (dialog.isShowing) {
                if (owned(act) && pendingAction == null) dialog.dismiss() else build()
            }
        }
        listeners += listener
        dialog.setOnDismissListener { listeners.remove(listener) }
        refresh()
        dialog.show()
        Ui.watch(dialog)
    }
}

// Paywall text. Order: ko, ja, en, zh (other languages fall back to English).
object ProText {
    fun t(key: String): String = table[key]?.let { it[I18n.lang] ?: it["en"] ?: it["ko"] } ?: key
    fun f(key: String, vararg args: Any): String = String.format(t(key), *args)

    private fun m(ko: String, ja: String, en: String, zh: String) = mapOf("ko" to ko, "ja" to ja, "en" to en, "zh" to zh)

    private val table: Map<String, Map<String, String>> = mapOf(
        "menu" to m("✨ 부엉이 프로", "✨ フクロウ プロ", "✨ Owl Pro", "✨ 猫头鹰 Pro"),
        "title" to m("✨ 부엉이 프로", "✨ フクロウ プロ", "✨ Owl Pro", "✨ 猫头鹰 Pro"),
        "sub" to m("통역은 무료로 계속 쓰고, 공부 기능을 모두 열어보세요.", "通訳は無料のまま、学習機能をすべて使えます。", "Interpreting stays free. Unlock every study feature.", "翻译功能继续免费，解锁全部学习功能。"),
        "b1" to m("자동 모드: 버튼 없이 듣고 통역", "自動モード：ボタンなしで通訳", "Auto mode: interpret hands-free", "自动模式：免按键翻译"),
        "b2" to m("매일 새 뉴스로 공부 (3단계 난이도)", "毎日のニュースで学習（3レベル）", "Daily news lessons at 3 levels", "每日新闻学习（3个难度）"),
        "b3" to m("오늘의 복습: 잊기 전에 다시 보는 단어 카드", "今日の復習：忘れる前に単語カード", "Daily review: word cards right before you forget", "今日复习：遗忘前复习单词卡"),
        "b4" to m("앞으로 추가되는 학습 기능 모두", "今後追加される学習機能すべて", "Every study feature we add later", "今后新增的全部学习功能"),
        "year" to m("1년 구독", "年間プラン", "Yearly", "年度订阅"),
        "month" to m("1개월 구독", "月間プラン", "Monthly", "月度订阅"),
        "n_months" to m("%d개월 구독", "%dか月プラン", "%d months", "%d个月订阅"),
        "life" to m("평생 이용권", "買い切り", "Lifetime", "永久版"),
        "life_note" to m("한 번 결제로 계속 사용", "一度の購入でずっと使える", "Pay once, keep it forever", "一次付费，永久使用"),
        "trial" to m("%d일 무료 체험", "%d日間無料", "%d-day free trial", "免费试用%d天"),
        "per_month" to m("월 %s꼴", "月あたり%s", "%s per month", "每月约%s"),
        "best" to m("추천", "おすすめ", "Best", "推荐"),
        "restore" to m("구매 복원", "購入を復元", "Restore purchase", "恢复购买"),
        "restored" to m("구매를 찾았어요. 프로가 열렸어요!", "購入が見つかりました。プロが有効です！", "Purchase found. Pro is on!", "已找到购买记录，Pro 已开启！"),
        "not_found" to m("이 구글 계정으로 산 내역이 없어요.", "このGoogleアカウントの購入はありません。", "No purchase found for this Google account.", "此 Google 帐号没有购买记录。"),
        "loading" to m("Play 스토어에서 가격을 불러오는 중…", "Playストアから価格を読み込み中…", "Loading prices from Play Store…", "正在从 Play 商店加载价格…"),
        "beta" to m("지금은 테스트 기간이라 모든 기능이 무료로 열려 있어요.", "現在テスト期間中のため、すべて無料です。", "During testing, every feature is free.", "测试期间所有功能免费。"),
        "unavailable" to m("Play 스토어에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.", "Playストアに接続できません。後でもう一度お試しください。", "Can't reach Play Store. Please try again shortly.", "无法连接 Play 商店，请稍后再试。"),
        "thanks" to m("고마워요! 부엉이 프로가 열렸어요", "ありがとう！プロが有効になりました", "Thank you! Owl Pro is on", "谢谢！Pro 已开启"),
        "pending" to m("결제가 처리 중이에요. 완료되면 자동으로 열려요.", "支払い処理中です。完了すると自動で有効になります。", "Payment is processing. Pro turns on when it completes.", "付款处理中，完成后自动开启。"),
        "status_on" to m("✔ 프로 이용 중이에요. 고마워요!", "✔ プロを利用中です。ありがとう！", "✔ Pro is active. Thank you!", "✔ Pro 已开启，谢谢！"),
        "cancel_note" to m("구독은 Play 스토어 > 결제 및 정기 결제에서 언제든 해지할 수 있어요.", "サブスクはPlayストアの「お支払いと定期購入」からいつでも解約できます。", "Cancel anytime in Play Store > Payments & subscriptions.", "可随时在 Play 商店 > 付款和订阅中取消。"),
        "later" to m("나중에", "あとで", "Later", "以后再说")
    )
}
