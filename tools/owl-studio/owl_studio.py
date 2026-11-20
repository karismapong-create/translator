#!/usr/bin/env python3
"""
Owl Studio: draws a book part's pictures on your Mac with local Qwen models, for the 부엉이 통역 app.

- Ollama (a local Qwen text model) writes the book's character sheet once and a short visual
  description for every sentence.
- mflux draws the first picture with Qwen-Image, and each next one with Qwen-Image-Edit from the
  picture before it, so characters and style carry through the whole part.
- The app sends a job, then fetches each picture as soon as it is ready; the job goes on if the
  phone sleeps, and sending the same part again picks it up where it is. The phone records the
  narration and makes the video itself.

Standard library only. Run:  python3 owl_studio.py   (settings in config.json next to this file)
"""
import hashlib
import http.server
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import traceback
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
CONFIG_FILE = HERE / "config.json"
DEFAULTS = {
    "port": 8765,
    "token": "",                       # optional: the app sends it as X-Owl-Token
    "data_dir": str(Path.home() / "owl-studio"),
    "ollama": "http://127.0.0.1:11434",
    "llm": "qwen3:8b",                 # an Ollama model; install.sh picks a Qwen you have
    "width": 1024,
    "height": 768,
    "steps": 20,
    # {model} {prompt} {out} {ref} {width} {height} {steps} {seed} are filled in for each picture.
    "gen_model": "OsaurusAI/Qwen-Image-mflux-6bit",
    "edit_model": "OsaurusAI/Qwen-Image-Edit-mflux-q6",
    "gen_cmd": ["mflux-generate-qwen", "--model", "{model}", "--base-model", "qwen", "--prompt", "{prompt}",
                "--width", "{width}", "--height", "{height}", "--steps", "{steps}", "--seed", "{seed}", "--output", "{out}"],
    "edit_cmd": ["mflux-generate-qwen-edit", "--model", "{model}", "--base-model", "qwen", "--image-paths", "{ref}",
                 "--prompt", "{prompt}", "--width", "{width}", "--height", "{height}", "--steps", "{steps}",
                 "--seed", "{seed}", "--output", "{out}"],
}

STYLE = ("Gentle hand-painted watercolor storybook illustration, warm soft colors, delicate ink lines, "
         "detailed and expressive faces, soft natural light, cinematic composition. No text, letters or speech bubbles.")


def load_config():
    cfg = dict(DEFAULTS)
    if CONFIG_FILE.exists():
        try:
            cfg.update(json.loads(CONFIG_FILE.read_text()))
        except Exception as e:
            print("config.json unreadable, using defaults:", e)
    return cfg


CFG = load_config()
DATA = Path(CFG["data_dir"])
JOBS = DATA / "jobs"
CASTS = DATA / "casts"
JOBS.mkdir(parents=True, exist_ok=True)
CASTS.mkdir(parents=True, exist_ok=True)

LOCK = threading.Lock()
QUEUE = []             # job ids waiting, oldest first
CANCEL = set()
WAKE = threading.Event()


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ---------------- Ollama (local Qwen text model) ----------------

def ask_llm(system, user, max_tokens=400):
    body = json.dumps({
        "model": CFG["llm"], "stream": False, "think": False,
        "options": {"num_predict": max_tokens, "temperature": 0.4},
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
    }).encode()
    req = urllib.request.Request(CFG["ollama"].rstrip("/") + "/api/chat", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        text = json.loads(r.read().decode())["message"]["content"]
    # Reasoning models may still wrap thoughts in <think> tags.
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.S).strip().strip('"')
    return text


def cast_sheet(book_id, title, author):
    f = CASTS / f"{book_id}.txt"
    if f.exists():
        return f.read_text().strip()
    try:
        text = ask_llm(
            "You are the art director of an illustrated edition. Write a character and style sheet in English, "
            "at most 160 words: for each main character (up to 6), name followed by fixed age, face, hair, build "
            "and clothing with colours; then one line on the setting and era and one on the colour palette. "
            "Plain text only.",
            f'Book: "{title}" by {author}', 500)
    except Exception as e:
        log("cast sheet failed:", e)
        return ""
    if len(text) > 40:
        f.write_text(text)
        return text
    return ""


def visual(job, i, cast):
    s = job["sentences"]
    scene = s[i]
    plain = f'{STYLE} Scene from "{job["title"]}" by {job["author"]}, in the book\'s era: {scene}' + \
            (f" Characters: {cast}" if cast else "")
    try:
        d = ask_llm(
            "You write prompts for an image generation model. Describe ONE moment as a single concrete picture, "
            "in English, 40 to 80 words: the characters (age, look, period clothing, expression), what they are "
            "doing, the setting and era, time of day and light, and the camera framing. Describe every character "
            "who appears exactly as the character sheet says. Draw only what the scene sentence shows; the context "
            "is for understanding only. No text in the picture. Answer with the description only.",
            f'Book: "{job["title"]}" by {job["author"]}' + (f' ({job["chapter"]})' if job.get("chapter") else "") +
            (f"\nCharacter sheet: {cast}" if cast else "") +
            f"\nContext before: {s[i - 1] if i > 0 else ''}\nScene sentence: {scene}\n"
            f"Context after: {s[i + 1] if i + 1 < len(s) else ''}", 300)
        return f"{STYLE} {d}" if len(d) >= 20 else plain
    except Exception as e:
        log("visual prompt failed, using the plain one:", e)
        return plain


# ---------------- Drawing (mflux) ----------------

def run_draw(cmd_key, model, prompt, out_png, ref, seed):
    values = {"model": model, "prompt": prompt, "out": str(out_png), "ref": str(ref or ""),
              "width": str(CFG["width"]), "height": str(CFG["height"]), "steps": str(CFG["steps"]), "seed": str(seed)}
    cmd = [part.format(**values) for part in CFG[cmd_key]]
    log("draw:", cmd[0], Path(out_png).name)
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0 and "--base-model" in cmd and "unrecognized arguments" in (p.stderr or ""):
        # Older/newer mflux without --base-model for this command: once more without it.
        k = cmd.index("--base-model")
        cmd = cmd[:k] + cmd[k + 2:]
        p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0 or not Path(out_png).exists():
        tail = ((p.stderr or "") + (p.stdout or "")).strip().splitlines()[-6:]
        raise RuntimeError(" | ".join(tail)[-500:] or f"exit {p.returncode}")


def to_jpeg(png, jpg):
    # sips comes with macOS: longest side 1024, JPEG.
    subprocess.run(["sips", "-Z", "1024", "-s", "format", "jpeg", "-s", "formatOptions", "85", str(png), "--out", str(jpg)],
                   capture_output=True, check=True)


# ---------------- Jobs ----------------

def job_dir(jid):
    return JOBS / jid


def read_state(jid):
    f = job_dir(jid) / "state.json"
    return json.loads(f.read_text()) if f.exists() else None


def write_state(jid, st):
    f = job_dir(jid) / "state.json"
    tmp = f.with_suffix(".tmp")
    tmp.write_text(json.dumps(st, ensure_ascii=False))
    tmp.replace(f)


def work(jid):
    st = read_state(jid)
    if not st or st["state"] in ("done", "cancelled"):
        return
    d = job_dir(jid)
    st["state"] = "working"
    st["error"] = ""
    write_state(jid, st)
    cast = st.get("cast") or cast_sheet(st["book_id"], st["title"], st["author"])
    st["cast"] = cast
    seed = int(st["book_id"]) % 100000 if str(st["book_id"]).isdigit() else 42
    n = len(st["sentences"])
    prev = None
    for i in range(n):
        if jid in CANCEL:
            st["state"] = "cancelled"
            write_state(jid, st)
            CANCEL.discard(jid)
            return
        png = d / f"{i}.png"
        jpg = d / f"{i}.jpg"
        if jpg.exists():
            prev = png if png.exists() else jpg
            continue
        st["current"] = i
        write_state(jid, st)
        prompt = visual(st, i, cast)
        try:
            if prev is None:
                run_draw("gen_cmd", CFG["gen_model"], prompt, png, None, seed)
            else:
                edit = ("The picture is the previous illustration of this story. Keep exactly the same characters "
                        "(faces, hair, age, clothing), art style, colours and setting where they continue. "
                        "Draw this new moment: " + prompt)
                run_draw("edit_cmd", CFG["edit_model"], edit, png, prev, seed)
            to_jpeg(png, jpg)
            prev = png
            st["done"] = sorted(set(st.get("done", [])) | {i})
            st["last_error"] = ""
        except Exception as e:
            log(f"picture {i} failed:", e)
            st["failed"] = sorted(set(st.get("failed", [])) | {i})
            st["last_error"] = str(e)[:500]
        write_state(jid, st)
    st["state"] = "done"
    st["current"] = -1
    write_state(jid, st)
    log("job done:", jid)


def worker():
    while True:
        WAKE.wait(5)
        WAKE.clear()
        while True:
            with LOCK:
                if not QUEUE:
                    break
                jid = QUEUE.pop(0)
            try:
                work(jid)
            except Exception as e:
                traceback.print_exc()
                st = read_state(jid) or {}
                st.update(state="error", error=str(e)[:500])
                write_state(jid, st)


def submit(body):
    sentences = [str(x) for x in body.get("sentences", []) if str(x).strip()]
    if not sentences:
        raise ValueError("no sentences")
    book_id = str(body.get("book_id", "0"))
    key = body.get("id") or "part"
    digest = hashlib.sha1(("\n".join(sentences)).encode()).hexdigest()[:10]
    jid = re.sub(r"[^A-Za-z0-9_-]", "_", f"{key}_{digest}")
    d = job_dir(jid)
    st = read_state(jid)
    if st is None or (body.get("restart") and st["state"] != "working"):
        if d.exists():
            shutil.rmtree(d)
        d.mkdir(parents=True)
        st = {"id": jid, "state": "queued", "book_id": book_id, "title": body.get("title", ""),
              "author": body.get("author", ""), "chapter": body.get("chapter", ""), "sentences": sentences,
              "cast": body.get("cast", ""), "done": [], "failed": [], "current": -1, "error": "", "last_error": "",
              "created": time.time()}
        write_state(jid, st)
    if st["state"] in ("queued", "error", "cancelled"):
        st["state"] = "queued"
        write_state(jid, st)
    with LOCK:
        if st["state"] == "queued" and jid not in QUEUE:
            QUEUE.append(jid)
    WAKE.set()
    return status(jid)


def status(jid):
    st = read_state(jid)
    if st is None:
        return None
    with LOCK:
        pos = QUEUE.index(jid) + 1 if jid in QUEUE else 0
    return {"id": jid, "state": st["state"], "total": len(st["sentences"]), "done": st.get("done", []),
            "failed": st.get("failed", []), "current": st.get("current", -1), "queue": pos,
            "error": st.get("error", ""), "last_error": st.get("last_error", "")}


# ---------------- HTTP ----------------

class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "OwlStudio/1"

    def log_message(self, fmt, *args):
        pass

    def _ok_token(self):
        t = CFG.get("token") or ""
        return not t or self.headers.get("X-Owl-Token", "") == t

    def _json(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if not self._ok_token():
            return self._json(401, {"error": "token"})
        parts = [p for p in self.path.split("?")[0].split("/") if p]
        if parts == ["health"]:
            return self._json(200, {"ok": True, "app": "owl-studio", "llm": CFG["llm"],
                                    "gen_model": CFG["gen_model"], "edit_model": CFG["edit_model"],
                                    "size": [CFG["width"], CFG["height"]], "steps": CFG["steps"]})
        if len(parts) == 2 and parts[0] == "jobs":
            s = status(parts[1])
            return self._json(200, s) if s else self._json(404, {"error": "no such job"})
        if len(parts) == 4 and parts[0] == "jobs" and parts[2] == "art" and re.fullmatch(r"\d+\.jpg", parts[3]):
            f = job_dir(re.sub(r"[^A-Za-z0-9_-]", "_", parts[1])) / parts[3]
            if not f.exists():
                return self._json(404, {"error": "not yet"})
            b = f.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)
            return
        self._json(404, {"error": "not found"})

    def do_POST(self):
        if not self._ok_token():
            return self._json(401, {"error": "token"})
        parts = [p for p in self.path.split("?")[0].split("/") if p]
        n = int(self.headers.get("Content-Length") or 0)
        try:
            body = json.loads(self.rfile.read(n).decode() or "{}") if n else {}
        except Exception:
            return self._json(400, {"error": "bad json"})
        try:
            if parts == ["jobs"]:
                return self._json(200, submit(body))
            if len(parts) == 3 and parts[0] == "jobs" and parts[2] == "cancel":
                jid = parts[1]
                with LOCK:
                    if jid in QUEUE:
                        QUEUE.remove(jid)
                CANCEL.add(jid)
                st = read_state(jid)
                if st and st["state"] == "queued":
                    st["state"] = "cancelled"
                    write_state(jid, st)
                return self._json(200, {"ok": True})
        except ValueError as e:
            return self._json(400, {"error": str(e)})
        self._json(404, {"error": "not found"})


def selftest():
    print("Ollama:", CFG["ollama"], "model:", CFG["llm"])
    print(" ->", ask_llm("Answer in one short sentence.", "Describe a fox in a forest.", 60))
    out = DATA / "selftest.png"
    out.unlink(missing_ok=True)
    t = time.time()
    run_draw("gen_cmd", CFG["gen_model"], STYLE + " A small owl reading a book under a lamp.", out, None, 7)
    print("Qwen-Image OK in", int(time.time() - t), "s ->", out)
    out2 = DATA / "selftest-edit.png"
    out2.unlink(missing_ok=True)
    t = time.time()
    run_draw("edit_cmd", CFG["edit_model"], "Keep the same owl and style. The owl now flies over a moonlit lake.", out2, out, 7)
    print("Qwen-Image-Edit OK in", int(time.time() - t), "s ->", out2)


def main():
    if "--selftest" in sys.argv:
        selftest()
        return
    # Jobs left unfinished by a restart go on.
    for d in sorted(JOBS.iterdir(), key=lambda p: p.stat().st_mtime):
        st = read_state(d.name)
        if st and st["state"] in ("queued", "working"):
            st["state"] = "queued"
            write_state(d.name, st)
            QUEUE.append(d.name)
    threading.Thread(target=worker, daemon=True).start()
    WAKE.set()
    srv = http.server.ThreadingHTTPServer(("0.0.0.0", int(CFG["port"])), Handler)
    log(f"Owl Studio on port {CFG['port']} (LLM {CFG['llm']}, pictures {CFG['gen_model']} / {CFG['edit_model']})")
    srv.serve_forever()


if __name__ == "__main__":
    main()
