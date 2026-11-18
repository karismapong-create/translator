package com.example.interpreter

// Screen text in every supported language. Order in m(): ko, ja, en, fr, es, de, zh.
object I18n {

    var lang = "ko"
        private set

    fun use(code: String) { lang = code }

    fun t(key: String): String = table[key]?.let { it[lang] ?: it["en"] ?: it["ko"] } ?: key

    fun f(key: String, vararg args: Any): String = String.format(t(key), *args)

    private fun m(ko: String, ja: String, en: String, fr: String, es: String, de: String, zh: String) =
        mapOf("ko" to ko, "ja" to ja, "en" to en, "fr" to fr, "es" to es, "de" to de, "zh" to zh)

    private val table: Map<String, Map<String, String>> = mapOf(
        // Tabs and main screen
        "tab_interp" to m("통역", "通訳", "Interpret", "Interpréter", "Interpretar", "Dolmetschen", "口译"),
        "tab_talk" to m("회화", "会話", "Talk", "Conversation", "Conversación", "Gespräch", "会话"),
        "tab_vocab" to m("단어장", "単語帳", "Words", "Mots", "Palabras", "Wörter", "单词本"),
        "tab_gloss" to m("용어집", "用語集", "Glossary", "Glossaire", "Glosario", "Glossar", "术语表"),
        "auto" to m("🔍 자동 감지", "🔍 自動検出", "🔍 Auto detect", "🔍 Détection auto", "🔍 Detección automática", "🔍 Automatisch", "🔍 自动检测"),
        "hint" to m(
            "시작을 누르고 말하세요. 🔍 자동 감지는 내 언어와 오른쪽 언어를 알아서 구분해요.\n☆ 문장 저장 · 🔊 다시 듣기 · 글자를 길게 눌러 '단어 저장'",
            "開始を押して話してください。🔍 自動検出は自分の言語と右側の言語を自動で区別します。\n☆ 文を保存 · 🔊 もう一度聞く · 文字を長押しで「単語を保存」",
            "Tap Start and speak. 🔍 Auto detect tells your language and the right-hand language apart.\n☆ save sentence · 🔊 listen again · long-press text to save a word",
            "Appuyez sur Démarrer et parlez. 🔍 La détection auto distingue votre langue et celle de droite.\n☆ enregistrer la phrase · 🔊 réécouter · appui long pour enregistrer un mot",
            "Pulsa Iniciar y habla. 🔍 La detección automática distingue tu idioma y el de la derecha.\n☆ guardar frase · 🔊 volver a escuchar · mantén pulsado para guardar una palabra",
            "Tippe auf Start und sprich. 🔍 Automatisch unterscheidet deine Sprache und die rechte Sprache.\n☆ Satz speichern · 🔊 nochmal hören · lange drücken, um ein Wort zu speichern",
            "点击开始后说话。🔍 自动检测会区分你的语言和右侧的语言。\n☆ 保存句子 · 🔊 再听一遍 · 长按文字可“保存单词”"
        ),
        "chat_hint" to m("입력하면 내 언어↔외국어로 번역해요", "入力すると自分の言語↔外国語に翻訳します", "Type to translate between your language and another", "Tapez pour traduire entre votre langue et une autre", "Escribe para traducir entre tu idioma y otro", "Tippe, um zwischen deiner und einer anderen Sprache zu übersetzen", "输入后可在你的语言和外语之间翻译"),
        "translate" to m("번역", "翻訳", "Translate", "Traduire", "Traducir", "Übersetzen", "翻译"),
        "speak_aloud" to m("음성으로 읽기", "読み上げ", "Read aloud", "Lire à voix haute", "Leer en voz alta", "Vorlesen", "朗读"),
        "settings" to m("설정", "設定", "Settings", "Réglages", "Ajustes", "Einstellungen", "设置"),
        "start" to m("시작", "開始", "Start", "Démarrer", "Iniciar", "Start", "开始"),
        "stop" to m("정지", "停止", "Stop", "Arrêter", "Detener", "Stopp", "停止"),

        // Status lines
        "engine_status" to m("번역 엔진: %s", "翻訳エンジン: %s", "Engine: %s", "Moteur : %s", "Motor: %s", "Engine: %s", "翻译引擎：%s"),
        "engine_device" to m("기기 번역만", "端末内翻訳のみ", "On-device only", "Sur l'appareil uniquement", "Solo en el dispositivo", "Nur auf dem Gerät", "仅设备翻译"),
        "listening" to m("듣는 중 · %1\$s → %2\$s · %3\$s", "聞き取り中 · %1\$s → %2\$s · %3\$s", "Listening · %1\$s → %2\$s · %3\$s", "Écoute · %1\$s → %2\$s · %3\$s", "Escuchando · %1\$s → %2\$s · %3\$s", "Hört zu · %1\$s → %2\$s · %3\$s", "正在听 · %1\$s → %2\$s · %3\$s"),
        "auto_status" to m("듣는 중 · 자동 감지 (%1\$s ↔ %2\$s) · %3\$s", "聞き取り中 · 自動検出 (%1\$s ↔ %2\$s) · %3\$s", "Listening · Auto (%1\$s ↔ %2\$s) · %3\$s", "Écoute · Auto (%1\$s ↔ %2\$s) · %3\$s", "Escuchando · Auto (%1\$s ↔ %2\$s) · %3\$s", "Hört zu · Automatisch (%1\$s ↔ %2\$s) · %3\$s", "正在听 · 自动检测 (%1\$s ↔ %2\$s) · %3\$s"),
        "preparing" to m("언어팩 준비 중… 처음 한 번은 인터넷이 필요해요.", "言語パックを準備中… 初回のみインターネットが必要です。", "Preparing language pack… Internet is needed the first time.", "Préparation du pack de langue… Internet requis la première fois.", "Preparando el paquete de idioma… Se necesita internet la primera vez.", "Sprachpaket wird vorbereitet… Beim ersten Mal ist Internet nötig.", "正在准备语言包… 首次需要联网。"),
        "pack_failed" to m("언어팩을 받지 못했어요. 인터넷 연결을 확인하세요.", "言語パックを取得できませんでした。接続を確認してください。", "Couldn't download the language pack. Check your connection.", "Impossible de télécharger le pack de langue. Vérifiez la connexion.", "No se pudo descargar el paquete de idioma. Revisa la conexión.", "Sprachpaket konnte nicht geladen werden. Prüfe die Verbindung.", "无法下载语言包，请检查网络。"),
        "no_recognizer" to m("이 폰에서는 음성 인식을 쓸 수 없어요. Google 앱을 확인하세요.", "この端末では音声認識が使えません。Googleアプリを確認してください。", "Speech recognition isn't available. Check the Google app.", "Reconnaissance vocale indisponible. Vérifiez l'appli Google.", "El reconocimiento de voz no está disponible. Revisa la app de Google.", "Spracherkennung nicht verfügbar. Prüfe die Google-App.", "无法使用语音识别，请检查 Google 应用。"),
        "same_lang" to m("서로 다른 두 언어를 고르세요.", "異なる2つの言語を選んでください。", "Choose two different languages.", "Choisissez deux langues différentes.", "Elige dos idiomas distintos.", "Wähle zwei verschiedene Sprachen.", "请选择两种不同的语言。"),
        "pick_foreign" to m("위쪽에서 번역할 외국어를 고르세요.", "上で翻訳する外国語を選んでください。", "Choose a foreign language at the top.", "Choisissez une langue étrangère en haut.", "Elige un idioma extranjero arriba.", "Wähle oben eine Fremdsprache.", "请在上方选择要翻译的外语。"),
        "mic_perm" to m("마이크 권한을 허용해야 통역할 수 있어요.", "通訳にはマイクの許可が必要です。", "Allow microphone access to interpret.", "Autorisez le micro pour interpréter.", "Permite el micrófono para interpretar.", "Erlaube das Mikrofon zum Dolmetschen.", "需要麦克风权限才能口译。"),
        "no_swap" to m("자동 감지 중에는 바꿀 필요가 없어요", "自動検出中は入れ替える必要はありません", "No need to swap in auto detect", "Pas besoin d'inverser en mode auto", "No hace falta cambiar en modo automático", "Im Automatikmodus nicht nötig", "自动检测时无需交换"),
        "failed" to m("번역 실패", "翻訳失敗", "Translation failed", "Échec de la traduction", "Error de traducción", "Übersetzung fehlgeschlagen", "翻译失败"),
        "pack_error" to m("(언어팩 오류 · 다시 받는 중)", "(言語パックエラー · 再取得中)", "(language pack error · re-downloading)", "(erreur du pack · nouveau téléchargement)", "(error del paquete · descargando de nuevo)", "(Sprachpaketfehler · wird neu geladen)", "（语言包错误 · 正在重新下载）"),
        "repairing" to m("번역 결과가 이상해서 언어팩을 다시 받는 중이에요…", "翻訳結果がおかしいため、言語パックを再取得しています…", "Results looked wrong, re-downloading language packs…", "Résultats incorrects, nouveau téléchargement des packs…", "Resultados extraños, descargando de nuevo los paquetes…", "Ergebnisse fehlerhaft, Sprachpakete werden neu geladen…", "翻译结果异常，正在重新下载语言包…"),
        "repaired" to m("언어팩을 정리했어요. 다시 입력하면 새로 받아요.", "言語パックを整理しました。もう一度入力すると再取得します。", "Language packs reset. They'll download again next time.", "Packs réinitialisés. Ils seront retéléchargés.", "Paquetes reiniciados. Se descargarán de nuevo.", "Sprachpakete zurückgesetzt. Sie werden neu geladen.", "已重置语言包，下次会重新下载。"),

        // Settings menu
        "m_camera" to m("📷 카메라 번역", "📷 カメラ翻訳", "📷 Camera translation", "📷 Traduction caméra", "📷 Traducción con cámara", "📷 Kamera-Übersetzung", "📷 相机翻译"),
        "m_mic" to m("🎙 마이크 진단", "🎙 マイク診断", "🎙 Microphone test", "🎙 Test du micro", "🎙 Prueba de micrófono", "🎙 Mikrofontest", "🎙 麦克风诊断"),
        "m_clear" to m("화면 지우기", "画面を消去", "Clear screen", "Effacer l'écran", "Borrar pantalla", "Bildschirm leeren", "清空屏幕"),
        "m_export" to m("화면 내용 내보내기", "画面の内容を共有", "Share screen text", "Partager le contenu", "Compartir contenido", "Inhalt teilen", "分享屏幕内容"),
        "m_engine" to m("번역 엔진 (지금: %s)", "翻訳エンジン (現在: %s)", "Translation engine (now: %s)", "Moteur de traduction (actuel : %s)", "Motor de traducción (actual: %s)", "Übersetzungs-Engine (aktuell: %s)", "翻译引擎（当前：%s）"),
        "m_privacy" to m("개인정보처리방침", "プライバシーポリシー", "Privacy policy", "Politique de confidentialité", "Política de privacidad", "Datenschutzerklärung", "隐私政策"),
        "m_licenses" to m("오픈소스 라이선스", "オープンソースライセンス", "Open source licenses", "Licences open source", "Licencias de código abierto", "Open-Source-Lizenzen", "开源许可"),
        "m_silent" to m("무음 모드 (지금: %s)", "サイレントモード (現在: %s)", "Silent mode (now: %s)", "Mode silencieux (actuel : %s)", "Modo silencio (actual: %s)", "Stummmodus (aktuell: %s)", "静音模式（当前：%s）"),
        "m_country" to m("🌐 내 나라·언어 (%s)", "🌐 国・言語 (%s)", "🌐 Country & language (%s)", "🌐 Pays et langue (%s)", "🌐 País e idioma (%s)", "🌐 Land & Sprache (%s)", "🌐 国家与语言（%s）"),
        "on" to m("켜짐", "オン", "On", "Activé", "Activado", "An", "开"),
        "off" to m("꺼짐", "オフ", "Off", "Désactivé", "Desactivado", "Aus", "关"),
        "silent_on" to m("무음 모드를 켰어요", "サイレントモードをオンにしました", "Silent mode on", "Mode silencieux activé", "Modo silencio activado", "Stummmodus an", "已开启静音模式"),
        "silent_off" to m("무음 모드를 껐어요", "サイレントモードをオフにしました", "Silent mode off", "Mode silencieux désactivé", "Modo silencio desactivado", "Stummmodus aus", "已关闭静音模式"),
        "nothing_export" to m("화면에 번역된 내용이 없어요.", "翻訳された内容がありません。", "Nothing to share yet.", "Rien à partager.", "Nada que compartir.", "Noch nichts zu teilen.", "暂无可分享的内容。"),
        "export_title" to m("내보내기", "共有", "Share", "Partager", "Compartir", "Teilen", "分享"),

        // Saving words
        "save_word" to m("단어 저장", "単語を保存", "Save word", "Enregistrer le mot", "Guardar palabra", "Wort speichern", "保存单词"),
        "saved_word" to m("단어장에 저장: %s", "単語帳に保存: %s", "Saved: %s", "Enregistré : %s", "Guardado: %s", "Gespeichert: %s", "已保存：%s"),
        "word_failed" to m("단어 번역에 실패했어요.", "単語の翻訳に失敗しました。", "Couldn't translate the word.", "Impossible de traduire le mot.", "No se pudo traducir la palabra.", "Wort konnte nicht übersetzt werden.", "单词翻译失败。"),

        // Engine messages
        "using" to m("%s 사용", "%s を使用", "Using %s", "Utilisation de %s", "Usando %s", "%s wird verwendet", "正在使用 %s"),
        "err_key" to m("%s 키가 올바르지 않아요. 지금은 기기 번역을 써요.", "%s のキーが正しくありません。端末内翻訳を使います。", "%s key is invalid. Using on-device translation.", "Clé %s invalide. Traduction sur l'appareil.", "La clave de %s no es válida. Uso el dispositivo.", "%s-Schlüssel ungültig. Übersetzung auf dem Gerät.", "%s 密钥无效，改用设备翻译。"),
        "err_quota" to m("DeepL 무료 한도를 다 썼어요. 기기 번역으로 전환했어요.", "DeepLの無料枠を使い切りました。端末内翻訳に切り替えました。", "DeepL free quota used up. Switched to on-device.", "Quota gratuit DeepL épuisé. Traduction sur l'appareil.", "Cuota gratuita de DeepL agotada. Uso el dispositivo.", "DeepL-Gratiskontingent aufgebraucht. Gerät wird verwendet.", "DeepL 免费额度已用完，已切换为设备翻译。"),
        "err_config" to m("%s 설정(주소나 모델)을 확인하세요. 지금은 기기 번역을 써요.", "%s の設定(アドレスかモデル)を確認してください。端末内翻訳を使います。", "Check %s settings (address or model). Using on-device.", "Vérifiez les réglages de %s (adresse ou modèle).", "Revisa la configuración de %s (dirección o modelo).", "Prüfe die %s-Einstellungen (Adresse oder Modell).", "请检查 %s 设置（地址或模型），暂用设备翻译。"),
        "err_rate" to m("%s 사용 한도에 걸려 잠시 기기 번역을 써요.", "%s の利用上限のため、しばらく端末内翻訳を使います。", "%s rate limit hit. Using on-device for a moment.", "Limite %s atteinte. Traduction locale un instant.", "Límite de %s alcanzado. Uso el dispositivo un momento.", "%s-Limit erreicht. Kurz Übersetzung auf dem Gerät.", "%s 达到使用上限，暂时使用设备翻译。"),
        "err_net" to m("%s에 연결하지 못해 잠시 기기 번역을 써요.", "%s に接続できないため、しばらく端末内翻訳を使います。", "Couldn't reach %s. Using on-device for a moment.", "Impossible de joindre %s. Traduction locale un instant.", "No se pudo conectar con %s. Uso el dispositivo un momento.", "%s nicht erreichbar. Kurz Übersetzung auf dem Gerät.", "无法连接 %s，暂时使用设备翻译。"),
        "err_timeout" to m("응답 시간 초과. 모델이 너무 크거나 주소가 틀렸을 수 있어요.", "応答タイムアウト。モデルが大きすぎるかアドレスが違う可能性があります。", "Timed out. The model may be too large or the address wrong.", "Délai dépassé. Modèle trop gros ou adresse incorrecte.", "Tiempo agotado. El modelo es muy grande o la dirección es incorrecta.", "Zeitüberschreitung. Modell zu groß oder Adresse falsch.", "响应超时。模型可能太大或地址错误。"),
        "err_connect" to m("연결 실패. 주소, 인터넷 연결, (맥북이라면) 같은 와이파이인지 확인하세요.", "接続失敗。アドレス、ネット接続、(Macなら)同じWi-Fiか確認してください。", "Connection failed. Check the address, internet, and (for a Mac) the same Wi-Fi.", "Échec de connexion. Vérifiez l'adresse, Internet et (pour un Mac) le même Wi-Fi.", "Error de conexión. Revisa la dirección, internet y (si es un Mac) la misma Wi-Fi.", "Verbindung fehlgeschlagen. Prüfe Adresse, Internet und (beim Mac) dasselbe WLAN.", "连接失败。请检查地址、网络，以及（Mac）是否同一 Wi-Fi。"),

        // Engine settings dialog
        "e_title" to m("번역 엔진", "翻訳エンジン", "Translation engine", "Moteur de traduction", "Motor de traducción", "Übersetzungs-Engine", "翻译引擎"),
        "e_need" to m("(설정 필요)", "(設定が必要)", "(setup needed)", "(à configurer)", "(requiere configuración)", "(Einrichtung nötig)", "（需要设置）"),
        "e_save" to m("저장하고 사용", "保存して使用", "Save & use", "Enregistrer et utiliser", "Guardar y usar", "Speichern & nutzen", "保存并使用"),
        "e_test" to m("연결 테스트", "接続テスト", "Test", "Tester", "Probar", "Testen", "连接测试"),
        "cancel" to m("취소", "キャンセル", "Cancel", "Annuler", "Cancelar", "Abbrechen", "取消"),
        "close" to m("닫기", "閉じる", "Close", "Fermer", "Cerrar", "Schließen", "关闭"),
        "e_getkey" to m("🔑 키 받으러 가기 (사이트 열기)", "🔑 キーを取得 (サイトを開く)", "🔑 Get a key (open site)", "🔑 Obtenir une clé (ouvrir le site)", "🔑 Obtener clave (abrir sitio)", "🔑 Schlüssel holen (Seite öffnen)", "🔑 获取密钥（打开网站）"),
        "e_ollama" to m("⬇ Ollama 내려받기 (사이트 열기)", "⬇ Ollamaをダウンロード (サイトを開く)", "⬇ Download Ollama (open site)", "⬇ Télécharger Ollama (ouvrir le site)", "⬇ Descargar Ollama (abrir sitio)", "⬇ Ollama herunterladen (Seite öffnen)", "⬇ 下载 Ollama（打开网站）"),
        "e_paste" to m("📋 복사한 키 붙여넣기", "📋 コピーしたキーを貼り付け", "📋 Paste copied key", "📋 Coller la clé copiée", "📋 Pegar clave copiada", "📋 Kopierten Schlüssel einfügen", "📋 粘贴已复制的密钥"),
        "e_models" to m("📋 모델 목록 불러오기", "📋 モデル一覧を取得", "📋 Load model list", "📋 Charger la liste des modèles", "📋 Cargar lista de modelos", "📋 Modellliste laden", "📋 加载模型列表"),
        "e_address" to m("주소", "アドレス", "Address", "Adresse", "Dirección", "Adresse", "地址"),
        "e_key" to m("API 키", "APIキー", "API key", "Clé API", "Clave API", "API-Schlüssel", "API 密钥"),
        "e_key_opt" to m("API 키 (없으면 비워두기)", "APIキー (なければ空欄)", "API key (leave empty if none)", "Clé API (vide si aucune)", "Clave API (vacía si no hay)", "API-Schlüssel (leer lassen, falls keiner)", "API 密钥（没有可留空）"),
        "e_model" to m("모델", "モデル", "Model", "Modèle", "Modelo", "Modell", "模型"),
        "e_model_hint" to m("목록에서 고르거나 직접 입력", "一覧から選ぶか直接入力", "Pick from the list or type", "Choisir dans la liste ou saisir", "Elige de la lista o escribe", "Aus der Liste wählen oder eingeben", "从列表选择或直接输入"),
        "e_testing" to m("테스트 중…", "テスト中…", "Testing…", "Test en cours…", "Probando…", "Wird getestet…", "测试中…"),
        "e_ok" to m("성공 ✔\n%s", "成功 ✔\n%s", "Success ✔\n%s", "Réussi ✔\n%s", "Éxito ✔\n%s", "Erfolgreich ✔\n%s", "成功 ✔\n%s"),
        "e_fail" to m("실패: %s", "失敗: %s", "Failed: %s", "Échec : %s", "Error: %s", "Fehlgeschlagen: %s", "失败：%s"),
        "e_loading" to m("모델 목록을 불러오는 중…", "モデル一覧を取得中…", "Loading models…", "Chargement des modèles…", "Cargando modelos…", "Modelle werden geladen…", "正在加载模型…"),
        "g_free" to m("무료 · 추천", "無料 · おすすめ", "Free · recommended", "Gratuit · recommandé", "Gratis · recomendado", "Kostenlos · empfohlen", "免费 · 推荐"),
        "g_lite" to m("무료 · 가벼움 (품질 낮음)", "無料 · 軽量 (品質低め)", "Free · light (lower quality)", "Gratuit · léger (qualité moindre)", "Gratis · ligero (menor calidad)", "Kostenlos · leicht (geringere Qualität)", "免费 · 轻量（质量较低）"),
        "g_paid" to m("유료 · 결제 필요", "有料 · 請求設定が必要", "Paid · billing required", "Payant · facturation requise", "De pago · requiere facturación", "Kostenpflichtig · Abrechnung nötig", "付费 · 需开通结算"),
        "e_pick" to m("모델 선택", "モデルを選択", "Choose a model", "Choisir un modèle", "Elige un modelo", "Modell wählen", "选择模型"),
        "e_found" to m("모델 %d개를 찾았어요. 하나를 고르세요.", "%d個のモデルが見つかりました。選んでください。", "Found %d models. Pick one.", "%d modèles trouvés. Choisissez-en un.", "Se encontraron %d modelos. Elige uno.", "%d Modelle gefunden. Wähle eines.", "找到 %d 个模型，请选择一个。"),
        "e_selected" to m("선택한 모델: %s\n'연결 테스트'로 확인한 뒤 저장하세요.", "選択したモデル: %s\n接続テストで確認してから保存してください。", "Selected: %s\nTest it, then save.", "Sélectionné : %s\nTestez puis enregistrez.", "Seleccionado: %s\nPruébalo y guarda.", "Ausgewählt: %s\nTesten, dann speichern.", "已选择：%s\n请测试后保存。"),
        "e_models_fail" to m("목록을 불러오지 못했어요. 키와 주소를 확인하세요.", "一覧を取得できませんでした。キーとアドレスを確認してください。", "Couldn't load the list. Check the key and address.", "Impossible de charger la liste. Vérifiez la clé et l'adresse.", "No se pudo cargar la lista. Revisa la clave y la dirección.", "Liste konnte nicht geladen werden. Prüfe Schlüssel und Adresse.", "无法加载列表，请检查密钥和地址。"),
        "paste_empty" to m("복사된 내용이 없어요.", "コピーされた内容がありません。", "Nothing copied yet.", "Rien n'a été copié.", "No hay nada copiado.", "Nichts kopiert.", "没有复制的内容。"),
        "browser_fail" to m("브라우저를 열지 못했어요. 주소를 길게 눌러 복사하세요.", "ブラウザを開けませんでした。アドレスを長押ししてコピーしてください。", "Couldn't open the browser. Long-press the address to copy it.", "Impossible d'ouvrir le navigateur. Appui long pour copier l'adresse.", "No se pudo abrir el navegador. Mantén pulsada la dirección para copiarla.", "Browser konnte nicht geöffnet werden. Adresse lange drücken zum Kopieren.", "无法打开浏览器，请长按地址复制。"),
        "e_generic_help" to m(
            "아래 버튼으로 사이트를 열어 키를 만든 뒤, 복사해서 붙여넣으세요.",
            "下のボタンでサイトを開いてキーを作成し、コピーして貼り付けてください。",
            "Open the site with the button below, create a key, then copy and paste it here.",
            "Ouvrez le site avec le bouton ci-dessous, créez une clé, puis collez-la ici.",
            "Abre el sitio con el botón de abajo, crea una clave y pégala aquí.",
            "Öffne die Seite mit dem Button unten, erstelle einen Schlüssel und füge ihn hier ein.",
            "用下方按钮打开网站创建密钥，然后复制粘贴到这里。"
        ),
        "deepl_help" to m(
            "① 아래 버튼으로 DeepL API 페이지를 열고 'DeepL API Free'로 가입하세요.\n② 계정 메뉴의 'API 키'에서 키를 복사하세요. 무료 키는 :fx로 끝나요.\n③ 아래에 붙여넣으세요.",
            "① 下のボタンでDeepL APIのページを開き「DeepL API Free」に登録します。\n② アカウントの「APIキー」からキーをコピーします。無料キーは :fx で終わります。\n③ 下に貼り付けてください。",
            "① Open the DeepL API page below and sign up for “DeepL API Free”.\n② Copy your key from the account's API keys. Free keys end with :fx.\n③ Paste it below.",
            "① Ouvrez la page DeepL API ci-dessous et inscrivez-vous à « DeepL API Free ».\n② Copiez la clé depuis votre compte. Les clés gratuites finissent par :fx.\n③ Collez-la ci-dessous.",
            "① Abre la página de DeepL API y regístrate en «DeepL API Free».\n② Copia la clave desde tu cuenta. Las claves gratuitas terminan en :fx.\n③ Pégala abajo.",
            "① Öffne unten die DeepL-API-Seite und registriere dich für „DeepL API Free“.\n② Kopiere den Schlüssel aus deinem Konto. Kostenlose Schlüssel enden auf :fx.\n③ Unten einfügen.",
            "① 用下方按钮打开 DeepL API 页面并注册“DeepL API Free”。\n② 在账户中复制 API 密钥。免费密钥以 :fx 结尾。\n③ 粘贴到下方。"
        )
    )
}