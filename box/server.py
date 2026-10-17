#!/usr/bin/env python3
"""Thai Game Translator — home box relay.

Runs on a Linux box where Claude Code is logged in (e.g. with a Claude Max subscription).
The Android app sends a prompt (+ optional screenshot) over Wi-Fi; this server runs
`claude -p` and returns the answer. Python 3 standard library only.
"""
import base64
import json
import os
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VERSION = 1
HOME = os.path.expanduser('~')
CONF_DIR = os.path.join(HOME, '.config', 'thai-translate-box')
CONF_FILE = os.path.join(CONF_DIR, 'config.json')
WORK_DIR = os.path.join(HOME, '.cache', 'thai-translate-box')
DEFAULTS = {'port': 8787, 'pin': '', 'model': 'haiku', 'claude': '', 'timeout': 120}
SLOTS = threading.Semaphore(1)          # one Claude call at a time
LOG_LOCK = threading.Lock()


def log(*a):
    with LOG_LOCK:
        print(time.strftime('%H:%M:%S'), *a, flush=True)


def load_conf():
    c = dict(DEFAULTS)
    try:
        with open(CONF_FILE, encoding='utf-8') as f:
            c.update(json.load(f))
    except FileNotFoundError:
        pass
    if not c['claude']:
        c['claude'] = find_claude() or 'claude'
    return c


def find_claude():
    cands = [shutil.which('claude'), os.path.join(HOME, '.local/bin/claude'), os.path.join(HOME, '.claude/local/claude'),
             os.path.join(HOME, '.npm-global/bin/claude'), '/usr/local/bin/claude', '/usr/bin/claude']
    for p in cands:
        if p and os.path.isfile(p) and os.access(p, os.X_OK):
            return p
    return None


class Session:
    """One long-running `claude -p --input-format stream-json` process. Reusing it skips the
    multi-second Claude Code start-up on every line (slow on small ARM boxes)."""
    MAX_TURNS = 8          # restart regularly so old screenshots don't pile up in context

    def __init__(self, conf, model, system):
        os.makedirs(WORK_DIR, exist_ok=True)
        self.turns = 0
        cmd = [conf['claude'], '-p', '--input-format', 'stream-json', '--output-format', 'stream-json', '--verbose',
               '--model', model, '--no-session-persistence', '--strict-mcp-config', '--tools', '']
        if system:
            cmd += ['--system-prompt', system]
        env = dict(os.environ)
        env.setdefault('HOME', HOME)
        self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                     text=True, bufsize=1, cwd=WORK_DIR, env=env)
        self.q = queue.Queue()
        threading.Thread(target=self._reader, daemon=True).start()

    def _reader(self):
        for line in self.proc.stdout:
            self.q.put(line)
        self.q.put(None)

    def alive(self):
        return self.proc.poll() is None and self.turns < self.MAX_TURNS

    def ask(self, prompt, image_b64, timeout):
        content = []
        if image_b64:
            content.append({'type': 'image', 'source': {'type': 'base64', 'media_type': 'image/jpeg', 'data': image_b64}})
        content.append({'type': 'text', 'text': prompt})
        msg = {'type': 'user', 'message': {'role': 'user', 'content': content}}
        self.proc.stdin.write(json.dumps(msg, ensure_ascii=False) + '\n')
        self.proc.stdin.flush()
        self.turns += 1
        deadline = time.time() + timeout
        while True:
            left = deadline - time.time()
            if left <= 0:
                raise subprocess.TimeoutExpired('claude', timeout)
            line = self.q.get(timeout=left)
            if line is None:
                raise RuntimeError('Claude Code ปิดตัวเอง (อาจต้องล็อกอินใหม่: พิมพ์ claude ในกล่อง)')
            try:
                j = json.loads(line)
            except json.JSONDecodeError:
                continue
            if j.get('type') == 'result':
                if j.get('is_error'):
                    raise RuntimeError(str(j.get('result') or j.get('subtype') or 'error'))
                return j.get('result', '')

    def close(self):
        try:
            self.proc.stdin.close()
            self.proc.terminate()
        except Exception:
            pass


SESSIONS = {}
SESSION_LOCK = threading.Lock()


def run_claude(conf, system, prompt, image_b64=None, model=None):
    """Returns (text, ms). Uses a warm session; falls back to a one-shot call if that fails."""
    model = model or conf['model']
    key = (model, system)
    t0 = time.time()
    with SESSION_LOCK:
        try:
            s = SESSIONS.get(key)
            if s is None or not s.alive():
                if s:
                    s.close()
                s = SESSIONS[key] = Session(conf, model, system)
            text = s.ask(prompt, image_b64, int(conf['timeout']))
            return text, int((time.time() - t0) * 1000)
        except Exception as e:
            s = SESSIONS.pop(key, None)
            if s:
                s.close()
            if isinstance(e, subprocess.TimeoutExpired):
                raise
            log(f'session failed ({e}); retrying one-shot')
    return run_once(conf, system, prompt, image_b64, model, t0)


def run_once(conf, system, prompt, image_b64, model, t0):
    os.makedirs(WORK_DIR, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=WORK_DIR) as wd:
        tools = ''
        if image_b64:
            with open(os.path.join(wd, 'screen.jpg'), 'wb') as f:
                f.write(base64.b64decode(image_b64))
            prompt = ('First use the Read tool to look at the game screenshot file ./screen.jpg, then do the task.\n\n'
                      + prompt)
            tools = 'Read'
        cmd = [conf['claude'], '-p', '--output-format', 'json', '--model', model,
               '--no-session-persistence', '--strict-mcp-config', '--tools', tools]
        if tools:
            cmd += ['--allowedTools', tools]
        if system:
            cmd += ['--system-prompt', system]
        env = dict(os.environ)
        env.setdefault('HOME', HOME)
        p = subprocess.run(cmd, input=prompt, capture_output=True, text=True, cwd=wd,
                           timeout=int(conf['timeout']), env=env)
    ms = int((time.time() - t0) * 1000)
    out = (p.stdout or '').strip()
    try:
        j = json.loads(out.splitlines()[-1]) if out else {}
    except (json.JSONDecodeError, IndexError):
        j = {}
    if p.returncode != 0 or j.get('is_error') or 'result' not in j:
        detail = j.get('result') or (p.stderr or out or 'no output').strip()[-300:]
        raise RuntimeError(detail)
    return j['result'], ms


class Handler(BaseHTTPRequestHandler):
    server_version = 'ThaiTranslateBox/%d' % VERSION

    def log_message(self, *a):
        pass

    def _send(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path.startswith('/ping'):
            return self._send(200, {'app': 'thai-translate-box', 'version': VERSION, 'host': os.uname().nodename})
        self._send(404, {'error': 'not found'})

    def do_POST(self):
        conf = self.server.conf
        if self.path != '/ai':
            return self._send(404, {'error': 'not found'})
        if conf['pin'] and self.headers.get('X-Pin', '') != str(conf['pin']):
            return self._send(401, {'error': 'PIN ไม่ถูกต้อง'})
        try:
            n = int(self.headers.get('Content-Length') or 0)
            if n > 8_000_000:
                return self._send(413, {'error': 'request too large'})
            req = json.loads(self.rfile.read(n) or b'{}')
            if not SLOTS.acquire(timeout=30):
                return self._send(503, {'error': 'กล่องกำลังยุ่ง ลองใหม่'})
            try:
                text, ms = run_claude(conf, req.get('system', ''), req.get('prompt', ''),
                                      req.get('image'), req.get('model'))
            finally:
                SLOTS.release()
            log(f'{self.client_address[0]} ok {ms} ms{" +image" if req.get("image") else ""}')
            self._send(200, {'text': text, 'ms': ms})
        except subprocess.TimeoutExpired:
            log(f'{self.client_address[0]} timeout')
            self._send(504, {'error': 'Claude ตอบช้าเกินไป'})
        except Exception as e:
            log(f'{self.client_address[0]} error: {e}')
            self._send(500, {'error': str(e)[:300]})


def selftest():
    conf = load_conf()
    print('claude:', conf['claude'])
    text, ms = run_claude(conf, 'Reply with only the Thai translation.', 'Translate to Thai: You too, Simon.')
    print(f'OK ({ms} ms): {text}')


def main():
    if '--selftest' in sys.argv:
        try:
            selftest()
        except Exception as e:
            print('FAILED:', e)
            sys.exit(1)
        return
    conf = load_conf()
    srv = ThreadingHTTPServer(('0.0.0.0', int(conf['port'])), Handler)
    srv.conf = conf
    log(f'Thai Game Translator box listening on port {conf["port"]} (model {conf["model"]}, claude {conf["claude"]})')
    srv.serve_forever()


if __name__ == '__main__':
    main()
