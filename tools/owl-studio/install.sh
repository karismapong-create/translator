#!/bin/bash
# Owl Studio installer for an Apple Silicon Mac.
#   bash install.sh          install, pick models for this Mac's memory, start at login
#   bash install.sh --test   also draw two test pictures (downloads the model the first time)
set -e
cd "$(dirname "$0")"
HERE="$(pwd)"

if [ "$(uname -m)" != "arm64" ]; then
  echo "Owl Studio needs an Apple Silicon Mac (M1 or later)."; exit 1
fi

MEM_GB=$(( $(sysctl -n hw.memsize) / 1073741824 ))
echo "Memory: ${MEM_GB} GB"
if [ "$MEM_GB" -lt 32 ]; then
  echo "Qwen-Image needs about 30 GB while drawing; this Mac has ${MEM_GB} GB."
  echo "It may still run very slowly with heavy swapping. Continue? (y/N)"
  read -r ans; [ "$ans" = "y" ] || exit 1
fi

# mflux (MLX image models) through uv.
if ! command -v uv >/dev/null 2>&1; then
  echo "Installing uv..."
  curl -LsSf https://astral.sh/uv/install.sh | sh
  export PATH="$HOME/.local/bin:$PATH"
fi
echo "Installing / updating mflux..."
uv tool install --upgrade mflux
export PATH="$HOME/.local/bin:$PATH"
command -v mflux-generate-qwen >/dev/null || { echo "mflux-generate-qwen not found after install"; exit 1; }

# A local Qwen text model in Ollama.
LLM="qwen3:8b"
if command -v ollama >/dev/null 2>&1; then
  HAVE=$(ollama list 2>/dev/null | awk 'NR>1{print $1}' | grep -i '^qwen' | head -1 || true)
  if [ -n "$HAVE" ]; then LLM="$HAVE"; else echo "Pulling $LLM ..."; ollama pull "$LLM"; fi
else
  echo "Ollama is not installed: get it from https://ollama.com, then run: ollama pull $LLM"
fi
echo "Text model: $LLM"

# Picture models by memory: 6-bit with room to spare, 4-bit editing on 32-63 GB.
if [ "$MEM_GB" -ge 64 ]; then EDIT="OsaurusAI/Qwen-Image-Edit-mflux-q6"; W=1024; H=768
else EDIT="OsaurusAI/Qwen-Image-Edit-mflux-q4"; W=896; H=672; fi
GEN="OsaurusAI/Qwen-Image-mflux-6bit"

if [ ! -f config.json ]; then
  TOKEN=$(LC_ALL=C tr -dc 'a-z0-9' </dev/urandom | head -c 12)
  cat > config.json <<EOF
{
  "port": 8765,
  "token": "$TOKEN",
  "llm": "$LLM",
  "gen_model": "$GEN",
  "edit_model": "$EDIT",
  "width": $W,
  "height": $H,
  "steps": 20
}
EOF
  echo "config.json written."
fi

# Start at login and keep running (launchd).
PLIST="$HOME/Library/LaunchAgents/com.owlinterpreter.studio.plist"
mkdir -p "$HOME/Library/LaunchAgents" "$HOME/owl-studio"
cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>com.owlinterpreter.studio</string>
  <key>ProgramArguments</key><array><string>/usr/bin/python3</string><string>$HERE/owl_studio.py</string></array>
  <key>EnvironmentVariables</key><dict><key>PATH</key><string>$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string></dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$HOME/owl-studio/studio.log</string>
  <key>StandardErrorPath</key><string>$HOME/owl-studio/studio.log</string>
</dict></plist>
EOF
launchctl unload "$PLIST" 2>/dev/null || true
launchctl load "$PLIST"

if [ "$1" = "--test" ]; then
  echo "Drawing two test pictures (the first run downloads the model, about 20 GB)..."
  /usr/bin/python3 owl_studio.py --selftest
fi

TOKEN=$(/usr/bin/python3 -c "import json;print(json.load(open('config.json')).get('token',''))")
echo
echo "Owl Studio is running (log: ~/owl-studio/studio.log)."
echo "In the app: Settings > Picture keys > MacBook (local Qwen)"
echo "  address: <this Mac's WireGuard IP>:8765"
for ip in $(ifconfig | awk '/inet /{print $2}' | grep -v '^127\.'); do echo "    candidate: $ip:8765"; done
echo "  token:   $TOKEN"
