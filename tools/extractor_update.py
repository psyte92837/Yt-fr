#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Lumen contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Finds a newer NewPipeExtractor for Lumen and pins it in gradle/libs.versions.toml.

Used by .github/workflows/extractor-watch.yml:
  extractor_update.py current                       -> prints the pinned version
  extractor_update.py find --mode auto --live ok|broken|blocked|unknown
                                                    -> prints JSON {pin, sha, kind, label, ahead, log} or {} if nothing newer
  extractor_update.py set <pin>                     -> writes the pin

Policy (mode auto): take the latest release if it is newer than the pin. If there is no newer release but live YouTube
is broken with the current pin, take the head of the dev branch (YouTube fixes land there first).
Mode release / dev forces one source. A candidate is used only when it is strictly ahead of the pin (no downgrades).
"""
import json
import os
import re
import sys
import urllib.request

UPSTREAM = "TeamNewPipe/NewPipeExtractor"
TOML = os.path.join(os.path.dirname(__file__), "..", "gradle", "libs.versions.toml")
KEY = "teamnewpipe-newpipe-extractor"
PIN_RE = re.compile(r'^(%s\s*=\s*")([^"]+)(")' % re.escape(KEY), re.M)


def read_pin(text):
    m = PIN_RE.search(text)
    if not m:
        raise SystemExit("pin %s not found in libs.versions.toml" % KEY)
    return m.group(2)


def write_pin(text, pin):
    if not re.fullmatch(r"[0-9A-Za-z._-]{1,64}", pin):
        raise SystemExit("bad pin: %r" % pin)
    return PIN_RE.sub(lambda m: m.group(1) + pin + m.group(3), text, count=1)


def api(path):
    req = urllib.request.Request("https://api.github.com/repos/%s/%s" % (UPSTREAM, path))
    req.add_header("Accept", "application/vnd.github+json")
    tok = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if tok:
        req.add_header("Authorization", "Bearer " + tok)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def choose(mode, live, release, dev, ahead_of_pin):
    """Pure policy. release/dev are (sha, label) or None; ahead_of_pin(sha) -> commits ahead (0 if not newer)."""
    order = {"release": [release], "dev": [dev]}.get(mode)
    if order is None:  # auto
        order = [release] + ([dev] if live == "broken" else [])
    for cand in order:
        if not cand:
            continue
        sha, label, kind = cand
        n = ahead_of_pin(sha)
        if n > 0:
            return {"pin": sha, "sha": sha, "label": label, "kind": kind, "ahead": n}
    return {}


def find(mode, live):
    with open(TOML, encoding="utf-8") as f:
        pin = read_pin(f.read())
    release = dev = None
    try:
        rel = api("releases/latest")
        tag = rel["tag_name"]
        release = (api("commits/" + tag)["sha"], tag, "release")
    except Exception as e:  # no releases or API hiccup
        print("release lookup failed: %s" % e, file=sys.stderr)
    try:
        head = api("commits/dev")
        dev = (head["sha"], "dev@" + head["sha"][:10], "dev")
    except Exception as e:
        print("dev lookup failed: %s" % e, file=sys.stderr)

    cache = {}

    def ahead(sha):
        if sha not in cache:
            try:
                cmp = api("compare/%s...%s" % (pin, sha))
                cache[sha] = (cmp.get("ahead_by", 0) if cmp.get("status") in ("ahead",) else 0, cmp)
            except Exception as e:
                print("compare %s...%s failed: %s" % (pin, sha, e), file=sys.stderr)
                cache[sha] = (0, {})
        return cache[sha][0]

    res = choose(mode, live, release, dev, ahead)
    if res:
        commits = cache[res["sha"]][1].get("commits", [])
        res["log"] = "\n".join("- %s %s" % (c["sha"][:10], c["commit"]["message"].splitlines()[0][:120])
                               for c in commits[-25:])
        res["current"] = pin
    return res


def main(argv):
    if len(argv) >= 2 and argv[1] == "current":
        with open(TOML, encoding="utf-8") as f:
            print(read_pin(f.read()))
    elif len(argv) >= 3 and argv[1] == "set":
        with open(TOML, encoding="utf-8") as f:
            text = f.read()
        with open(TOML, "w", encoding="utf-8") as f:
            f.write(write_pin(text, argv[2]))
    elif len(argv) >= 2 and argv[1] == "find":
        mode, live = "auto", "unknown"
        args = argv[2:]
        for i, a in enumerate(args):
            if a == "--mode":
                mode = args[i + 1]
            if a == "--live":
                live = args[i + 1]
        print(json.dumps(find(mode, live)))
    elif len(argv) >= 2 and argv[1] == "selftest":
        t = 'a = "1"\n%s = "abc123"\nb = "2"\n' % KEY
        assert read_pin(t) == "abc123"
        assert read_pin(write_pin(t, "def456")) == "def456" and 'b = "2"' in write_pin(t, "def456")
        rel, dev = ("r1", "v1", "release"), ("d1", "dev@d1", "dev")
        assert choose("auto", "ok", rel, dev, lambda s: 3 if s == "r1" else 0)["pin"] == "r1"
        assert choose("auto", "ok", rel, dev, lambda s: 0 if s == "r1" else 5) == {}
        assert choose("auto", "broken", rel, dev, lambda s: 0 if s == "r1" else 5)["pin"] == "d1"
        assert choose("dev", "ok", rel, dev, lambda s: 2)["kind"] == "dev"
        assert choose("release", "broken", None, dev, lambda s: 2) == {}
        print("selftest ok")
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
