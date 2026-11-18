package com.example.interpreter

// Screen text for the conversation tab. Order in m(): ko, ja, en, fr, es, de, zh.
object TalkText {

    fun t(key: String): String = table[key]?.let { it[I18n.lang] ?: it["en"] ?: it["ko"] } ?: key

    fun f(key: String, vararg args: Any): String = String.format(t(key), *args)

    private fun m(ko: String, ja: String, en: String, fr: String, es: String, de: String, zh: String) =
        mapOf("ko" to ko, "ja" to ja, "en" to en, "fr" to fr, "es" to es, "de" to de, "zh" to zh)

    private val table: Map<String, Map<String, String>> = mapOf(
        // Modes and setup
        "mode_situation" to m("📍 상황별 회화", "📍 場面別会話", "📍 Situations", "📍 Situations", "📍 Situaciones", "📍 Situationen", "📍 情景会话"),
        "mode_ai" to m("🤖 AI 자유 회화", "🤖 AI自由会話", "🤖 AI chat", "🤖 Discussion IA", "🤖 Charla con IA", "🤖 KI-Gespräch", "🤖 AI 自由对话"),
        "l_lang" to m("언어", "言語", "Language", "Langue", "Idioma", "Sprache", "语言"),
        "l_topic" to m("주제", "テーマ", "Topic", "Sujet", "Tema", "Thema", "话题"),
        "l_level" to m("수준 (상황별 회화 연습에도 적용돼요)", "レベル (場面別会話の練習にも適用)", "Level (also used for situation practice)", "Niveau (aussi pour les situations)", "Nivel (también para las situaciones)", "Niveau (auch für Situationen)", "难度（也用于情景练习）"),
        "l_role" to m("AI 역할", "AIの役割", "AI role", "Rôle de l'IA", "Papel de la IA", "Rolle der KI", "AI 角色"),
        "custom_hint" to m("주제를 적어주세요 (예: 친구 집에 초대받았어요)", "テーマを入力 (例: 友達の家に招待された)", "Describe the topic (e.g. invited to a friend's home)", "Décrivez le sujet (ex. invité chez un ami)", "Describe el tema (p. ej. invitado a casa de un amigo)", "Thema beschreiben (z. B. bei Freunden eingeladen)", "输入话题（例：受邀去朋友家）"),
        "role_hint" to m("AI 역할 (비워두면 주제에 맞게 정해요)", "AIの役割 (空欄ならテーマに合わせます)", "AI role (leave empty to match the topic)", "Rôle de l'IA (vide = selon le sujet)", "Papel de la IA (vacío = según el tema)", "Rolle der KI (leer = passend zum Thema)", "AI 角色（留空则按话题设定）"),
        "start_chat" to m("대화 시작", "会話を始める", "Start conversation", "Commencer", "Empezar", "Gespräch starten", "开始对话"),
        "need_llm_note" to m(
            "⚠ 회화에는 AI 엔진이 필요해요. 설정 → 번역 엔진에서 Gemini, Claude, 맥북 LLM 등을 설정하세요.",
            "⚠ 会話にはAIエンジンが必要です。設定 → 翻訳エンジンでGeminiなどを設定してください。",
            "⚠ Conversation needs an AI engine. Set up Gemini, Claude, a local LLM, etc. in Settings → Translation engine.",
            "⚠ La conversation nécessite un moteur IA. Configurez Gemini, Claude, etc. dans Réglages → Moteur.",
            "⚠ La conversación necesita un motor de IA. Configura Gemini, Claude, etc. en Ajustes → Motor.",
            "⚠ Für Gespräche wird eine KI-Engine benötigt. Richte Gemini, Claude usw. unter Einstellungen → Engine ein.",
            "⚠ 对话需要 AI 引擎。请在 设置 → 翻译引擎 中设置 Gemini、Claude 等。"
        ),
        "engine_note" to m("대화 엔진: %s", "会話エンジン: %s", "Chat engine: %s", "Moteur de discussion : %s", "Motor de conversación: %s", "Gesprächs-Engine: %s", "对话引擎：%s"),
        "need_llm_toast" to m("먼저 설정 → 번역 엔진에서 AI 엔진을 설정하세요.", "先に設定 → 翻訳エンジンでAIエンジンを設定してください。", "Set up an AI engine in Settings → Translation engine first.", "Configurez d'abord un moteur IA dans Réglages.", "Configura primero un motor de IA en Ajustes.", "Richte zuerst eine KI-Engine in den Einstellungen ein.", "请先在设置中设置 AI 引擎。"),

        // Chat
        "hint_btn" to m("힌트", "ヒント", "Hint", "Indice", "Pista", "Tipp", "提示"),
        "end_btn" to m("끝내기", "終了", "End", "Terminer", "Terminar", "Beenden", "结束"),
        "input_hint" to m("말하거나 입력하세요 (내 언어도 OK)", "話すか入力 (自分の言語でもOK)", "Speak or type (your language is OK too)", "Parlez ou tapez (votre langue aussi)", "Habla o escribe (tu idioma también vale)", "Sprich oder tippe (auch in deiner Sprache)", "说话或输入（也可以用你的语言）"),
        "send" to m("보내기", "送信", "Send", "Envoyer", "Enviar", "Senden", "发送"),
        "auto_speak" to m("AI 말 읽어주기", "AIの発言を読み上げ", "Read AI aloud", "Lire l'IA à voix haute", "Leer la IA en voz alta", "KI vorlesen", "朗读 AI 回复"),
        "mic" to m("🎤 말하기", "🎤 話す", "🎤 Speak", "🎤 Parler", "🎤 Hablar", "🎤 Sprechen", "🎤 说话"),
        "mic_on" to m("⏹ 듣는 중 (누르면 끝)", "⏹ 聞き取り中 (押すと終了)", "⏹ Listening (tap to finish)", "⏹ Écoute (appuyez pour finir)", "⏹ Escuchando (toca para terminar)", "⏹ Hört zu (tippen zum Beenden)", "⏹ 正在听（点按结束）"),
        "busy" to m("AI가 답하는 중이에요.", "AIが返答中です。", "The AI is replying.", "L'IA répond.", "La IA está respondiendo.", "Die KI antwortet gerade.", "AI 正在回复。"),
        "no_reply" to m("응답을 받지 못했어요: %s", "応答がありませんでした: %s", "No reply: %s", "Pas de réponse : %s", "Sin respuesta: %s", "Keine Antwort: %s", "未收到回复：%s"),
        "retry" to m("다시 시도", "再試行", "Retry", "Réessayer", "Reintentar", "Erneut versuchen", "重试"),
        "better" to m("✏ 더 자연스럽게", "✏ より自然に", "✏ More natural", "✏ Plus naturel", "✏ Más natural", "✏ Natürlicher", "✏ 更自然的说法"),
        "listen" to m("🔊 듣기", "🔊 聞く", "🔊 Listen", "🔊 Écouter", "🔊 Escuchar", "🔊 Anhören", "🔊 听"),
        "replay" to m("🔊 다시 듣기", "🔊 もう一度", "🔊 Replay", "🔊 Réécouter", "🔊 Repetir", "🔊 Nochmal", "🔊 再听"),
        "save" to m("☆ 저장", "☆ 保存", "☆ Save", "☆ Enregistrer", "☆ Guardar", "☆ Speichern", "☆ 保存"),
        "saved_mark" to m("★ 저장됨", "★ 保存済み", "★ Saved", "★ Enregistré", "★ Guardado", "★ Gespeichert", "★ 已保存"),
        "saved_toast" to m("단어장에 저장했어요", "単語帳に保存しました", "Saved to your word list", "Enregistré dans vos mots", "Guardado en tus palabras", "In deinen Wörtern gespeichert", "已保存到单词本"),
        "hint_wait" to m("💡 힌트 만드는 중…", "💡 ヒントを作成中…", "💡 Making a hint…", "💡 Création d'un indice…", "💡 Creando una pista…", "💡 Tipp wird erstellt…", "💡 正在生成提示…"),
        "hint_fail" to m("힌트를 받지 못했어요.", "ヒントを取得できませんでした。", "Couldn't get a hint.", "Impossible d'obtenir un indice.", "No se pudo obtener una pista.", "Kein Tipp erhalten.", "未能获取提示。"),
        "hint_title" to m("💡 이렇게 말해보세요", "💡 こう言ってみましょう", "💡 Try saying", "💡 Essayez de dire", "💡 Prueba a decir", "💡 Versuch es so", "💡 试着这样说"),
        "use_this" to m("이 문장으로 말하기", "この文で話す", "Use this", "Utiliser cette phrase", "Usar esta frase", "Diesen Satz nutzen", "用这句话"),
        "hint_after" to m("AI가 말한 다음에 힌트를 볼 수 있어요.", "AIが話した後にヒントを見られます。", "You can get a hint after the AI speaks.", "Indice disponible après la réponse de l'IA.", "La pista está disponible después de que hable la IA.", "Tipps gibt es, nachdem die KI gesprochen hat.", "AI 说完后才能查看提示。"),
        "not_heard" to m("잘 못 들었어요. 다시 말해 주세요.", "聞き取れませんでした。もう一度話してください。", "Didn't catch that. Please say it again.", "Je n'ai pas compris. Répétez, s'il vous plaît.", "No te entendí. Repítelo, por favor.", "Nicht verstanden. Bitte noch einmal.", "没听清，请再说一遍。"),

        // Topics and levels
        "tp_cafe" to m("카페에서 주문하기", "カフェで注文", "Ordering at a café", "Commander au café", "Pedir en una cafetería", "Im Café bestellen", "在咖啡馆点单"),
        "tp_hotel" to m("호텔 체크인", "ホテルのチェックイン", "Hotel check-in", "Arrivée à l'hôtel", "Registro en el hotel", "Hotel-Check-in", "酒店入住"),
        "tp_rest" to m("식당에서 식사", "レストランで食事", "Eating at a restaurant", "Au restaurant", "Comer en un restaurante", "Im Restaurant essen", "在餐厅用餐"),
        "tp_pharm" to m("약국·병원", "薬局・病院", "Pharmacy & clinic", "Pharmacie et clinique", "Farmacia y clínica", "Apotheke & Arzt", "药店·医院"),
        "tp_way" to m("길 묻기·교통", "道を尋ねる・交通", "Directions & transport", "Itinéraire et transports", "Direcciones y transporte", "Weg & Verkehr", "问路·交通"),
        "tp_shop" to m("쇼핑", "ショッピング", "Shopping", "Shopping", "Compras", "Einkaufen", "购物"),
        "tp_biz" to m("비즈니스 미팅", "ビジネス会議", "Business meeting", "Réunion d'affaires", "Reunión de negocios", "Geschäftstreffen", "商务会议"),
        "tp_cowork" to m("직장 동료와 잡담", "同僚との雑談", "Chat with a coworker", "Discussion entre collègues", "Charla con un compañero", "Plausch mit Kollegen", "和同事闲聊"),
        "tp_news" to m("뉴스 토론", "ニュースについて話す", "Discussing the news", "Parler de l'actualité", "Hablar de noticias", "Über Nachrichten sprechen", "讨论新闻"),
        "tp_intro" to m("자기소개·취미", "自己紹介・趣味", "Introductions & hobbies", "Présentations et loisirs", "Presentaciones y aficiones", "Vorstellung & Hobbys", "自我介绍·爱好"),
        "tp_custom" to m("직접 입력", "自由入力", "Custom", "Personnalisé", "Personalizado", "Eigenes Thema", "自定义"),
        "lv_1" to m("초급", "初級", "Beginner", "Débutant", "Principiante", "Anfänger", "初级"),
        "lv_2" to m("중급", "中級", "Intermediate", "Intermédiaire", "Intermedio", "Mittelstufe", "中级"),
        "lv_3" to m("고급", "上級", "Advanced", "Avancé", "Avanzado", "Fortgeschritten", "高级"),

        // Situations (used by the next step)
        "s_where" to m("📍 어디에 계세요?", "📍 どこにいますか？", "📍 Where are you?", "📍 Où êtes-vous ?", "📍 ¿Dónde estás?", "📍 Wo bist du?", "📍 你在哪里？"),
        "back" to m("← 뒤로", "← 戻る", "← Back", "← Retour", "← Atrás", "← Zurück", "← 返回"),
        "s_help" to m(
            "장소를 고르면 자주 하는 질문과 대화 예문을 보여줘요. 처음 열 때 AI가 만들어 저장하고, 다음부터는 바로 열려요.",
            "場所を選ぶと、よくある質問と会話例を表示します。初回はAIが作成して保存し、次からはすぐ開きます。",
            "Pick a place to see common questions and sample dialogues. The AI creates them the first time and saves them.",
            "Choisissez un lieu pour voir les questions courantes et des dialogues. L'IA les crée la première fois puis les enregistre.",
            "Elige un lugar para ver preguntas comunes y diálogos. La IA los crea la primera vez y los guarda.",
            "Wähle einen Ort für häufige Fragen und Beispieldialoge. Die KI erstellt sie beim ersten Mal und speichert sie.",
            "选择地点即可查看常见问题和对话示例。首次由 AI 生成并保存。"
        ),
        "s_need_llm" to m("⚠ 처음 만들 때는 AI 엔진이 필요해요. 설정 → 번역 엔진에서 설정하세요.", "⚠ 初回作成にはAIエンジンが必要です。設定 → 翻訳エンジンで設定してください。", "⚠ An AI engine is needed the first time. Set one up in Settings → Translation engine.", "⚠ Un moteur IA est nécessaire la première fois. Configurez-le dans Réglages.", "⚠ Se necesita un motor de IA la primera vez. Configúralo en Ajustes.", "⚠ Beim ersten Mal wird eine KI-Engine benötigt. Richte sie in den Einstellungen ein.", "⚠ 首次生成需要 AI 引擎，请在设置中设置。"),
        "s_making_q" to m("%1\$s에서 자주 하는 질문을 만드는 중… (%2\$s)", "%1\$sでよくある質問を作成中… (%2\$s)", "Creating common questions for %1\$s… (%2\$s)", "Création des questions pour %1\$s… (%2\$s)", "Creando preguntas para %1\$s… (%2\$s)", "Fragen für %1\$s werden erstellt… (%2\$s)", "正在生成%1\$s的常见问题…（%2\$s）"),
        "s_failed" to m("만들지 못했어요: %s", "作成できませんでした: %s", "Couldn't create it: %s", "Échec de la création : %s", "No se pudo crear: %s", "Erstellen fehlgeschlagen: %s", "生成失败：%s"),
        "s_format" to m("형식을 읽지 못했어요", "形式を読み取れませんでした", "unreadable format", "format illisible", "formato ilegible", "unlesbares Format", "格式无法读取"),
        "s_q_help" to m("질문을 누르면 2~3분 분량의 대화 예문이 나와요.", "質問を押すと2〜3分の会話例が表示されます。", "Tap a question for a 2–3 minute sample dialogue.", "Touchez une question pour un dialogue de 2–3 minutes.", "Toca una pregunta para ver un diálogo de 2–3 minutos.", "Tippe auf eine Frage für einen 2–3-minütigen Dialog.", "点按问题可查看 2–3 分钟的对话示例。"),
        "s_regen_list" to m("목록 다시 만들기", "一覧を作り直す", "Recreate list", "Recréer la liste", "Rehacer la lista", "Liste neu erstellen", "重新生成列表"),
        "s_making_d" to m("대화 예문을 만드는 중… 30초~1분 정도 걸릴 수 있어요. (%s)", "会話例を作成中… 30秒〜1分かかることがあります。(%s)", "Creating the dialogue… this can take 30–60 seconds. (%s)", "Création du dialogue… 30 à 60 secondes. (%s)", "Creando el diálogo… puede tardar 30–60 segundos. (%s)", "Dialog wird erstellt… kann 30–60 Sekunden dauern. (%s)", "正在生成对话… 可能需要 30–60 秒。（%s）"),
        "s_tap_hint" to m("재생 중에 대사를 누르면 그 단어에서 멈추고, 멈춘 대사를 다시 누르면 이어서, 다른 대사를 누르면 그 대사부터 재생해요. 길게 누르면 단어·숙어를 단어장에 저장해요.", "再生中にセリフを押すとその単語で止まり、同じセリフをもう一度押すと続きから、別のセリフを押すとそこから再生します。長押しで単語・熟語を単語帳に保存します。", "Tap a line while playing to pause on the word; tap it again to resume, or tap another line to play from there. Long-press to save words or phrases.", "Touchez une réplique pour mettre en pause, ou à l'arrêt pour lire à partir de là. Appui long pour enregistrer des mots ou expressions.", "Toca una frase para pausar, o en pausa para reproducir desde ahí. Mantén pulsado para guardar palabras o expresiones.", "Tippen pausiert, im Stopp spielt Tippen ab dieser Zeile. Lange drücken speichert Wörter oder Wendungen.", "播放时点按台词暂停，暂停时点按从该句播放。长按可把单词、短语存入单词本。"),
        "w_title" to m("단어·숙어 저장", "単語・熟語を保存", "Save words & phrases", "Enregistrer mots et expressions", "Guardar palabras y expresiones", "Wörter & Wendungen speichern", "保存单词和短语"),
        "w_words" to m("단어를 누르면 바로 저장돼요", "単語を押すとすぐ保存されます", "Tap a word to save it", "Touchez un mot pour l'enregistrer", "Toca una palabra para guardarla", "Tippe auf ein Wort zum Speichern", "点按单词即可保存"),
        "w_phrase" to m("숙어나 여러 단어는 문장을 길게 눌러 범위를 고른 뒤 '단어 저장'을 누르세요.", "熟語や複数の語は、文を長押しして範囲を選び「単語を保存」を押してください。", "For a phrase, long-press the sentence, select it, then choose “Save word”.", "Pour une expression, faites un appui long sur la phrase, sélectionnez-la puis « Enregistrer le mot ».", "Para una expresión, mantén pulsada la frase, selecciónala y elige «Guardar palabra».", "Für eine Wendung den Satz lange drücken, markieren und „Wort speichern“ wählen.", "短语请长按句子选择范围，然后点“保存单词”。"),

        "s_play" to m("▶ 전체 듣기", "▶ 全部聞く", "▶ Play all", "▶ Tout écouter", "▶ Escuchar todo", "▶ Alles abspielen", "▶ 全部播放"),
        "s_stop" to m("⏹ 멈추기", "⏹ 停止", "⏹ Stop", "⏹ Arrêter", "⏹ Detener", "⏹ Stopp", "⏹ 停止"),
        "s_resume" to m("▶ 이어서 듣기", "▶ 続きから聞く", "▶ Resume", "▶ Reprendre", "▶ Continuar", "▶ Fortsetzen", "▶ 继续播放"),
        "s_practice" to m("🎭 이 상황으로 연습", "🎭 この場面で練習", "🎭 Practice this", "🎭 S'entraîner", "🎭 Practicar esto", "🎭 Das üben", "🎭 练习这个情景"),
        "s_disclaimer" to m("참고용 예문이에요. 실제 절차나 표현은 장소마다 다를 수 있어요.", "参考用の例文です。実際の手続きや表現は場所によって異なることがあります。", "Sample dialogue for reference. Real procedures and wording may differ.", "Dialogue indicatif. Les procédures réelles peuvent varier.", "Diálogo de referencia. Los procedimientos reales pueden variar.", "Beispieldialog zur Orientierung. Abläufe können abweichen.", "示例对话仅供参考，实际流程和表达可能不同。"),
        "s_regen_d" to m("대화 다시 만들기", "会話を作り直す", "Recreate dialogue", "Recréer le dialogue", "Rehacer el diálogo", "Dialog neu erstellen", "重新生成对话"),
        "s_me" to m("🙋 나", "🙋 自分", "🙋 Me", "🙋 Moi", "🙋 Yo", "🙋 Ich", "🙋 我"),
        "s_other" to m("🧑‍💼 상대", "🧑‍💼 相手", "🧑‍💼 Staff", "🧑‍💼 Interlocuteur", "🧑‍💼 Personal", "🧑‍💼 Gegenüber", "🧑‍💼 对方"),
        "tts_wait" to m("음성 엔진을 준비하는 중이에요. 잠시 후 다시 눌러주세요.", "音声エンジンを準備中です。少し待ってからもう一度押してください。", "The voice engine is starting. Try again in a moment.", "Le moteur vocal démarre. Réessayez dans un instant.", "El motor de voz se está iniciando. Inténtalo en un momento.", "Die Sprachausgabe startet. Gleich nochmal versuchen.", "语音引擎正在启动，请稍后再试。")
    )
}