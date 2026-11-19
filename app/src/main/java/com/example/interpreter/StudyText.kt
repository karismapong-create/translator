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
        "books_btn" to m("📚 책으로 공부", "📚 本で学ぶ", "📚 Learn with books", "📚 Apprendre avec des livres", "📚 Aprender con libros", "📚 Mit Büchern lernen", "📚 读书学习"),
        "books_intro" to m("저작권이 끝난 영어 고전을 내 언어·수준에 맞춰 읽어요. 문장 듣기, 단어 뜻, 앞뒤 감기 모두 뉴스와 같아요.", "著作権の切れた英語の名作を、言語とレベルに合わせて読みます。音声・単語の意味・早送りはニュースと同じです。", "Read classic books that are free of copyright, at your language and level. Listening, word meanings and skipping work like news.", "Lisez des classiques libres de droits à votre langue et niveau. Écoute, sens des mots et avance rapide comme pour l'actu.", "Lee clásicos libres de derechos en tu idioma y nivel. Audio, significados y avance funcionan como en noticias.", "Gemeinfreie Klassiker in deiner Sprache und Stufe lesen. Hören, Wortbedeutung und Spulen wie bei den News.", "按你的语言和水平阅读版权已过期的经典名著。朗读、词义、快进与新闻相同。"),
        "books_search" to m("제목이나 작가 (영어)", "タイトルや作者（英語）", "Title or author", "Titre ou auteur", "Título o autor", "Titel oder Autor", "书名或作者（英文）"),
        "books_mine" to m("내 책장", "本棚", "My shelf", "Ma bibliothèque", "Mi estantería", "Mein Regal", "我的书架"),
        "books_progress" to m("%1\$d/%2\$d 읽음", "%1\$d/%2\$d 読了", "%1\$d/%2\$d read", "%1\$d/%2\$d lus", "%1\$d/%2\$d leídos", "%1\$d/%2\$d gelesen", "已读 %1\$d/%2\$d"),
        "books_pick" to m("추천 도서", "おすすめの本", "Books to start", "Livres conseillés", "Libros recomendados", "Empfohlene Bücher", "推荐书籍"),
        "books_found" to m("검색 결과", "検索結果", "Results", "Résultats", "Resultados", "Ergebnisse", "搜索结果"),
        "books_loading" to m("더 많은 책을 불러오는 중…", "さらに本を読み込み中…", "Loading more books…", "Chargement d'autres livres…", "Cargando más libros…", "Weitere Bücher werden geladen…", "正在加载更多书籍…"),
        "books_offline" to m("목록을 불러오지 못했어요. 인터넷 연결을 확인하세요.", "一覧を読み込めませんでした。接続を確認してください。", "Couldn't load the list. Check your connection.", "Liste indisponible. Vérifiez la connexion.", "No se pudo cargar la lista. Revisa la conexión.", "Liste nicht geladen. Verbindung prüfen.", "无法加载列表，请检查网络。"),
        "books_none" to m("찾는 책이 없어요. 영어 제목으로 찾아보세요.", "見つかりません。英語のタイトルで探してください。", "No books found. Try the English title.", "Aucun livre. Essayez le titre anglais.", "Sin resultados. Prueba con el título en inglés.", "Nichts gefunden. Englischen Titel versuchen.", "未找到，请用英文书名搜索。"),
        "books_source" to m("원문: 저작권이 만료된 공개 도서 (작가 사후 70년 경과)", "原文：著作権の切れたパブリックドメイン作品", "Original text: public-domain work (copyright expired)", "Texte original : œuvre du domaine public", "Texto original: obra de dominio público", "Originaltext: gemeinfreies Werk", "原文：版权已过期的公有领域作品"),
        "books_ready" to m("📚 책이 준비됐어요", "📚 本の準備ができました", "📚 Your book is ready", "📚 Votre livre est prêt", "📚 Tu libro está listo", "📚 Dein Buch ist bereit", "📚 书已准备好"),
        "books_queue" to m("내려받는 중", "ダウンロード中", "Downloading", "Téléchargement", "Descargando", "Wird geladen", "正在下载"),
        "books_retrying" to m("서버가 바빠요. 잠시 뒤 자동으로 다시 받아요. 앱을 닫아도 돼요.", "サーバーが混んでいます。少し後に自動で再試行します。アプリを閉じても大丈夫です。", "The server is busy. It will retry by itself shortly; you can close the app.", "Serveur occupé. Nouvel essai automatique bientôt ; vous pouvez fermer l'app.", "Servidor ocupado. Reintentará solo en breve; puedes cerrar la app.", "Server ausgelastet. Neuer Versuch folgt automatisch; App darf geschlossen werden.", "服务器繁忙，稍后会自动重试，可以关闭应用。"),
        "books_making" to m("%s 수준으로 이 부분을 만드는 중… 한 번 만들면 저장돼서 다음부터는 바로 열려요.", "%sレベルでこのパートを作成中… 一度作ると保存され、次からすぐ開きます。", "Making this part at %s level… It's saved, so next time it opens at once.", "Création de cette partie (niveau %s)… Enregistrée pour la prochaine fois.", "Creando esta parte (nivel %s)… Se guarda para abrirla al instante.", "Dieser Teil wird erstellt (Stufe %s)… Er wird gespeichert.", "正在按%s水平制作本部分…制作后会保存，下次直接打开。"),
        "art_need_key" to m("그림을 그리려면 설정 → AI 엔진에서 Google Gemini 또는 OpenAI 키를 넣어 주세요. (두 곳 모두 그림 생성은 유료 결제가 켜진 키가 필요해요)", "絵を描くには、設定→AIエンジンでGoogle GeminiかOpenAIのキーを入れてください（どちらも画像生成は有料設定のキーが必要です）。", "To draw pictures, add a Google Gemini or OpenAI key in Settings → AI engine (both need billing turned on for images).", "Pour dessiner, ajoutez une clé Google Gemini ou OpenAI (facturation activée requise pour les images).", "Para dibujar, añade una clave de Google Gemini u OpenAI (las imágenes requieren facturación activada).", "Zum Zeichnen einen Google-Gemini- oder OpenAI-Schlüssel eintragen (Bilder brauchen aktivierte Abrechnung).", "要绘图，请在设置→AI 引擎中填写 Google Gemini 或 OpenAI 密钥（两者生成图片都需开通付费）。"),
        "art_info" to m("🎨 문장마다 그 장면을 그림으로 그려요.\n\n• 내 Gemini(또는 OpenAI) 키로 그리고, 그림 한 장에 약 50~100원 정도 요금이 나와요. 무료 키로는 그려지지 않을 수 있어요.\n• 한 번 그린 그림은 폰에 저장돼서 다시 돈이 들지 않아요.\n• 그림 버튼을 누를 때마다 그리기 → 숨기기 → 다시 보이기로 바뀌어요.\n• 그림 위에서 밀면 앞뒤 감기예요. 설정에서 '그림 던져서 숨기기'로 바꿀 수 있어요.\n• 그림을 길게 누르면 다시 그릴 수 있어요.", "🎨 文ごとにその場面を絵にします。\n\n• 自分のGemini（またはOpenAI）キーで描き、1枚あたり数円〜十数円かかります。無料キーでは描けない場合があります。\n• 一度描いた絵はスマホに保存され、再び料金はかかりません。\n• 絵ボタンを押すたびに 描く→隠す→再表示 になります。\n• 絵の上のスワイプは早送り・巻き戻しです。設定で「投げて隠す」に変更できます。\n• 絵を長押しで描き直せます。", "🎨 Draws the scene of a sentence.\n\n• Uses your own Gemini (or OpenAI) key; each picture costs a few cents. Free keys may not be able to draw.\n• Pictures are saved on the phone, so each is paid once.\n• The picture button draws, hides, and shows it again.\n• Swiping on a picture skips forward/back; in Settings you can make it throw the picture away instead.\n• Long-press a picture to draw it again.", "🎨 Dessine la scène d'une phrase.\n\n• Utilise votre clé Gemini (ou OpenAI) ; quelques centimes par image.\n• Les images sont enregistrées : payées une seule fois.\n• Lancez une image pour la masquer. Le bouton image dessine, masque puis réaffiche.\n• Appui long pour redessiner.", "🎨 Dibuja la escena de una frase.\n\n• Usa tu clave de Gemini (u OpenAI); unos céntimos por imagen.\n• Las imágenes se guardan: se pagan una vez.\n• Lanza una imagen para ocultarla. El botón de imagen dibuja, oculta y vuelve a mostrar.\n• Mantén pulsada una imagen para redibujarla.", "🎨 Zeichnet die Szene eines Satzes.\n\n• Nutzt deinen Gemini- (oder OpenAI-)Schlüssel; wenige Cent pro Bild.\n• Bilder werden gespeichert und nur einmal bezahlt.\n• Bild wegwerfen blendet es aus. Die Bildtaste zeichnet, blendet aus und zeigt wieder.\n• Bild lange drücken zum Neuzeichnen.", "🎨 为每句话画出场景。\n\n• 使用你自己的 Gemini（或 OpenAI）密钥，每张约几美分。\n• 画好的图保存在手机上，只付一次费。\n• 按住图片向任意方向甩出即可隐藏。图片按钮依次为：绘制→隐藏→重新显示。\n• 长按图片可重画。"),
        "art_go" to m("그려 주세요", "描いて", "Draw it", "Dessiner", "Dibujar", "Zeichnen", "开始绘制"),
        "art_drawing" to m("🎨 그림을 그리는 중… (10~30초)", "🎨 描いています…（10〜30秒）", "🎨 Drawing… (10–30 s)", "🎨 Dessin en cours… (10–30 s)", "🎨 Dibujando… (10–30 s)", "🎨 Wird gezeichnet… (10–30 s)", "🎨 正在绘制…（10～30秒）"),
        "art_failed" to m("그림은 그리지 못했어요 (문장 학습은 그대로 쓸 수 있어요): %s", "絵は描けませんでした（文の学習はそのまま使えます）: %s", "Couldn't draw the picture (the lesson itself is fine): %s", "Image impossible (la leçon reste disponible) : %s", "No se pudo dibujar (la lección sigue disponible): %s", "Bild fehlgeschlagen (die Lektion bleibt): %s", "无法绘图（句子学习照常可用）：%s"),
        "art_redraw" to m("이 장면을 다시 그릴까요? (요금이 한 번 더 나와요)", "この場面を描き直しますか？（もう一度料金がかかります）", "Draw this scene again? (It costs once more.)", "Redessiner cette scène ? (coût supplémentaire)", "¿Redibujar esta escena? (Cuesta otra vez)", "Szene neu zeichnen? (Kostet erneut)", "重新绘制此场景？（会再次收费）"),
        "art_redraw_yes" to m("다시 그리기", "描き直す", "Draw again", "Redessiner", "Redibujar", "Neu zeichnen", "重新绘制"),
        "books_retry" to m("✨ AI로 다시 만들기", "✨ AIでもう一度作る", "✨ Make it with AI again", "✨ Refaire avec l'IA", "✨ Volver a crear con IA", "✨ Mit KI neu erstellen", "✨ 用 AI 重新制作"),
        "art_hide_all" to m("🎨 그림 모두 숨기기", "🎨 絵をすべて隠す", "🎨 Hide all pictures", "🎨 Masquer les images", "🎨 Ocultar imágenes", "🎨 Bilder ausblenden", "🎨 隐藏全部图片"),
        "art_show_all" to m("🎨 그린 그림 모두 보이기", "🎨 描いた絵をすべて表示", "🎨 Show all drawn pictures", "🎨 Afficher les images", "🎨 Mostrar imágenes", "🎨 Bilder einblenden", "🎨 显示全部已画图片"),
        "pack_title" to m("📦 글·그림 DB 내보내기/가져오기", "📦 文・絵DBの書き出し/取り込み", "📦 Export / import text & picture DB", "📦 Exporter / importer la base", "📦 Exportar / importar base de datos", "📦 Datenbank exportieren / importieren", "📦 导出/导入 文字·图片数据库"),
        "pack_export" to m("📦 내보내기", "📦 書き出す", "📦 Export", "📦 Exporter", "📦 Exportar", "📦 Exportieren", "📦 导出"),
        "pack_import" to m("📥 가져오기", "📥 取り込む", "📥 Import", "📥 Importer", "📥 Importar", "📥 Importieren", "📥 导入"),
        "pack_situations" to m("💬 상황별 회화 DB", "💬 場面別会話DB", "💬 Situation dialogues DB", "💬 Dialogues par situation", "💬 Diálogos por situación", "💬 Situationsdialoge", "💬 情景会话数据库"),
        "pack_export_title" to m("패키지에 넣을 내용 (글·그림 함께)", "パッケージに入れる内容（文と絵）", "What to put in the package (text and pictures)", "Contenu du paquet (textes et images)", "Contenido del paquete (texto e imágenes)", "Inhalt des Pakets (Text und Bilder)", "打包内容（文字和图片）"),
        "pack_export_go" to m("내보내기", "書き出す", "Export", "Exporter", "Exportar", "Exportieren", "导出"),
        "pack_working" to m("📦 처리 중…", "📦 処理中…", "📦 Working…", "📦 En cours…", "📦 Procesando…", "📦 Wird bearbeitet…", "📦 处理中…"),
        "pack_exported" to m("📦 저장했어요: %s", "📦 保存しました: %s", "📦 Saved: %s", "📦 Enregistré : %s", "📦 Guardado: %s", "📦 Gespeichert: %s", "📦 已保存：%s"),
        "pack_imported" to m("📥 가져왔어요: 책 %1\$d권, 학습 %2\$d개, 그림 %3\$d장, 회화 %4\$d개", "📥 取り込みました: 本%1\$d冊、レッスン%2\$d、絵%3\$d枚、会話%4\$d", "📥 Imported: %1\$d books, %2\$d lessons, %3\$d pictures, %4\$d dialogue files", "📥 Importé : %1\$d livres, %2\$d leçons, %3\$d images, %4\$d dialogues", "📥 Importado: %1\$d libros, %2\$d lecciones, %3\$d imágenes, %4\$d diálogos", "📥 Importiert: %1\$d Bücher, %2\$d Lektionen, %3\$d Bilder, %4\$d Dialoge", "📥 已导入：%1\$d 本书、%2\$d 课、%3\$d 张图、%4\$d 个会话"),
        "pack_nothing" to m("새로 가져올 내용이 없어요 (이미 있거나 패키지가 아니에요).", "新しく取り込む内容はありません。", "Nothing new to import (already here, or not a package).", "Rien de nouveau à importer.", "Nada nuevo que importar.", "Nichts Neues zu importieren.", "没有可导入的新内容。"),
        "books_downloading" to m("내려받는 중이에요. 앱을 닫아도 계속 받고, 다 받으면 알림으로 알려드려요.", "ダウンロード中です。アプリを閉じても続き、完了したら通知します。", "Downloading. It keeps going with the app closed and notifies you when done.", "Téléchargement. Il continue app fermée et vous prévient à la fin.", "Descargando. Sigue con la app cerrada y te avisa al terminar.", "Wird geladen. Läuft auch bei geschlossener App weiter und meldet sich danach.", "正在下载。关闭应用也会继续，完成后通知你。"),
        "books_empty" to m("이 책은 읽을 수 있는 본문을 찾지 못했어요.", "この本は本文を取り出せませんでした。", "Couldn't find readable text in this book.", "Texte introuvable dans ce livre.", "No se encontró texto legible.", "Kein lesbarer Text gefunden.", "未找到可阅读的正文。"),
        "books_start" to m("▶ %d부분부터 시작", "▶ パート%dから始める", "▶ Start at part %d", "▶ Commencer à la partie %d", "▶ Empezar en la parte %d", "▶ Bei Teil %d beginnen", "▶ 从第%d部分开始"),
        "books_continue" to m("▶ 이어 읽기 (%d부분)", "▶ 続きを読む（パート%d）", "▶ Continue (part %d)", "▶ Continuer (partie %d)", "▶ Continuar (parte %d)", "▶ Weiterlesen (Teil %d)", "▶ 继续阅读（第%d部分）"),
        "books_part" to m("%d부분", "パート%d", "Part %d", "Partie %d", "Parte %d", "Teil %d", "第%d部分"),
        "books_remove" to m("이 책 지우기", "この本を削除", "Remove this book", "Retirer ce livre", "Quitar este libro", "Buch entfernen", "删除此书"),
        "books_prev" to m("◀ 이전", "◀ 前へ", "◀ Previous", "◀ Précédent", "◀ Anterior", "◀ Zurück", "◀ 上一部分"),
        "books_next" to m("다음 ▶", "次へ ▶", "Next ▶", "Suivant ▶", "Siguiente ▶", "Weiter ▶", "下一部分 ▶"),
        "press_hint" to m("문장을 누르면 거기서부터 읽고, 읽는 중에 누르면 그 단어에서 멈춰요. 단어를 길게 누르면 뜻이 나와요. 문장 밖을 한 번 누르면 멈춤/재생, 오른쪽·왼쪽으로 밀면 앞으로·뒤로 감기예요.", "文を押すとそこから読み、読み上げ中に押すとその単語で止まります。単語を長押しすると意味が出ます。文の外をタップで一時停止/再生、右・左にスワイプで早送り・巻き戻し。", "Tap a sentence to read from it; tap while reading to pause on the word. Long-press a word for its meaning. Tap outside the sentences to pause/play; swipe right or left to skip forward or back.", "Touchez une phrase pour lire à partir d'elle, pendant la lecture pour mettre en pause. Appui long : sens du mot. Touchez hors des phrases pour pause/lecture ; glissez à droite/gauche pour avancer/reculer.", "Toca una frase para leer desde ahí; tócala leyendo para pausar. Mantén pulsada una palabra para su significado. Toca fuera de las frases para pausar/reproducir; desliza a derecha/izquierda para avanzar/retroceder.", "Satz tippen liest ab dort, beim Lesen tippen pausiert am Wort. Wort lange drücken zeigt die Bedeutung. Außerhalb der Sätze tippen: Pause/Weiter; nach rechts/links wischen: vor/zurück.", "点按句子从该处朗读，朗读时点按在该词暂停。长按单词显示释义。点按句子外可暂停/播放，向右/左滑动可快进/快退。"),
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
