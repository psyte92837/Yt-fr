#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Lumen contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Hand-translated core of the interface (tier 1) for all languages at once.

  tools/i18n/tier1.py pick [N]          # writes tools/i18n/tier1_keys.json: the N most used "cores" of the texts
  tools/i18n/tier1.py show OFFSET COUNT # prints a slice of the list to translate
  tools/i18n/tier1.py merge file.json   # {"core": [18 translations in LANGS order]}; existing entries are kept
A core is a text without the signs around it ("🎬 Save video…" → "Save video"); I18n.lookup finds it for every spelling.
"""
import json, os, re, sys
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LANGS = "zh hi es ar fr bn pt ur id de ja tr vi ko it th pl uk".split()
OUT = os.path.join(ROOT, "studio/src/main/assets/lumen_i18n")
P = re.compile(r"\{(\d{1,2})\}")


def core(x):
    a = 0
    while a < len(x) and not x[a].isalnum(): a += 1
    b = len(x)
    while b > a and x[b - 1] in "…:. ": b -= 1
    if "{" in x[:a] or b - a < 2 or (a == 0 and b == len(x)): return x
    return x[a:b]


def pick(n):
    keys = json.load(open(os.path.join(ROOT, "tools/i18n/keys.json")))
    where = json.load(open(os.path.join(ROOT, "tools/i18n/keys_src.json")))
    pri = {"StudioActivity.kt": 3, "StudioEditorActivity.kt": 3, "StudioMixActivity.kt": 3, "Ui.kt": 3, "SettingsActivity.kt": 3,
           "AiSettings.kt": 2, "TtsActivity.kt": 2, "StudioOverlayUi.kt": 2, "SendReviewActivity.kt": 2, "I18n.kt": 50}
    sc = {}
    for x in keys:
        c = core(x)
        if len(c) > 220 and where.get(x) != "I18n.kt" or len(c) < 3: continue
        sc[c] = sc.get(c, 0) + 1 + pri.get(where.get(x, ""), 0)
    ranked = sorted(sc, key=lambda c: (-sc[c], len(c)))[:n]
    json.dump(ranked, open(os.path.join(ROOT, "tools/i18n/tier1_keys.json"), "w"), ensure_ascii=False, indent=0)
    print(len(ranked), "cores,", sum(len(c) for c in ranked), "characters")


def merge(path):
    data = json.load(open(path))
    added = {l: 0 for l in LANGS}
    bad = 0
    for l_i, l in enumerate(LANGS):
        f = os.path.join(OUT, l + ".json")
        cat = json.load(open(f)) if os.path.exists(f) else {}
        for k, arr in data.items():
            if not isinstance(arr, list) or len(arr) != len(LANGS):
                bad += 1 if l_i == 0 else 0; continue
            v = arr[l_i]
            if k in cat or not isinstance(v, str) or not v.strip() or set(P.findall(k)) != set(P.findall(v)):
                continue
            cat[k] = v; added[l] += 1
        with open(f, "w", encoding="utf-8") as fh:
            fh.write("{\n" + ",\n".join("  " + json.dumps(a, ensure_ascii=False) + ": " + json.dumps(b, ensure_ascii=False) for a, b in sorted(cat.items())) + "\n}\n")
    print("added", added, "malformed rows", bad)


if __name__ == "__main__":
    c = sys.argv[1]
    if c == "pick": pick(int(sys.argv[2]) if len(sys.argv) > 2 else 330)
    elif c == "show":
        t = json.load(open(os.path.join(ROOT, "tools/i18n/tier1_keys.json")))
        print(json.dumps(t[int(sys.argv[2]):int(sys.argv[2]) + int(sys.argv[3])], ensure_ascii=False))
    elif c == "merge": merge(sys.argv[2])
