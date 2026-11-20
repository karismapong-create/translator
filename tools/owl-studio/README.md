# Owl Studio — 맥북 로컬 Qwen으로 책 영상 그림 그리기

부엉이 통역의 **영상 만들기** 그림을 맥북에서 그려 주는 작은 서버입니다.

- Ollama의 로컬 Qwen(텍스트)이 책의 인물 설정표와 문장별 장면 묘사를 씁니다.
- mflux로 첫 그림은 **Qwen-Image**, 다음 그림부터는 **Qwen-Image-Edit**가 앞 그림을 참고해서 그려 인물과 화풍이 이어집니다.
- 앱은 작업을 보낸 뒤 그림이 한 장씩 완성될 때마다 바로 받아 보여 줍니다. 폰을 꺼도 맥북은 계속 그리고, 다시 누르면 이어서 받습니다.
- 낭독 녹음과 MP4 합치기는 폰이 합니다(단어별 자막 타이밍 때문에).

## 필요한 것

- Apple Silicon 맥(M1 이상). 메모리 **32GB 이상**(64GB 이상이면 더 좋은 6비트 모델과 1024×768).
- Ollama (https://ollama.com).
- 처음 실행할 때 모델을 수십 GB 내려받습니다.

## 설치

```bash
cd translator/tools/owl-studio
bash install.sh --test
```

- mflux 설치, 메모리에 맞는 모델 선택, `config.json` 생성(토큰 포함), 로그인할 때 자동 실행(launchd)까지 합니다.
- `--test`는 그림 두 장(새로 그리기 + 이어 그리기)을 시험으로 그립니다. 첫 실행은 모델 다운로드 때문에 오래 걸립니다.
- 마지막에 **주소 후보(IP:8765)와 토큰**이 나옵니다. WireGuard 주소를 고르세요.

## 앱 설정

설정 → 🔑 그림 그리기 키 → **맥북 (로컬 Qwen)**

1. 주소(예: `10.0.0.2:8765`)와 토큰을 넣고 **연결 확인**을 누릅니다.
2. 그림 엔진에서 **맥북 로컬 Qwen**을 고르고 저장합니다.
3. 책 파트 → 🎬 → **↻ 영상 만들기**.

## 설정 바꾸기 (`config.json`)

| 항목 | 뜻 |
|---|---|
| `llm` | Ollama 모델 이름 (예: `qwen3:8b`, `qwen3:14b`) |
| `gen_model`, `edit_model` | mflux 모델 (Hugging Face 이름 또는 폴더) |
| `width`, `height` | 그림 크기 |
| `steps` | 단계 수 (낮추면 빠르고 거칠어짐) |
| `gen_cmd`, `edit_cmd` | mflux 명령어 틀 (버전이 바뀌어 옵션이 다르면 여기서 고칩니다) |

바꾼 뒤 다시 시작:

```bash
launchctl unload ~/Library/LaunchAgents/com.owlinterpreter.studio.plist
launchctl load ~/Library/LaunchAgents/com.owlinterpreter.studio.plist
```

로그: `~/owl-studio/studio.log` · 작업과 그림: `~/owl-studio/jobs/`
