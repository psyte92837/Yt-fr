#!/usr/bin/env python3
"""Post-processes graphify-out after `graphify cluster-only` (stdlib only, offline).

Names the communities after their dominant files and adds a per-file symbol map
to GRAPH_REPORT.md, so a session can pick the right file from the report alone.
Usage: graph_map.py [graphify-out dir]   (default: ./graphify-out)
"""
import collections, json, os, re, sys

D = sys.argv[1] if len(sys.argv) > 1 else "graphify-out"
GP, RP = os.path.join(D, "graph.json"), os.path.join(D, "GRAPH_REPORT.md")
g = json.load(open(GP))
nodes = g["nodes"]
deg = collections.Counter()
for e in g["links"]:
    deg[e["source"]] += 1
    deg[e["target"]] += 1


def fname(n):
    return os.path.basename(n.get("source_file", "")).rsplit(".", 1)[0]


def loc(n):
    m = re.sub(r"\D", "", n.get("source_location", ""))
    return int(m) if m else 0


comm = collections.defaultdict(list)
for n in nodes:
    comm[n.get("community", -1)].append(n)

names = {}
for c, ns in comm.items():
    files = collections.Counter(fname(n) for n in ns if fname(n))
    top = [f for f, _ in files.most_common(2)]
    key = max(ns, key=lambda n: deg[n["id"]])["label"].strip("()")
    names[c] = f"{' + '.join(top) or 'misc'} ({key})"
    for n in ns:
        n["community_name"] = names[c]
json.dump(g, open(GP, "w"), ensure_ascii=False)

byfile = collections.defaultdict(list)
for n in nodes:
    if n.get("source_file"):
        byfile[n["source_file"]].append(n)
lines = ["## Карта файлов (символы по числу связей; строка для Read offset)"]
for f in sorted(byfile, key=lambda f: ("/test/" in f, f)):
    ns = byfile[f]
    top = sorted(ns, key=lambda n: -deg[n["id"]])[:6]
    syms = ", ".join(f"{n['label']}:{loc(n)}" for n in top)
    lines.append(f"- `{f.split('/lumen/')[-1]}` ({len(ns)}): {syms}")
fmap = "\n".join(lines) + "\n\n"

hubs = ["## Community Hubs (Navigation)"]
for c in sorted(comm, key=lambda c: -len(comm[c])):
    if c >= 0:
        hubs.append(f"- Community {c}: {names[c]} — {len(comm[c])} симв.")
hubs = "\n".join(hubs) + "\n\n"

r = open(RP).read()
r = re.sub(r"## Community Hubs \(Navigation\)\n.*?(?=\n## )", lambda m: hubs.rstrip("\n") + "\n", r, flags=re.S)
r = re.sub(r"## Карта файлов.*?(?=\n## )", "", r, flags=re.S)
r = r.replace("## Communities (", fmap + "## Communities (", 1)
r = re.sub(r'### Community (\d+) - "[^"]*"',
           lambda m: f'### Community {m.group(1)} - "{names.get(int(m.group(1)), "?")}"', r)
open(RP, "w").write(r)
print(f"graph_map: {len(names)} communities named, {len(byfile)} files mapped")
