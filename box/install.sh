#!/usr/bin/env bash
# Thai Game Translator — install the home-box relay (uses the Claude Code login on this machine).
# Usage:  curl -fsSL https://raw.githubusercontent.com/rotemaster-crypto/thai-game-translator/main/box/install.sh | bash
set -e
REPO_RAW="https://raw.githubusercontent.com/rotemaster-crypto/thai-game-translator/main/box"
APP_DIR="$HOME/thai-translate-box"
CONF_DIR="$HOME/.config/thai-translate-box"
PORT=8787

say() { printf '\n\033[1;32m%s\033[0m\n' "$*"; }
warn() { printf '\n\033[1;33m%s\033[0m\n' "$*"; }

say "[1/5] ตรวจเครื่อง"
command -v python3 >/dev/null || { warn "ไม่พบ python3 — ติดตั้งด้วย: sudo apt install -y python3"; exit 1; }
CLAUDE_BIN="$(command -v claude || true)"
for c in "$HOME/.local/bin/claude" "$HOME/.claude/local/claude" "$HOME/.npm-global/bin/claude" /usr/local/bin/claude; do
  [ -z "$CLAUDE_BIN" ] && [ -x "$c" ] && CLAUDE_BIN="$c"
done
if [ -z "$CLAUDE_BIN" ]; then
  warn "ไม่พบ Claude Code ในเครื่องนี้ — ติดตั้งและล็อกอินก่อน:  curl -fsSL https://claude.ai/install.sh | bash  แล้วพิมพ์ claude เพื่อล็อกอินด้วยบัญชี Max"
  exit 1
fi
echo "Claude Code: $CLAUDE_BIN ($("$CLAUDE_BIN" --version 2>/dev/null || echo '?'))"

say "[2/5] ติดตั้งโปรแกรม"
mkdir -p "$APP_DIR" "$CONF_DIR"
curl -fsSL "$REPO_RAW/server.py" -o "$APP_DIR/server.py"
chmod +x "$APP_DIR/server.py"
if [ -f "$CONF_DIR/config.json" ]; then
  PIN="$(python3 -c "import json;print(json.load(open('$CONF_DIR/config.json')).get('pin',''))")"
else
  PIN="$(python3 -c 'import random;print(random.randint(1000,9999))')"
fi
python3 - "$CONF_DIR/config.json" "$PIN" "$CLAUDE_BIN" "$PORT" <<'EOF'
import json, sys, os
path, pin, claude, port = sys.argv[1:]
c = {}
if os.path.exists(path):
    c = json.load(open(path))
c.update({'pin': pin, 'claude': claude, 'port': int(port)})
c.setdefault('model', 'haiku')
json.dump(c, open(path, 'w'), indent=2)
EOF

say "[3/5] ทดสอบเรียก Claude (ครั้งแรกอาจใช้ 10-30 วินาที)"
if python3 "$APP_DIR/server.py" --selftest; then
  echo "Claude ใช้งานได้"
else
  warn "เรียก Claude ไม่สำเร็จ — พิมพ์ claude ในเครื่องนี้หนึ่งครั้งเพื่อล็อกอินด้วยบัญชี Claude Max แล้วรันตัวติดตั้งนี้ใหม่"
  exit 1
fi

say "[4/5] ตั้งให้เปิดเองทุกครั้งที่บูต"
pkill -f "$APP_DIR/server.py" 2>/dev/null || true
if command -v systemctl >/dev/null && sudo -n true 2>/dev/null || { command -v systemctl >/dev/null && sudo -v; }; then
  sudo tee /etc/systemd/system/thai-translate-box.service >/dev/null <<EOF
[Unit]
Description=Thai Game Translator box (Claude Code relay)
After=network-online.target
Wants=network-online.target

[Service]
User=$USER
Environment=HOME=$HOME
Environment=PATH=$(dirname "$CLAUDE_BIN"):$(dirname "$(command -v node 2>/dev/null || echo /usr/bin/node)"):/usr/local/bin:/usr/bin:/bin
ExecStart=$(command -v python3) $APP_DIR/server.py
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
  sudo systemctl daemon-reload
  sudo systemctl enable --now thai-translate-box.service
  sudo systemctl restart thai-translate-box.service
  if command -v ufw >/dev/null && sudo ufw status 2>/dev/null | grep -q "Status: active"; then sudo ufw allow $PORT/tcp >/dev/null; fi
else
  (crontab -l 2>/dev/null | grep -v thai-translate-box; echo "@reboot nohup python3 $APP_DIR/server.py >> $APP_DIR/server.log 2>&1 &") | crontab -
  nohup python3 "$APP_DIR/server.py" >> "$APP_DIR/server.log" 2>&1 &
fi
sleep 2

say "[5/5] เสร็จแล้ว!"
IPS="$(hostname -I 2>/dev/null | tr ' ' '\n' | grep -E '^(192\.168|10\.|172\.(1[6-9]|2[0-9]|3[01]))' | head -3 | tr '\n' ' ')"
curl -fsS "http://127.0.0.1:$PORT/ping" >/dev/null && echo "เซิร์ฟเวอร์ทำงานอยู่" || warn "เซิร์ฟเวอร์ยังไม่ตอบ ลองดู: sudo journalctl -u thai-translate-box -n 30"
cat <<EOF

  ใส่ค่านี้ในแอป "แปลเกมไทย" บน Retroid (ตัว AI: กล่องที่บ้าน)
  ┌─────────────────────────────────────────┐
     ที่อยู่กล่อง :  ${IPS:-<IP ของเครื่องนี้>}(พอร์ต $PORT)
     PIN         :  $PIN
  └─────────────────────────────────────────┘
  (หรือกด "ค้นหากล่องอัตโนมัติ" ในแอป แล้วใส่แค่ PIN)

EOF
