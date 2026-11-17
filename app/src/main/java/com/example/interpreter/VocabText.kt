package com.example.interpreter

// Screen text for the word list, quizzes and word details. Order in m(): ko, ja, en, fr, es, de, zh.
object VocabText {

    fun t(key: String): String = table[key]?.let { it[I18n.lang] ?: it["en"] ?: it["ko"] } ?: key

    fun f(key: String, vararg args: Any): String = String.format(t(key), *args)

    private fun m(ko: String, ja: String, en: String, fr: String, es: String, de: String, zh: String) =
        mapOf("ko" to ko, "ja" to ja, "en" to en, "fr" to fr, "es" to es, "de" to de, "zh" to zh)

    private val table: Map<String, Map<String, String>> = mapOf(
        // Word list
        "all_langs" to m("모든 언어", "すべての言語", "All languages", "Toutes les langues", "Todos los idiomas", "Alle Sprachen", "所有语言"),
        "all_dates" to m("모든 날짜", "すべての日付", "All dates", "Toutes les dates", "Todas las fechas", "Alle Daten", "所有日期"),
        "search" to m("검색", "検索", "Search", "Rechercher", "Buscar", "Suchen", "搜索"),
        "quiz" to m("시험", "テスト", "Quiz", "Quiz", "Prueba", "Quiz", "测验"),
        "export" to m("내보내기", "共有", "Share", "Partager", "Compartir", "Teilen", "分享"),
        "empty" to m(
            "저장한 단어나 문장이 없어요.\n통역 화면에서 ☆를 누르거나, 글자를 길게 눌러 '단어 저장'을 고르세요.",
            "保存した単語や文がありません。\n通訳画面で☆を押すか、文字を長押しして「単語を保存」を選んでください。",
            "No saved words yet.\nTap ☆ on the interpret screen, or long-press text and choose “Save word”.",
            "Aucun mot enregistré.\nTouchez ☆ dans l'écran d'interprétation ou faites un appui long et choisissez « Enregistrer le mot ».",
            "Aún no hay palabras guardadas.\nToca ☆ en la pantalla de interpretación o mantén pulsado y elige «Guardar palabra».",
            "Noch keine Wörter gespeichert.\nTippe im Dolmetsch-Bildschirm auf ☆ oder drücke lange auf Text und wähle „Wort speichern“.",
            "还没有保存的单词。\n在口译界面点 ☆，或长按文字选择“保存单词”。"
        ),
        "no_meaning" to m("뜻 없음 · 시험을 시작하면 자동으로 채워요", "意味なし · テスト開始時に自動で補います", "No meaning yet · filled in when you start a quiz", "Pas de sens · ajouté au début du quiz", "Sin significado · se completa al empezar la prueba", "Keine Bedeutung · wird beim Quiz ergänzt", "暂无释义 · 开始测验时自动补全"),
        "example" to m("예) ", "例) ", "e.g. ", "ex. ", "ej. ", "z. B. ", "例："),
        "detail_menu" to m("단어별 상세 설명", "単語ごとの詳しい説明", "Word-by-word details", "Détails mot par mot", "Detalles palabra por palabra", "Wort-für-Wort-Erklärung", "逐词详解"),
        "copy" to m("복사", "コピー", "Copy", "Copier", "Copiar", "Kopieren", "复制"),
        "delete" to m("삭제", "削除", "Delete", "Supprimer", "Eliminar", "Löschen", "删除"),
        "deleted" to m("삭제했어요", "削除しました", "Deleted", "Supprimé", "Eliminado", "Gelöscht", "已删除"),
        "copied" to m("복사했어요", "コピーしました", "Copied", "Copié", "Copiado", "Kopiert", "已复制"),
        "nothing" to m("내보낼 항목이 없어요.", "共有する項目がありません。", "Nothing to share.", "Rien à partager.", "Nada que compartir.", "Nichts zu teilen.", "没有可分享的项目。"),
        "export_title" to m("단어장 내보내기", "単語帳を共有", "Share word list", "Partager les mots", "Compartir palabras", "Wortliste teilen", "分享单词本"),

        // Quiz setup
        "need2" to m("시험을 보려면 단어가 2개 이상 있어야 해요.", "テストには単語が2つ以上必要です。", "You need at least 2 words for a quiz.", "Il faut au moins 2 mots pour un quiz.", "Necesitas al menos 2 palabras.", "Für ein Quiz brauchst du mindestens 2 Wörter.", "测验至少需要 2 个单词。"),
        "filling" to m("뜻이 없는 단어 %d개의 뜻을 채우는 중…", "意味のない単語%d個を補完中…", "Filling in meanings for %d words…", "Ajout du sens pour %d mots…", "Completando %d significados…", "Bedeutungen für %d Wörter werden ergänzt…", "正在补全 %d 个单词的释义…"),
        "mode1" to m("외국어 보고 뜻 고르기", "外国語を見て意味を選ぶ", "See the word, pick the meaning", "Voir le mot, choisir le sens", "Ver la palabra, elegir el significado", "Wort sehen, Bedeutung wählen", "看外语选释义"),
        "mode2" to m("뜻 보고 외국어 고르기", "意味を見て外国語を選ぶ", "See the meaning, pick the word", "Voir le sens, choisir le mot", "Ver el significado, elegir la palabra", "Bedeutung sehen, Wort wählen", "看释义选外语"),
        "mode3" to m("카드 넘기며 외우기", "カードをめくって覚える", "Flash cards", "Cartes mémoire", "Tarjetas", "Karteikarten", "翻卡记忆"),
        "mode4" to m("틀렸던 것만 (뜻 고르기, %d개)", "間違えたものだけ (%d個)", "Only mistakes (%d)", "Seulement les erreurs (%d)", "Solo errores (%d)", "Nur Fehler (%d)", "只练错题（%d 个）"),
        "quiz_title" to m("시험 · %d개", "テスト · %d個", "Quiz · %d", "Quiz · %d", "Prueba · %d", "Quiz · %d", "测验 · %d 个"),
        "need2m" to m("뜻이 있는 단어가 2개 이상 필요해요.", "意味のある単語が2つ以上必要です。", "You need at least 2 words with meanings.", "Il faut au moins 2 mots avec un sens.", "Necesitas al menos 2 palabras con significado.", "Mindestens 2 Wörter mit Bedeutung nötig.", "至少需要 2 个有释义的单词。"),

        // Quiz
        "q_quit" to m("그만하기", "やめる", "Quit", "Quitter", "Salir", "Beenden", "退出"),
        "q_progress" to m("%1\$d / %2\$d   ·   맞힘 %3\$d", "%1\$d / %2\$d   ·   正解 %3\$d", "%1\$d / %2\$d   ·   correct %3\$d", "%1\$d / %2\$d   ·   justes %3\$d", "%1\$d / %2\$d   ·   aciertos %3\$d", "%1\$d / %2\$d   ·   richtig %3\$d", "%1\$d / %2\$d   ·   答对 %3\$d"),
        "q_show" to m("뜻 보기", "意味を見る", "Show meaning", "Voir le sens", "Ver significado", "Bedeutung zeigen", "查看释义"),
        "q_know" to m("✔ 알아요", "✔ 知ってる", "✔ I know it", "✔ Je sais", "✔ Lo sé", "✔ Weiß ich", "✔ 认识"),
        "q_dont" to m("✘ 몰라요", "✘ 知らない", "✘ Don't know", "✘ Je ne sais pas", "✘ No lo sé", "✘ Weiß ich nicht", "✘ 不认识"),
        "q_correct" to m("정답!", "正解！", "Correct!", "Correct !", "¡Correcto!", "Richtig!", "答对了！"),
        "q_wrong" to m("오답", "不正解", "Wrong", "Faux", "Incorrecto", "Falsch", "答错了"),
        "q_next" to m("다음", "次へ", "Next", "Suivant", "Siguiente", "Weiter", "下一题"),
        "q_result_btn" to m("결과 보기", "結果を見る", "See results", "Voir les résultats", "Ver resultados", "Ergebnis", "查看结果"),
        "q_result" to m("결과", "結果", "Results", "Résultats", "Resultados", "Ergebnis", "结果"),
        "q_perfect" to m("모두 맞혔어요! 👏", "全問正解！👏", "All correct! 👏", "Tout juste ! 👏", "¡Todo correcto! 👏", "Alles richtig! 👏", "全部答对！👏"),
        "q_missed" to m("틀린 단어", "間違えた単語", "Missed words", "Mots ratés", "Palabras falladas", "Falsche Wörter", "答错的单词"),
        "q_retry_wrong" to m("틀린 것만 다시", "間違えたものだけ再挑戦", "Retry mistakes", "Refaire les erreurs", "Repetir errores", "Fehler wiederholen", "只重做错题"),
        "q_restart" to m("처음부터 다시", "最初から", "Start over", "Recommencer", "Empezar de nuevo", "Neu starten", "从头再来"),

        // Word details
        "making_detail" to m("단어 설명을 만드는 중… (%s)", "単語の説明を作成中… (%s)", "Creating word details… (%s)", "Création des détails… (%s)", "Creando detalles… (%s)", "Erklärung wird erstellt… (%s)", "正在生成单词详解…（%s）"),
        "detail_fallback" to m("%s 설명에 실패해서 기기 분석으로 보여드려요", "%s の説明に失敗したため端末分析で表示します", "%s failed, showing on-device analysis", "%s a échoué, analyse locale affichée", "%s falló, se muestra el análisis local", "%s fehlgeschlagen, Geräteanalyse wird gezeigt", "%s 失败，改为显示设备分析"),
        "play_sentence" to m("🔊 문장 전체 듣기", "🔊 文全体を聞く", "🔊 Play the sentence", "🔊 Écouter la phrase", "🔊 Escuchar la frase", "🔊 Satz anhören", "🔊 播放整句"),
        "grammar" to m("문법 포인트", "文法ポイント", "Grammar points", "Points de grammaire", "Gramática", "Grammatik", "语法要点"),
        "source" to m("설명: %s", "説明: %s", "Explained by: %s", "Explication : %s", "Explicación: %s", "Erklärt von: %s", "讲解：%s"),
        "offline_tip" to m(
            "번역 엔진에 Gemini, Claude, 맥북 LLM 등을 설정하면 활용과 문법까지 자세히 설명해요.",
            "翻訳エンジンにGeminiなどを設定すると、活用や文法まで詳しく説明します。",
            "Set up Gemini, Claude or a local LLM to get conjugation and grammar notes.",
            "Configurez Gemini, Claude ou un LLM local pour la conjugaison et la grammaire.",
            "Configura Gemini, Claude o un LLM local para conjugación y gramática.",
            "Mit Gemini, Claude oder einem lokalen LLM gibt es Konjugation und Grammatik dazu.",
            "设置 Gemini、Claude 或本地 LLM 可获得变形和语法讲解。"
        ),
        "regen" to m("다시 만들기", "作り直す", "Recreate", "Recréer", "Rehacer", "Neu erstellen", "重新生成"),
        "base_form" to m("기본형 %s", "基本形 %s", "base form %s", "forme de base %s", "forma base %s", "Grundform %s", "原形 %s"),
        "add_vocab" to m("+ 단어장", "+ 単語帳", "+ Words", "+ Mots", "+ Palabras", "+ Wörter", "+ 单词本"),
        "added" to m("✔ 추가됨", "✔ 追加済み", "✔ Added", "✔ Ajouté", "✔ Añadido", "✔ Hinzugefügt", "✔ 已添加"),
        "added_toast" to m("단어장에 추가: %s", "単語帳に追加: %s", "Added: %s", "Ajouté : %s", "Añadido: %s", "Hinzugefügt: %s", "已添加：%s"),
        "offline_label" to m("기기 분석", "端末分析", "On-device analysis", "Analyse locale", "Análisis local", "Geräteanalyse", "设备分析"),
        "zh_note" to m(
            "중국어는 띄어쓰기가 없어서, 단어별 설명에는 AI 엔진이 필요해요.",
            "中国語は分かち書きがないため、単語ごとの説明にはAIエンジンが必要です。",
            "Chinese has no spaces, so word-by-word details need an AI engine.",
            "Le chinois n'a pas d'espaces : les détails mot par mot nécessitent un moteur IA.",
            "El chino no tiene espacios: los detalles por palabra necesitan un motor de IA.",
            "Chinesisch hat keine Leerzeichen; dafür ist eine KI-Engine nötig.",
            "中文没有空格，逐词讲解需要 AI 引擎。"
        )
    )
}