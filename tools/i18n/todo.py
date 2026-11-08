#!/usr/bin/env python3
# Lists missing keys of a catalogue (shortest first): tools/i18n/todo.py <lang> [offset] [count]
# Merges a JSON file of translations: tools/i18n/todo.py <lang> --merge file.json  (placeholders checked)
import json, os, re, sys
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
keys = json.load(open(os.path.join(ROOT, "tools/i18n/keys.json")))
lang = sys.argv[1]
path = os.path.join(ROOT, f"studio/src/main/assets/lumen_i18n/{lang}.json")
cat = json.load(open(path)) if os.path.exists(path) else {}
P = re.compile(r"\{(\d{1,2})\}")
if len(sys.argv) > 2 and sys.argv[2] == "--merge":
    new = json.load(open(sys.argv[3])); ok = bad = 0
    for k, v in new.items():
        if k in keys and isinstance(v, str) and v.strip() and set(P.findall(k)) == set(P.findall(v)): cat[k] = v; ok += 1
        else: bad += 1; print("rejected:", k[:60])
    items = sorted(cat.items())
    with open(path, "w", encoding="utf-8") as f:
        f.write("{\n" + ",\n".join("  " + json.dumps(k, ensure_ascii=False) + ": " + json.dumps(v, ensure_ascii=False) for k, v in items) + "\n}\n")
    print(f"{lang}: merged {ok}, rejected {bad}, total {len(cat)}/{len(keys)}")
else:
    todo = sorted((k for k in keys if k not in cat), key=lambda k: (len(k), k))
    off = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    n = int(sys.argv[3]) if len(sys.argv) > 3 else 150
    print(f"# {lang}: {len(todo)} missing", file=sys.stderr)
    print(json.dumps({k: "" for k in todo[off:off + n]}, ensure_ascii=False, indent=0))
