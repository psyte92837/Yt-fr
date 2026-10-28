#!/usr/bin/env python3
"""Tiny offline reader for graphify-out/graph.json (stdlib only, no pip needed).

  gq.py find <text>    symbols whose name contains <text>  -> file:line
  gq.py file <name>    all symbols of a file (substring of the path) with lines
  gq.py near <symbol>  callers/callees/references of a symbol, with file:line
  gq.py top [N]        most connected symbols (the "god nodes")
Обзор: graphify-out/GRAPH_REPORT.md (имена сообществ и карта файлов, строит tools/graph_map.py).
Run from the repo root. Read the printed file:line with `Read offset/limit`.
"""
import json, os, sys, collections

G = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "graphify-out", "graph.json")
g = json.load(open(G))
nodes = {n["id"]: n for n in g["nodes"]}
adj = collections.defaultdict(list)
for e in g["links"]:
    adj[e["source"]].append((e["target"], e.get("relation", "?"), ">"))
    adj[e["target"]].append((e["source"], e.get("relation", "?"), "<"))


def short(n):
    f = n.get("source_file", "").split("/lumen/")[-1]
    return f"{n['label']}  {f}:{n.get('source_location', '').lstrip('L')}"


def pick(q):
    q = q.lower()
    hit = [n for n in nodes.values() if n.get("norm_label", n["label"].lower()) == q]
    return hit or [n for n in nodes.values() if q in n["label"].lower()]


cmd, *a = sys.argv[1:] or ["top"]
arg = " ".join(a)
if cmd == "find":
    for n in sorted(pick(arg), key=lambda n: -len(adj[n["id"]]))[:40]:
        print(short(n))
elif cmd == "file":
    for n in sorted((n for n in nodes.values() if arg.lower() in n.get("source_file", "").lower()),
                    key=lambda n: (n.get("source_file", ""), int(n.get("source_location", "L0").lstrip("L") or 0))):
        print(short(n))
elif cmd == "near":
    for n in pick(arg)[:3]:
        print("==", short(n))
        by = collections.defaultdict(list)
        for t, rel, d in adj[n["id"]]:
            if t in nodes:
                by[f"{rel} {d}"].append(short(nodes[t]))
        for k, v in sorted(by.items()):
            print(f"  [{k}]")
            for s in sorted(set(v))[:25]:
                print("    ", s)
elif cmd == "top":
    for n in sorted(nodes.values(), key=lambda n: -len(adj[n["id"]]))[: int(arg or 25)]:
        print(len(adj[n["id"]]), short(n))
else:
    print(__doc__)
