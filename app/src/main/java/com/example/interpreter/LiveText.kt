package com.example.interpreter

// Screen text for live conversation. Order in m(): ko, ja, en, fr, es, de, zh.
object LiveText {

    fun t(key: String): String = table[key]?.let { it[I18n.lang] ?: it["en"] ?: it["ko"] } ?: key

    fun f(key: String, vararg args: Any): String = String.format(t(key), *args)

    private fun m(ko: String, ja: String, en: String, fr: String, es: String, de: String, zh: String) =
        mapOf("ko" to ko, "ja" to ja, "en" to en, "fr" to fr, "es" to es, "de" to de, "zh" to zh)

    // Quick replies offered when no AI engine can suggest better ones, in the user's language.
    fun quickReplies(): List<String> = listOf("q1", "q2", "q3", "q4", "q5").map { t(it) }

    private val table: Map<String, Map<String, String>> = mapOf(
        "open" to m("🗣 실시간 회화", "🗣 リアルタイム会話", "🗣 Live conversation", "🗣 Conversation en direct", "🗣 Conversación en vivo", "🗣 Live-Gespräch", "🗣 实时对话"),
        "speak" to m("🎤 말하기", "🎤 話す", "🎤 Speak", "🎤 Parler", "🎤 Hablar", "🎤 Sprechen", "🎤 说话"),
        "listening" to m("⏹ 듣는 중 · 누르면 끝", "⏹ 聞き取り中 · 押すと終了", "⏹ Listening · tap to finish", "⏹ J'écoute · touchez pour finir", "⏹ Escuchando · toca para terminar", "⏹ Höre zu · tippen zum Beenden", "⏹ 正在听 · 点按结束"),
        "panel_hint" to m("마이크를 누르고 말하세요", "マイクを押して話してください", "Tap the mic and speak", "Touchez le micro et parlez", "Toca el micrófono y habla", "Mikrofon tippen und sprechen", "点按麦克风后说话"),
        "partner_lang" to m("상대 언어", "相手の言語", "Their language", "Sa langue", "Su idioma", "Ihre Sprache", "对方语言"),
        "scene_none" to m("장면 없음", "場面なし", "No scene", "Aucune scène", "Sin escena", "Keine Szene", "无场景"),
        "split_on" to m("🎧 이어폰 분리 켬", "🎧 イヤホン分離 オン", "🎧 Split audio on", "🎧 Audio séparé activé", "🎧 Audio dividido activado", "🎧 Audio getrennt an", "🎧 分离音频 开"),
        "split_off" to m("🎧 이어폰 분리 끔", "🎧 イヤホン分離 オフ", "🎧 Split audio off", "🎧 Audio séparé désactivé", "🎧 Audio dividido desactivado", "🎧 Audio getrennt aus", "🎧 分离音频 关"),
        "split_help" to m("내 번역은 이어폰으로, 상대에게 하는 말은 폰 스피커로 나와요. 휴대폰에 따라 지원되지 않을 수 있어요.", "自分向けの訳はイヤホンに、相手向けの言葉はスマホのスピーカーから流れます。機種によっては対応していません。", "Translations for you play in your earbuds; what you say plays on the phone speaker for them. Some phones don't support this.", "Vos traductions passent dans vos écouteurs, vos paroles traduites sur le haut-parleur. Certains téléphones ne le permettent pas.", "Tus traducciones suenan en tus auriculares y lo que dices, en el altavoz del teléfono. Algunos teléfonos no lo admiten.", "Übersetzungen für dich kommen über die Kopfhörer, deine Worte für sie über den Lautsprecher. Nicht jedes Handy unterstützt das.", "给你的译文从耳机播放，你说的话从手机扬声器播放给对方。部分手机不支持。"),
        "no_earbuds" to m("연결된 이어폰이 없어 모두 폰 스피커로 나와요.", "イヤホンが接続されていないため、すべてスピーカーから流れます。", "No earbuds connected, so everything plays on the speaker.", "Aucun écouteur connecté : tout passe sur le haut-parleur.", "No hay auriculares conectados; todo suena por el altavoz.", "Keine Kopfhörer verbunden, alles kommt über den Lautsprecher.", "未连接耳机，全部从扬声器播放。"),
        "link" to m("🔗 폰 연결", "🔗 スマホ接続", "🔗 Link phones", "🔗 Relier les téléphones", "🔗 Conectar teléfonos", "🔗 Handys verbinden", "🔗 连接手机"),
        "unlink" to m("⛓ 연결 끊기", "⛓ 接続を切る", "⛓ Unlink", "⛓ Déconnecter", "⛓ Desconectar", "⛓ Trennen", "⛓ 断开"),
        "link_searching" to m("주변 폰을 찾는 중… 상대 폰에서도 '폰 연결'을 눌러 주세요.", "近くのスマホを探しています… 相手のスマホでも「スマホ接続」を押してください。", "Looking for nearby phones… Tap 'Link phones' on the other phone too.", "Recherche… Touchez aussi « Relier » sur l'autre téléphone.", "Buscando… Toca también «Conectar» en el otro teléfono.", "Suche… Tippe auch auf dem anderen Handy auf „Verbinden“.", "正在查找附近的手机… 请在对方手机上也点按“连接手机”。"),
        "link_pick" to m("연결할 폰을 고르세요", "接続するスマホを選んでください", "Choose a phone to link", "Choisissez un téléphone", "Elige un teléfono", "Handy auswählen", "选择要连接的手机"),
        "link_confirm" to m("두 폰에 같은 숫자가 보이면 연결하세요.\n\n%s", "両方のスマホに同じ数字が表示されたら接続してください。\n\n%s", "Link if both phones show the same code.\n\n%s", "Reliez si les deux affichent le même code.\n\n%s", "Conecta si ambos muestran el mismo código.\n\n%s", "Verbinden, wenn beide denselben Code zeigen.\n\n%s", "两部手机显示相同数字时再连接。\n\n%s"),
        "link_accept" to m("연결", "接続", "Link", "Relier", "Conectar", "Verbinden", "连接"),
        "cancel" to m("취소", "キャンセル", "Cancel", "Annuler", "Cancelar", "Abbrechen", "取消"),
        "link_ok" to m("연결됨: %s", "接続済み: %s", "Linked: %s", "Relié : %s", "Conectado: %s", "Verbunden: %s", "已连接：%s"),
        "link_lost" to m("연결이 끊겼어요", "接続が切れました", "Link lost", "Connexion perdue", "Conexión perdida", "Verbindung getrennt", "连接已断开"),
        "link_fail" to m("연결하지 못했어요. 두 폰 모두 블루투스와 Wi-Fi를 켜 주세요.", "接続できませんでした。両方のBluetoothとWi-Fiをオンにしてください。", "Couldn't link. Turn on Bluetooth and Wi-Fi on both phones.", "Échec. Activez Bluetooth et Wi-Fi sur les deux.", "No se pudo conectar. Activa Bluetooth y Wi-Fi en ambos.", "Verbindung fehlgeschlagen. Bluetooth und WLAN auf beiden einschalten.", "连接失败。请在两部手机上打开蓝牙和 Wi-Fi。"),
        "link_perm" to m("주변 기기 권한이 있어야 폰을 연결할 수 있어요.", "スマホ接続には付近のデバイスの権限が必要です。", "Nearby devices permission is needed to link phones.", "L'autorisation Appareils à proximité est nécessaire.", "Se necesita el permiso de dispositivos cercanos.", "Die Berechtigung „Geräte in der Nähe“ wird benötigt.", "连接手机需要“附近设备”权限。"),
        "history" to m("🕘 지난 대화", "🕘 過去の会話", "🕘 Past talks", "🕘 Conversations passées", "🕘 Conversaciones", "🕘 Frühere Gespräche", "🕘 历史对话"),
        "review_empty" to m("아직 저장된 대화가 없어요.", "保存された会話はまだありません。", "No saved conversations yet.", "Aucune conversation enregistrée.", "Aún no hay conversaciones.", "Noch keine Gespräche gespeichert.", "还没有保存的对话。"),
        "save_all" to m("전부 단어장에 저장", "すべて単語帳に保存", "Save all to vocabulary", "Tout enregistrer", "Guardar todo", "Alles speichern", "全部存入单词本"),
        "saved_n" to m("%d개를 단어장에 저장했어요. 단어장 탭에서 퀴즈로 복습하세요.", "%d件を単語帳に保存しました。単語帳タブのクイズで復習できます。", "Saved %d to your vocabulary. Review them with the quiz in the Vocabulary tab.", "%d éléments enregistrés. Révisez-les avec le quiz.", "Se guardaron %d. Repásalos con el cuestionario.", "%d gespeichert. Mit dem Quiz im Vokabel-Tab wiederholen.", "已保存 %d 条。可在单词本中用测验复习。"),
        "saved_one" to m("단어장에 저장했어요", "単語帳に保存しました", "Saved to vocabulary", "Enregistré", "Guardado", "Gespeichert", "已保存"),
        "close" to m("닫기", "閉じる", "Close", "Fermer", "Cerrar", "Schließen", "关闭"),
        "delete" to m("삭제", "削除", "Delete", "Supprimer", "Eliminar", "Löschen", "删除"),
        "fast" to m("빠른 번역", "速訳", "Quick", "Rapide", "Rápida", "Schnell", "快速翻译"),
        "refining" to m("정밀 번역 중…", "精密翻訳中…", "Refining…", "Affinage…", "Refinando…", "Wird verfeinert…", "正在精译…"),
        "me" to m("나", "自分", "Me", "Moi", "Yo", "Ich", "我"),
        "them" to m("상대", "相手", "Them", "L'autre", "La otra persona", "Gegenüber", "对方"),
        "remote_hint" to m("상대 폰에서 말하면 여기에 번역돼요", "相手のスマホで話すとここに訳が出ます", "What they say on their phone appears here", "Ce qu'il dit sur son téléphone s'affiche ici", "Lo que diga en su teléfono aparecerá aquí", "Was sie am eigenen Handy sagen, erscheint hier", "对方在其手机上说的话会翻译显示在这里"),
        "mic_perm" to m("마이크 권한이 필요해요", "マイクの権限が必要です", "Microphone permission is needed", "Autorisation du micro nécessaire", "Se necesita permiso del micrófono", "Mikrofonberechtigung nötig", "需要麦克风权限"),
        "new_talk" to m("✨ 새 대화", "✨ 新しい会話", "✨ New conversation", "✨ Nouvelle conversation", "✨ Nueva conversación", "✨ Neues Gespräch", "✨ 新对话"),
        "to_vocab" to m("단어장에 저장", "単語帳に保存", "Save to vocabulary", "Enregistrer", "Guardar", "Speichern", "存入单词本"),
        "auto_on" to m("🎙 자동 켬", "🎙 自動 オン", "🎙 Auto on", "🎙 Auto activé", "🎙 Auto activado", "🎙 Auto an", "🎙 自动 开"),
        "auto_off" to m("🎙 자동 끔", "🎙 自動 オフ", "🎙 Auto off", "🎙 Auto désactivé", "🎙 Auto desactivado", "🎙 Auto aus", "🎙 自动 关"),
        "auto_listening" to m("🎧 자동으로 듣는 중 · 가까이서 말하면 알아서 번역해요", "🎧 自動で聞き取り中 · 近くで話すと自動で訳します", "🎧 Listening automatically · speak nearby and it translates", "🎧 Écoute automatique · parlez près du téléphone", "🎧 Escucha automática · habla cerca del teléfono", "🎧 Hört automatisch zu · nah am Handy sprechen", "🎧 自动聆听中 · 靠近说话即可自动翻译"),
        "auto_help" to m("자동 모드에서는 버튼 없이 계속 들어요. 가까이서 말한 목소리만 인식하고, 언어로 누가 말했는지 구분해요. 앱이 읽는 동안에는 잠시 멈춰요.\n\n시끄러운 곳에서 엉뚱하게 반응하면 버튼을 길게 눌러 감도를 낮추세요.", "自動モードではボタンなしで聞き続けます。近くの声だけを認識し、言語で話し手を判断します。アプリが読み上げる間は止まります。\n\n騒がしい場所で誤反応するときはボタンを長押しして感度を下げてください。", "Auto mode keeps listening with no buttons. It only reacts to nearby voices and tells who spoke by the language. It pauses while the app speaks.\n\nIf it reacts to noise, long-press the button to lower the sensitivity.", "Le mode auto écoute sans bouton, réagit aux voix proches et reconnaît qui parle par la langue. Il s'arrête pendant la lecture.\n\nS'il réagit au bruit, appui long pour baisser la sensibilité.", "El modo auto escucha sin botones, solo reacciona a voces cercanas y distingue quién habla por el idioma. Se pausa mientras la app habla.\n\nSi reacciona al ruido, mantén pulsado para bajar la sensibilidad.", "Der Auto-Modus hört ohne Tasten zu, reagiert nur auf nahe Stimmen und erkennt an der Sprache, wer spricht. Er pausiert, während die App spricht.\n\nBei Fehlreaktionen lange drücken und Empfindlichkeit senken.", "自动模式无需按键持续聆听，只识别近处的声音，并根据语言判断说话人。应用朗读时会暂停。\n\n若在嘈杂处误触发，长按按钮降低灵敏度。"),
        "sens_title" to m("자동 감도", "自動感度", "Auto sensitivity", "Sensibilité auto", "Sensibilidad auto", "Auto-Empfindlichkeit", "自动灵敏度"),
        "sens_help" to m("높을수록 작은(먼) 목소리에도 반응하고, 낮을수록 가까이서 크게 말해야 반응해요.", "高いほど小さい（遠い）声にも反応し、低いほど近くで大きく話す必要があります。", "Higher reacts to quieter, farther voices; lower needs you to speak close and clearly.", "Plus haut : réagit aux voix lointaines ; plus bas : parlez près et fort.", "Más alto reacciona a voces lejanas; más bajo exige hablar cerca.", "Höher reagiert auf leisere Stimmen; niedriger erfordert nahes Sprechen.", "越高越能识别小声（远处）的声音，越低需要靠近大声说话。"),
        "sens_value" to m("감도 %d / 10", "感度 %d / 10", "Sensitivity %d / 10", "Sensibilité %d / 10", "Sensibilidad %d / 10", "Empfindlichkeit %d / 10", "灵敏度 %d / 10"),
        "q1" to m("네, 좋아요", "はい、お願いします", "Yes, please", "Oui, s'il vous plaît", "Sí, por favor", "Ja, bitte", "好的，麻烦了"),
        "q2" to m("아니요, 괜찮아요", "いいえ、大丈夫です", "No, thank you", "Non, merci", "No, gracias", "Nein, danke", "不用了，谢谢"),
        "q3" to m("다시 한번 말씀해 주세요", "もう一度言ってください", "Could you say that again?", "Pouvez-vous répéter ?", "¿Puede repetirlo?", "Können Sie das wiederholen?", "请再说一遍"),
        "q4" to m("천천히 말씀해 주세요", "ゆっくり話してください", "Please speak slowly", "Parlez lentement, s'il vous plaît", "Hable despacio, por favor", "Bitte langsam sprechen", "请说慢一点"),
        "q5" to m("감사합니다", "ありがとうございます", "Thank you", "Merci", "Gracias", "Danke", "谢谢")
    )
}
