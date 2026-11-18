package com.example.interpreter

// Screen text for daily review and news study. Order in m(): ko, ja, en, fr, es, de, zh.
object StudyText {

    fun t(key: String): String = table[key]?.let { it[I18n.lang] ?: it["en"] ?: it["ko"] } ?: key

    fun f(key: String, vararg args: Any): String = String.format(t(key), *args)

    private fun m(ko: String, ja: String, en: String, fr: String, es: String, de: String, zh: String) =
        mapOf("ko" to ko, "ja" to ja, "en" to en, "fr" to fr, "es" to es, "de" to de, "zh" to zh)

    private val table: Map<String, Map<String, String>> = mapOf(
        // Daily review
        "review_btn" to m("🧠 오늘의 복습 (%d)", "🧠 今日の復習 (%d)", "🧠 Today's review (%d)", "🧠 Révision du jour (%d)", "🧠 Repaso de hoy (%d)", "🧠 Heutige Wiederholung (%d)", "🧠 今日复习 (%d)"),
        "news_btn" to m("📰 뉴스로 공부", "📰 ニュースで学ぶ", "📰 Learn with news", "📰 Apprendre avec l'actu", "📰 Aprender con noticias", "📰 Mit Nachrichten lernen", "📰 用新闻学习"),
        "streak" to m("🔥 %d일 연속", "🔥 %d日連続", "🔥 %d-day streak", "🔥 %d jours d'affilée", "🔥 %d días seguidos", "🔥 %d Tage in Folge", "🔥 连续 %d 天"),
        "today" to m("오늘 %d / %d", "今日 %d / %d", "Today %d / %d", "Aujourd'hui %d / %d", "Hoy %d / %d", "Heute %d / %d", "今天 %d / %d"),
        "left" to m("남은 카드 %d", "残り %d", "%d left", "%d restantes", "Quedan %d", "%d übrig", "剩余 %d"),
        "recall" to m("뜻을 먼저 떠올려 보세요", "まず意味を思い出してみましょう", "Try to recall the meaning first", "Essayez de vous rappeler le sens", "Intenta recordar el significado", "Erinnere dich zuerst an die Bedeutung", "先试着回想意思"),
        "listen_hint" to m("소리만 듣고 떠올려 보세요", "音だけ聞いて思い出しましょう", "Listen and recall", "Écoutez et rappelez-vous", "Escucha y recuerda", "Hören und erinnern", "只听声音回想"),
        "show" to m("뜻 보기", "意味を見る", "Show meaning", "Voir le sens", "Ver significado", "Bedeutung zeigen", "查看意思"),
        "forgot" to m("몰랐어요", "わからなかった", "Forgot", "Oublié", "No lo sabía", "Vergessen", "不记得"),
        "hard" to m("헷갈려요", "あいまい", "Unsure", "Hésitant", "Dudoso", "Unsicher", "模糊"),
        "knew" to m("알았어요", "わかった", "Knew it", "Je savais", "Lo sabía", "Gewusst", "记得"),
        "done" to m("오늘 복습 끝! 내일 또 만나요.", "今日の復習は完了！また明日。", "Done for today! See you tomorrow.", "Terminé pour aujourd'hui !", "¡Listo por hoy!", "Für heute fertig!", "今天的复习完成了！"),
        "empty" to m("복습할 단어가 없어요. 회화·통역·뉴스에서 단어를 저장해 보세요.", "復習する単語がありません。会話・通訳・ニュースで単語を保存しましょう。", "Nothing to review. Save words from conversations, interpreting or news.", "Rien à réviser. Enregistrez des mots.", "Nada que repasar. Guarda palabras.", "Nichts zu wiederholen. Speichere Wörter.", "没有要复习的单词。请先保存单词。"),
        "listen_mode" to m("🎧 듣기 모드", "🎧 リスニング", "🎧 Listening mode", "🎧 Mode écoute", "🎧 Modo escucha", "🎧 Hörmodus", "🎧 听力模式"),
        "hook_btn" to m("✨ 연상 만들기", "✨ 連想を作る", "✨ Make a memory hook", "✨ Créer un moyen mnémo", "✨ Crear asociación", "✨ Eselsbrücke bauen", "✨ 生成联想"),
        "hook_wait" to m("연상을 만드는 중…", "連想を作成中…", "Making a hook…", "Création…", "Creando…", "Wird erstellt…", "正在生成…"),
        "hook_need" to m("설정에서 AI 엔진을 고르면 기억에 남는 연상 문장을 만들어 줘요.", "設定でAIエンジンを選ぶと連想文を作れます。", "Choose an AI engine in settings to get memory hooks.", "Choisissez un moteur IA dans les réglages.", "Elige un motor de IA en ajustes.", "Wähle in den Einstellungen eine KI.", "在设置中选择AI引擎即可生成联想。"),
        "next" to m("다음 복습: %s", "次の復習: %s", "Next review: %s", "Prochaine révision : %s", "Próximo repaso: %s", "Nächste Wiederholung: %s", "下次复习：%s"),
        "in_min" to m("10분 뒤", "10分後", "in 10 min", "dans 10 min", "en 10 min", "in 10 Min.", "10分钟后"),
        "in_days" to m("%d일 뒤", "%d日後", "in %d days", "dans %d jours", "en %d días", "in %d Tagen", "%d天后"),

        // News
        "news_title" to m("📰 뉴스로 공부", "📰 ニュースで学ぶ", "📰 Learn with news", "📰 Apprendre avec l'actu", "📰 Aprender con noticias", "📰 Mit Nachrichten lernen", "📰 用新闻学习"),
        "level0" to m("초급", "初級", "Beginner", "Débutant", "Principiante", "Anfänger", "初级"),
        "level1" to m("중급", "中級", "Intermediate", "Intermédiaire", "Intermedio", "Mittelstufe", "中级"),
        "level2" to m("고급", "上級", "Advanced", "Avancé", "Avanzado", "Fortgeschritten", "高级"),
        "all" to m("전체", "すべて", "All", "Tout", "Todo", "Alle", "全部"),
        "week" to m("최근 7일 전체 · %d건", "直近7日すべて · %d件", "Last 7 days · %d", "7 derniers jours · %d", "Últimos 7 días · %d", "Letzte 7 Tage · %d", "最近7天 · %d条"),
        "count" to m("%d건", "%d件", "%d", "%d", "%d", "%d", "%d条"),
        "today_tag" to m("오늘", "今日", "today", "aujourd'hui", "hoy", "heute", "今天"),
        "refresh" to m("↻ 새로고침", "↻ 更新", "↻ Refresh", "↻ Actualiser", "↻ Actualizar", "↻ Aktualisieren", "↻ 刷新"),
        "loading" to m("뉴스를 받아오는 중…", "ニュースを取得中…", "Getting the news…", "Chargement…", "Cargando…", "Wird geladen…", "正在获取新闻…"),
        "no_news" to m("이 날짜의 뉴스가 아직 없어요. 인터넷 연결 후 새로고침을 눌러 주세요.", "この日のニュースはまだありません。接続後に更新してください。", "No news for this day yet. Connect and tap Refresh.", "Pas encore d'actualités ce jour.", "Aún no hay noticias para este día.", "Noch keine Nachrichten für diesen Tag.", "这一天还没有新闻。"),
        "making" to m("%s 수준 기사를 만드는 중…", "%sレベルの記事を作成中…", "Writing a %s article…", "Rédaction (%s)…", "Escribiendo (%s)…", "Artikel (%s) wird geschrieben…", "正在生成%s文章…"),
        "words" to m("주요 단어", "重要単語", "Key words", "Mots clés", "Palabras clave", "Schlüsselwörter", "重点词汇"),
        "save_all" to m("단어 모두 단어장에 저장", "単語をすべて保存", "Save all words", "Tout enregistrer", "Guardar todas", "Alle speichern", "全部保存"),
        "saved_n" to m("%d개 저장했어요", "%d件保存しました", "Saved %d", "%d enregistrés", "%d guardadas", "%d gespeichert", "已保存 %d 个"),
        "need_ai" to m("AI 엔진을 설정하면 수준별 기사와 주요 단어가 나와요. 지금은 기기 번역만 보여줘요.", "AIエンジンを設定するとレベル別の記事と単語が出ます。今は端末翻訳のみです。", "Set an AI engine to get level-based articles and key words. Showing device translation only.", "Configurez une IA pour des articles par niveau.", "Configura una IA para artículos por nivel.", "Richte eine KI für Niveau-Artikel ein.", "设置AI引擎即可获得分级文章和词汇。"),
        "press_hint" to m("문장을 누르면 거기서부터 읽어요. 단어를 길게 누르면 뜻이 나오고, 누른 채로 밀면 단어마다 발음해요.", "文を押すとそこから読みます。単語を長押しすると意味が出て、そのままなぞると単語ごとに発音します。", "Tap a sentence to read from there. Long-press a word for its meaning; slide to hear each word.", "Touchez une phrase pour lire. Appui long sur un mot pour le sens ; glissez pour l'entendre.", "Toca una frase para leer. Mantén pulsada una palabra para ver su significado; desliza para oírla.", "Satz antippen zum Vorlesen. Wort lange drücken für Bedeutung, wischen zum Anhören.", "点句子从那里开始朗读。长按单词显示意思，按住滑动逐词发音。"),
        "source" to m("출처: 위키백과 「Current events」 (CC BY-SA 4.0) · AI가 학습용으로 다시 썼어요. 사실은 원문에서 확인하세요.", "出典: Wikipedia「Current events」(CC BY-SA 4.0)・AIが学習用に書き直しました。事実は原文で確認してください。", "Source: Wikipedia “Current events” (CC BY-SA 4.0) · rewritten by AI for learning. Check facts in the original.", "Source : Wikipédia « Current events » (CC BY-SA 4.0) · réécrit par IA.", "Fuente: Wikipedia «Current events» (CC BY-SA 4.0) · reescrito por IA.", "Quelle: Wikipedia „Current events“ (CC BY-SA 4.0) · von KI umgeschrieben.", "来源：维基百科「Current events」(CC BY-SA 4.0) · 由AI改写用于学习。"),
        "open_source" to m("원문 보기", "原文を見る", "Open original", "Voir l'original", "Ver original", "Original öffnen", "查看原文"),
        "failed" to m("기사를 만들지 못했어요: %s", "記事を作れませんでした: %s", "Couldn't write the article: %s", "Échec : %s", "Error: %s", "Fehlgeschlagen: %s", "生成失败：%s")
    )

    // News categories, more specific than the source's ten sections.
    val categories: List<Map<String, String>> = listOf(
        m("정치", "政治", "Politics", "Politique", "Política", "Politik", "政治"),
        m("선거", "選挙", "Elections", "Élections", "Elecciones", "Wahlen", "选举"),
        m("경제", "経済", "Economy", "Économie", "Economía", "Wirtschaft", "经济"),
        m("금융·증시", "金融・株式", "Finance & markets", "Finance", "Finanzas", "Finanzen", "金融·股市"),
        m("기업", "企業", "Business", "Entreprises", "Empresas", "Unternehmen", "企业"),
        m("국제·외교", "国際・外交", "International", "International", "Internacional", "International", "国际·外交"),
        m("분쟁·안보", "紛争・安全保障", "Conflict & security", "Conflits", "Conflictos", "Konflikte", "冲突·安全"),
        m("사회", "社会", "Society", "Société", "Sociedad", "Gesellschaft", "社会"),
        m("법·범죄", "法律・犯罪", "Law & crime", "Justice", "Justicia", "Recht", "法律·犯罪"),
        m("재난·사고", "災害・事故", "Disasters", "Catastrophes", "Desastres", "Katastrophen", "灾害·事故"),
        m("건강·의료", "健康・医療", "Health", "Santé", "Salud", "Gesundheit", "健康·医疗"),
        m("환경·기후", "環境・気候", "Environment", "Environnement", "Medio ambiente", "Umwelt", "环境·气候"),
        m("과학", "科学", "Science", "Sciences", "Ciencia", "Wissenschaft", "科学"),
        m("우주", "宇宙", "Space", "Espace", "Espacio", "Raumfahrt", "太空"),
        m("IT·기술", "IT・技術", "Technology", "Technologie", "Tecnología", "Technik", "科技"),
        m("에너지", "エネルギー", "Energy", "Énergie", "Energía", "Energie", "能源"),
        m("교통", "交通", "Transport", "Transports", "Transporte", "Verkehr", "交通"),
        m("교육", "教育", "Education", "Éducation", "Educación", "Bildung", "教育"),
        m("문화·예술", "文化・芸術", "Culture & arts", "Culture", "Cultura", "Kultur", "文化·艺术"),
        m("연예·미디어", "芸能・メディア", "Entertainment", "Divertissement", "Entretenimiento", "Unterhaltung", "娱乐·媒体"),
        m("스포츠", "スポーツ", "Sports", "Sport", "Deportes", "Sport", "体育"),
        m("종교", "宗教", "Religion", "Religion", "Religión", "Religion", "宗教"),
        m("인물·부고", "人物・訃報", "People & obituaries", "Personnalités", "Personas", "Personen", "人物·讣告"),
        m("기타", "その他", "Other", "Autres", "Otros", "Sonstiges", "其他")
    )

    fun category(i: Int): String = categories.getOrNull(i)?.let { it[I18n.lang] ?: it["en"] } ?: ""
}
