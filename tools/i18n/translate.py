#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Lumen contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Fills the Lumen Studio translation catalogues (protocol lumen.i18n v1).

  tools/i18n/kt.sh                       # keys.json from the sources (same parser as the build)
  GEMINI_API_KEY=… tools/i18n/translate.py [lang …] [--model M] [--batch N] [--jobs N]
  (free tier: ~500 requests a day per model; when gemini-3.5-flash-lite is used up, --model gemini-3.5-flash)

Only missing keys are sent; keys that left the sources are dropped. A translation must keep the
{N} placeholders of its key, or it is not saved (the app shows English there). Output:
studio/src/main/assets/lumen_i18n/<lang>.json, sorted, one key per line (small diffs).
"""
import concurrent.futures as cf
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KEYS = os.path.join(ROOT, "tools/i18n/keys.json")
OUT = os.path.join(ROOT, "studio/src/main/assets/lumen_i18n")

# keep in step with I18n.LANGS (ru and en live in the sources)
LANGS = {
    "bn": "Bengali", "ur": "Urdu", "th": "Thai",
    "es": "Spanish (neutral, Latin America friendly)",
    "pt": "Portuguese (Brazil)",
    "fr": "French",
    "de": "German",
    "it": "Italian",
    "tr": "Turkish",
    "pl": "Polish",
    "uk": "Ukrainian",
    "ar": "Arabic (Modern Standard)",
    "hi": "Hindi",
    "id": "Indonesian",
    "vi": "Vietnamese",
    "ja": "Japanese",
    "ko": "Korean",
    "zh": "Chinese (Simplified, mainland)",
}

PLACE = re.compile(r"\{(\d{1,2})\}")

RULES = """You translate the interface of Lumen Studio, an Android app for making videos on the phone
(AI script, voice-over, subtitles, a timeline video editor, info-schemes, clips).
Input: JSON {"id": {"en": English UI string, "ru": the same in Russian}}. Output: JSON {"id": "translation"} with
every id. Rules:
- Translate the English meaning into natural, short, native mobile-UI wording; the Russian is context only.
- Keep placeholders like {0} {1} exactly (you may move them), keep emoji, symbols (▶ ✕ ↶ ⋯ % · — →), line breaks
  and leading/trailing spaces.
- Do not translate product and brand names: Lumen, Lumen Studio, Gemini, ChatGPT, Claude, OpenAI, Anthropic, YouTube,
  Shorts, Reels, TikTok, Edge TTS, Pexels, Pixabay, Openverse, Wikimedia Commons, Lottie, JSON, API, MP4, WAV, GPU, CPU.
- Technical words of video editors use the terms of popular editors in that language (B-roll, PiP, keyframe…).
- Russian-style quotes «…» become the quotes of the target language.
- Do not add explanations."""


def placeholders(s):
    return sorted(set(PLACE.findall(s)))


def ask(model, key, lang_name, items, tries=4):
    body = {
        "systemInstruction": {"parts": [{"text": RULES}]},
        "contents": [{"role": "user", "parts": [{"text": "Target language: " + lang_name + "\n\n" +
                                                 json.dumps(items, ensure_ascii=False)}]}],
        "generationConfig": {"responseMimeType": "application/json", "temperature": 0.2},
    }
    if "lite" not in model:
        body["generationConfig"]["thinkingConfig"] = {"thinkingLevel": "minimal"}
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
    data = json.dumps(body).encode()
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, data, {"Content-Type": "application/json", "x-goog-api-key": key})
            with urllib.request.urlopen(req, timeout=180) as r:
                o = json.load(r)
            text = "".join(p.get("text", "") for p in o["candidates"][0]["content"]["parts"])
            return json.loads(text)
        except urllib.error.HTTPError as e:
            msg = e.read().decode(errors="replace")[:300]
            if e.code in (400, 401, 403, 404):
                raise RuntimeError(f"HTTP {e.code}: {msg}")
            if e.code == 429 and "per day" in msg:
                raise RuntimeError("daily quota of " + model + " is used up: " + msg[:120])
            time.sleep(5 * (attempt + 1))
        except Exception:
            time.sleep(3 * (attempt + 1))
    return {}


def load(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return {}


def save(path, m):
    with open(path, "w", encoding="utf-8") as f:
        f.write("{\n")
        items = sorted(m.items())
        for i, (k, v) in enumerate(items):
            f.write("  " + json.dumps(k, ensure_ascii=False) + ": " + json.dumps(v, ensure_ascii=False))
            f.write(",\n" if i < len(items) - 1 else "\n")
        f.write("}\n")


def main():
    args = sys.argv[1:]
    model, batch, jobs = "gemini-3.5-flash-lite", 80, 6
    langs = []
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--model": model = args[i + 1]; i += 2; continue
        if a == "--batch": batch = int(args[i + 1]); i += 2; continue
        if a == "--jobs": jobs = int(args[i + 1]); i += 2; continue
        langs.append(a); i += 1
    langs = langs or list(LANGS)
    key = os.environ.get("GEMINI_API_KEY")
    if not key:
        sys.exit("GEMINI_API_KEY is not set")
    keys = load(KEYS)
    os.makedirs(OUT, exist_ok=True)
    for lang in langs:
        path = os.path.join(OUT, lang + ".json")
        cat = {k: v for k, v in load(path).items() if k in keys}
        todo = [k for k in keys if k not in cat]
        print(f"{lang}: {len(cat)} kept, {len(todo)} to translate", flush=True)
        chunks = [todo[j:j + batch] for j in range(0, len(todo), batch)]

        def run(chunk):
            items = {str(n): {"en": k, "ru": keys[k]} for n, k in enumerate(chunk)}
            got = ask(model, key, LANGS[lang], items)
            out = {}
            for n, k in enumerate(chunk):
                v = got.get(str(n))
                if isinstance(v, str) and v.strip() and placeholders(v) == placeholders(k):
                    out[k] = v
            return out

        bad = 0
        with cf.ThreadPoolExecutor(jobs) as ex:
            for n, part in enumerate(ex.map(run, chunks)):
                cat.update(part)
                bad += len(chunks[n]) - len(part)
                if n % 10 == 9:
                    save(path, cat)
        save(path, cat)
        print(f"{lang}: {len(cat)}/{len(keys)} (rejected or missing {bad})", flush=True)


if __name__ == "__main__":
    main()
