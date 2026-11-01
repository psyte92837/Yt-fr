#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Lumen contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Builds Lumen's free-AI catalog (protocol lumen.freeai v1) from FreeLLMAPI data.

FreeLLMAPI (https://github.com/tashfeenahmed/freellmapi, MIT) keeps a curated list of free LLM tiers. Only its DATA is
read here, as text; none of its code is run:
  client/src/components/keys/shared.tsx  -> provider titles and "get a key" links
  server/src/providers/index.ts          -> OpenAI-compatible base URLs, keyless flag
  models: the signed monthly snapshot from api.freellmapi.co (Ed25519, key pinned in services/catalog-sync.ts),
          or, if it can not be fetched or verified, the model rows of the bundled migrations (older baseline).

  sync.py --repo DIR [--snapshot FILE|URL|none] [--out FILE]
Writes --out only when the content changed; prints a one-line summary. Exit 1 if the result looks broken.
"""
import argparse
import base64
import datetime
import glob
import hashlib
import json
import os
import re
import sys
import urllib.request

PROTOCOL = "lumen.freeai v1"
SNAPSHOT_URL = "https://api.freellmapi.co/v1/latest"
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets", "lumen_freeai.json")

# Providers with their own (non OpenAI) adapter in FreeLLMAPI that Lumen can still call.
NATIVE = {
    "google": {"base": "https://generativelanguage.googleapis.com/v1beta", "api": "gemini"},
}
# Registered upstream but not usable from an app: OpenCode's free tier answers 403 outside the OpenCode client.
BLOCKED = {"opencode"}


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def parse_labels(tsx):
    """{ value: 'groq', label: 'Groq', url: 'https://...' } -> {id: (title, url)}"""
    out = {}
    for m in re.finditer(r"\{\s*value:\s*'([^']+)',\s*label:\s*'((?:[^'\\]|\\.)*)'(?:,\s*url:\s*'([^']*)')?", tsx):
        out[m.group(1)] = (m.group(2).replace("\\'", "'"), m.group(3) or "")
    return out


def parse_providers(ts):
    """register(new OpenAICompatProvider({ platform, name, baseUrl, keyless })) -> {id: {...}}"""
    out = {}
    for m in re.finditer(r"new OpenAICompatProvider\(\{(.*?)\}\)\)", ts, re.S):
        body = m.group(1)
        pid = re.search(r"platform:\s*'([^']+)'", body)
        base = re.search(r"baseUrl:\s*'([^']*)'", body)
        if not pid or not base or not base.group(1).startswith("https://"):
            continue
        name = re.search(r"name:\s*'((?:[^'\\]|\\.)*)'", body)
        out[pid.group(1)] = {
            "base": base.group(1).rstrip("/"),
            "api": "openai",
            "name": name.group(1).replace("\\'", "'") if name else pid.group(1),
            "keyless": bool(re.search(r"keyless:\s*true", body)),
        }
    return out


def pubkey_from(ts):
    m = re.search(r"-----BEGIN PUBLIC KEY-----\s*(.*?)\s*-----END PUBLIC KEY-----", ts, re.S)
    return m.group(1).strip() if m else None


def verify(data, sig_b64, pem_body):
    """Ed25519 over the exact bytes. The raw key is the last 32 bytes of the SPKI DER."""
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    raw = base64.b64decode(pem_body)[-32:]
    Ed25519PublicKey.from_public_bytes(raw).verify(base64.b64decode(sig_b64), data)


def fetch_snapshot(src, pem_body):
    """Returns (catalog dict, label) or (None, reason)."""
    if src == "none":
        return None, "snapshot off"
    try:
        if src.startswith("https://"):
            req = urllib.request.Request(src, headers={"User-Agent": "lumen-freeai-sync"})
            with urllib.request.urlopen(req, timeout=30) as r:
                data, sig = r.read(), r.headers.get("x-catalog-signature")
        else:
            data = open(src, "rb").read()
            sig = open(src + ".sig").read().strip() if os.path.exists(src + ".sig") else None
        if not sig or not pem_body:
            return None, "no signature"
        verify(data, sig, pem_body)
        cat = json.loads(data)
        if not isinstance(cat.get("models"), list):
            return None, "bad shape"
        return cat, "snapshot %s %s" % (cat.get("tier", "?"), cat.get("version", "?"))
    except Exception as e:  # network, signature, json
        return None, "snapshot failed: %s" % e


ROW = re.compile(
    r"\[\s*'([^']+)',\s*'([^']+)',\s*'((?:[^'\\]|\\.)*)',\s*(\d+),\s*(\d+),\s*'([^']*)',\s*"
    r"(null|\d+),\s*(null|\d+),\s*(null|\d+),\s*(null|\d+),\s*(null|'[^']*'),\s*(null|\d+)\s*\]")
DISABLE = re.compile(r"UPDATE models SET [^`;]*?enabled\s*=\s*0[^`;]*?WHERE platform\s*=\s*'([^']+)' AND model_id\s*=\s*'([^']+)'")
DELETE = re.compile(r"DELETE FROM models WHERE platform\s*=\s*'([^']+)' AND model_id\s*=\s*'([^']+)'")
RENAME = re.compile(r"renameModel\(db,\s*'([^']+)',\s*'([^']+)',\s*'([^']+)'")


def migration_models(repo):
    """Best effort: literal model rows of the migrations, in file order (later wins)."""
    num = lambda v: None if v == "null" else int(v)
    rows = {}
    for path in sorted(glob.glob(os.path.join(repo, "server/src/db/migrations/*.ts"))):
        text = read(path)
        events = [(m.start(), "row", m) for m in ROW.finditer(text)]
        events += [(m.start(), "off", m) for m in DISABLE.finditer(text)]
        events += [(m.start(), "del", m) for m in DELETE.finditer(text)]
        events += [(m.start(), "ren", m) for m in RENAME.finditer(text)]
        for _, kind, m in sorted(events, key=lambda e: e[0]):
            if kind == "row":
                g = m.groups()
                rows[(g[0], g[1])] = {
                    "platform": g[0], "modelId": g[1], "displayName": g[2].replace("\\'", "'"),
                    "intelligenceRank": int(g[3]), "speedRank": int(g[4]),
                    "limits": {"rpm": num(g[6]), "rpd": num(g[7]), "tpm": num(g[8]), "tpd": num(g[9])},
                    "contextWindow": num(g[11]), "enabled": True, "supportsVision": False, "supportsTools": False,
                }
            elif kind == "off" and (m.group(1), m.group(2)) in rows:
                rows[(m.group(1), m.group(2))]["enabled"] = False
            elif kind == "del":
                rows.pop((m.group(1), m.group(2)), None)
            elif kind == "ren" and (m.group(1), m.group(2)) in rows:
                r = rows.pop((m.group(1), m.group(2)))
                r["modelId"] = m.group(3)
                rows[(m.group(1), m.group(3))] = r
    return list(rows.values())


def build(labels, provs, models, upstream):
    usable = dict(provs)
    for pid, extra in NATIVE.items():
        usable[pid] = dict(extra, name=labels.get(pid, (pid, ""))[0], keyless=False)
    out_models, used = [], set()
    for m in models:
        pid = m.get("platform")
        if pid not in usable or pid in BLOCKED or not m.get("enabled", True) or (m.get("modality") or "text") != "text":
            continue
        lim = m.get("limits") or {}
        out_models.append({
            "p": pid, "id": m["modelId"], "name": m.get("displayName") or m["modelId"],
            "intel": int(m.get("intelligenceRank") or 50), "speed": int(m.get("speedRank") or 50),
            "ctx": m.get("contextWindow") or 0,
            "rpm": lim.get("rpm") or 0, "rpd": lim.get("rpd") or 0, "tpm": lim.get("tpm") or 0, "tpd": lim.get("tpd") or 0,
            "vision": bool(m.get("supportsVision")), "tools": bool(m.get("supportsTools")),
        })
        used.add(pid)
    out_models.sort(key=lambda x: (x["intel"], x["speed"], x["p"], x["id"]))
    out_provs = []
    for pid in sorted(used):
        p = usable[pid]
        title, url = labels.get(pid, (p["name"], ""))
        out_provs.append({"id": pid, "title": title, "base": p["base"], "api": p["api"], "key": url, "keyless": p["keyless"]})
    body = {"providers": out_provs, "models": out_models}
    digest = hashlib.sha256(json.dumps(body, sort_keys=True).encode()).hexdigest()[:16]
    return dict({"protocol": PROTOCOL, "version": datetime.date.today().strftime("%Y.%m.%d"), "upstream": upstream,
                 "hash": digest}, **body)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="checkout of tashfeenahmed/freellmapi")
    ap.add_argument("--snapshot", default=SNAPSHOT_URL)
    ap.add_argument("--out", default=OUT)
    a = ap.parse_args()
    labels = parse_labels(read(os.path.join(a.repo, "client/src/components/keys/shared.tsx")))
    provs = parse_providers(read(os.path.join(a.repo, "server/src/providers/index.ts")))
    pem = pubkey_from(read(os.path.join(a.repo, "server/src/services/catalog-sync.ts")))
    snap, label = fetch_snapshot(a.snapshot, pem)
    models = snap["models"] if snap else migration_models(a.repo)
    if not snap:
        label = "migrations (%s)" % label
    cat = build(labels, provs, models, "freellmapi " + label)
    if len(cat["providers"]) < 3 or len(cat["models"]) < 10:
        print("too small: %d providers, %d models (%s)" % (len(cat["providers"]), len(cat["models"]), label))
        sys.exit(1)
    prev = None
    if os.path.exists(a.out):
        try:
            prev = json.loads(read(a.out))
        except ValueError:
            pass
    if prev and prev.get("hash") == cat["hash"]:
        print("unchanged %s: %d providers, %d models (%s)" % (cat["hash"], len(cat["providers"]), len(cat["models"]), label))
        return
    # a snapshot result is never replaced by the older migrations baseline
    if prev and not snap and "snapshot" in prev.get("upstream", "") and "migrations" not in prev.get("upstream", ""):
        print("kept snapshot catalog (%s)" % label)
        return
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(cat, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print("updated %s: %d providers, %d models (%s)" % (cat["hash"], len(cat["providers"]), len(cat["models"]), label))


if __name__ == "__main__":
    main()
