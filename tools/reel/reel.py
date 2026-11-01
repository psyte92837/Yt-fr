#!/usr/bin/env python3
"""Lumen Reel — вертикальный ролик с драйвом: вырезка пауз, субтитры по словам,
моушн-плашки по смыслу, SFX и бит. Работает в облачной сессии Claude Code и на ПК.

  python3 reel.py setup                      # зависимости + модель Whisper (~640 МБ, 1 раз)
  python3 reel.py transcribe IN.mp4          # -> reel_work/transcript.txt (править только текст)
  python3 reel.py plan                       # -> reel_work/plan.json (авто-черновик, 0 токенов)
  python3 reel.py preview IN.mp4             # -> reel_work/sheet.jpg (кадры на моментах плашек)
  python3 reel.py render IN.mp4 OUT.mp4      # готовый ролик 1080x1920
  python3 reel.py auto IN.mp4 OUT.mp4        # всё сразу без правок (без ИИ)

Схема plan.json — в PROMPT.md рядом. Время везде в секундах ИСХОДНОГО видео,
якоря — слова/фразы из расшифровки ("подписыв", "3 доллара", "claude#2", "@12.5", "+1.5").
"""
import sys, os, re, json, math, glob, subprocess, shutil

WORK = os.environ.get("REEL_WORK", "reel_work")
CACHE = os.path.expanduser("~/.cache/reel")
W, H, FPS = 1080, 1920, 30
MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2"


def sh(cmd, **kw):
    return subprocess.run(cmd, check=True, **kw)


def wpath(n):
    os.makedirs(WORK, exist_ok=True)
    return os.path.join(WORK, n)


# ----------------------------------------------------------------- setup
def setup():
    try:
        import sherpa_onnx, soundfile, PIL, numpy  # noqa
    except ImportError:
        sh([sys.executable, "-m", "pip", "install", "-q", "sherpa-onnx", "soundfile", "numpy", "pillow"])
    os.makedirs(CACHE, exist_ok=True)
    if not glob.glob(CACHE + "/sherpa*/small-encoder.onnx"):
        arc = CACHE + "/w.tar.bz2"
        sh(["curl", "-sSL", "-o", arc, MODEL_URL])
        sh(["tar", "xjf", arc, "-C", CACHE])
        os.remove(arc)
    if not font_path("Black"):
        print("WARN: шрифт Inter не найден, будет DejaVu")
    print("setup ok")


def font_path(style):
    for p in [f"/usr/share/fonts/opentype/inter/Inter-{style}.otf",
              os.path.join(CACHE, f"Inter-{style}.otf")]:
        if os.path.exists(p):
            return p
    alt = {"Black": "DejaVuSans-Bold", "ExtraBold": "DejaVuSans-Bold", "SemiBold": "DejaVuSans"}[style]
    for p in glob.glob(f"/usr/share/fonts/**/{alt}.ttf", recursive=True):
        return p
    return None


# ----------------------------------------------------------------- transcribe
def vad_segments(a, sr):
    import numpy as np
    fl = sr // 50
    n = len(a) // fl
    e = np.array([np.sqrt(np.mean(a[i * fl:(i + 1) * fl] ** 2)) for i in range(n)])
    thr = max(0.01, np.percentile(e, 20) * 2)
    segs, start, q = [], None, 0
    for i, v in enumerate(e > thr):
        t = i * fl / sr
        if v:
            start = t if start is None else start
            q = 0
        else:
            q += 1
            if start is not None and (q >= 12 or (q >= 4 and t - start > 3.5) or t - start > 6):
                segs.append((start, t))
                start = None
    if start is not None:
        segs.append((start, n * fl / sr))
    return [(s, e) for s, e in segs if e - s >= 0.25]


def transcribe(src, lang="ru"):
    import sherpa_onnx, soundfile as sf
    d = glob.glob(CACHE + "/sherpa*")
    if not d:
        setup()
        d = glob.glob(CACHE + "/sherpa*")
    d = d[0]
    wav = wpath("a16.wav")
    sh(["ffmpeg", "-v", "error", "-y", "-i", src, "-ac", "1", "-ar", "16000", wav])
    r = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=d + "/small-encoder.onnx", decoder=d + "/small-decoder.onnx",
        tokens=d + "/small-tokens.txt", language=lang, task="transcribe", num_threads=os.cpu_count() or 4)
    a, sr = sf.read(wav, dtype="float32")
    vad = vad_segments(a, sr)
    json.dump(vad, open(wpath("vad.json"), "w"))
    chunks = []
    for s, e in vad:  # фразы до 9 с: Whisper точнее с контекстом
        if chunks and e - chunks[-1][0] <= 9 and s - chunks[-1][1] < 1.0:
            chunks[-1][1] = e
        else:
            chunks.append([s, e])
    lines = []
    for s, e in chunks:
        st = r.create_stream()
        st.accept_waveform(sr, a[int(s * sr):int(e * sr)])
        r.decode_stream(st)
        txt = st.result.text.strip()
        lines.append(f"{s:.2f}-{e:.2f} | {txt}")
    open(wpath("transcript.txt"), "w").write(
        "# время-время | текст. Правь только текст (можно пустым — кусок вырежется; можно переносить слова между строками).\n"
        + "\n".join(lines) + "\n")
    print(open(wpath("transcript.txt")).read())


def read_transcript():
    segs = []
    for ln in open(wpath("transcript.txt")):
        m = re.match(r"\s*([\d.]+)-([\d.]+)\s*\|\s*(.*)", ln)
        if m:
            segs.append((float(m[1]), float(m[2]), m[3].strip()))
    return segs


# ----------------------------------------------------------------- plan
DEFAULT = {
    "pad": 0.09, "merge_gap": 0.18, "fit": "cover",
    "music": True, "bpm": 124, "music_db": -21, "sfx_db": -6,
    "accent": "#FFD60A", "accent2": "#7C5CFF",
    "caption_y": 1150, "caption_size": 96, "caption_max_words": 3, "caption_max_chars": 15,
    "emphasis": [], "cards": [],
}


def norm(w):
    return re.sub(r"[^\wё$%+-]", "", w.lower()).strip("-")


def auto_plan():
    segs = read_transcript()
    words = [w for _, _, t in segs for w in t.split()]
    emph = sorted({norm(w) for w in words if re.search(r"\d", w) or (w[:1].isupper() and len(w) > 3 and w not in words[:1])})
    cards = []
    nums = [w for w in words if re.search(r"\d", w)]
    for w in nums[:4]:
        cards.append({"type": "pill", "at": norm(w), "text": w.strip(",.!?").upper(), "color": "accent", "pos": "top", "until": "+1.8"})
    if words:
        cards.insert(0, {"type": "title", "at": "@0", "until": "+3", "kicker": "", "lines": [" ".join(words[:3]).upper().strip(",."), " ".join(words[3:5]).upper().strip(",.")]})
        cards.append({"type": "end", "at": "@-1.5", "lines": ["ПОДПИШИСЬ", "НА КАНАЛ"]})
    p = dict(DEFAULT, emphasis=emph[:12], cards=cards)
    json.dump(p, open(wpath("plan.json"), "w"), ensure_ascii=False, indent=1)
    print(open(wpath("plan.json")).read())


# ----------------------------------------------------------------- timeline
class TL:
    def __init__(self, plan):
        self.segs = [s for s in read_transcript() if s[2]]
        pad, gap = plan["pad"], plan["merge_gap"]
        vad = json.load(open(wpath("vad.json"))) if os.path.exists(wpath("vad.json")) else []
        voiced = []  # участки речи внутри каждой строки
        for s, e, _ in self.segs:
            v = [(max(a, s), min(b, e)) for a, b in vad if b > s and a < e]
            voiced.append(v or [(s, e)])
        iv = []
        for v in voiced:
            for s, e in v:
                s, e = max(0, s - pad), e + pad
                if iv and s - iv[-1][1] < gap:
                    iv[-1][1] = max(iv[-1][1], e)
                else:
                    iv.append([s, e])
        self.iv = iv
        self.dur = sum(e - s for s, e in iv)
        self.words = []
        for si, ((s, e, txt), v) in enumerate(zip(self.segs, voiced)):
            ws = txt.split()
            L = [len(w) + 2 for w in ws]
            tot = sum(L)
            vt = sum(b - a for a, b in v)
            pos = 0.0
            for w, l in zip(ws, L):
                t0, t1 = self.vmap(v, pos / tot * vt), self.vmap(v, (pos + l) / tot * vt)
                self.words.append(dict(w=w, n=norm(w), s=self.map(t0), e=self.map(t1), seg=si))
                pos += l
        self.emph = set()

    @staticmethod
    def vmap(v, x):  # x секунд речи -> время исходника
        for a, b in v:
            if x <= b - a:
                return a + x
            x -= b - a
        return v[-1][1]

    def map(self, t):
        acc = 0
        for s, e in self.iv:
            if t < s:
                return acc
            if t <= e:
                return acc + t - s
            acc += e - s
        return acc

    def find(self, anchor, ref=None, end=False, after=-1.0):
        """якорь -> время в смонтированном ролике"""
        a = str(anchor).strip()
        if a.startswith("+") and ref is not None:
            return ref + float(a[1:])
        if a.startswith("@"):
            v = float(a[1:])
            return self.dur + v if v < 0 else self.map(v)
        k = 1
        if "#" in a:
            a, k = a.rsplit("#", 1)
            k = int(k)
        toks = [norm(x) for x in a.split() if norm(x)]
        hit = 0
        for i in range(len(self.words)):
            if self.words[i]["s"] < after - 0.01:
                continue
            if all(i + j < len(self.words) and self.words[i + j]["n"].startswith(t) for j, t in enumerate(toks)):
                hit += 1
                if hit == k:
                    return self.words[i + len(toks) - 1]["e"] if end else self.words[i]["s"]
        if after > 0:
            return self.find(anchor, ref, end)
        raise SystemExit(f"якорь не найден: {anchor!r}")


# ----------------------------------------------------------------- media
def prepare(src, tl, plan):
    n = len(tl.iv)
    parts = []
    for i, (s, e) in enumerate(tl.iv):
        parts.append(f"[0:v]trim={s:.3f}:{e:.3f},setpts=PTS-STARTPTS[v{i}];"
                     f"[0:a]atrim={s:.3f}:{e:.3f},asetpts=PTS-STARTPTS,afade=t=in:d=0.02,"
                     f"afade=t=out:st={max(0, e - s - 0.02):.3f}:d=0.02[a{i}]")
    if plan["fit"] == "blur":
        vf = ("split[f][b];[b]scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,boxblur=30:3,eq=brightness=-0.08[bg];"
              "[f]scale=1080:1920:force_original_aspect_ratio=decrease:flags=lanczos[fg];[bg][fg]overlay=(W-w)/2:(H-h)/2")
    else:
        vf = "scale=1080:1920:force_original_aspect_ratio=increase:flags=lanczos,crop=1080:1920"
    fc = (";".join(parts) + ";" + "".join(f"[v{i}][a{i}]" for i in range(n)) +
          f"concat=n={n}:v=1:a=1[cv][ca];[cv]fps={FPS},{vf},unsharp=5:5:0.8,eq=contrast=1.06:saturation=1.18[vo];"
          "[ca]highpass=f=80,acompressor=threshold=-20dB:ratio=3:attack=5:release=80,loudnorm=I=-14:TP=-1.5,aresample=48000[ao]")
    open(wpath("fc.txt"), "w").write(fc)
    sh(["ffmpeg", "-v", "error", "-y", "-i", src, "-filter_complex_script", wpath("fc.txt"),
        "-map", "[vo]", "-map", "[ao]", "-c:v", "libx264", "-crf", "14", "-preset", "veryfast",
        "-c:a", "pcm_s16le", wpath("cut.mkv")])
    out = subprocess.run(["ffmpeg", "-v", "info", "-i", wpath("cut.mkv"), "-vf", "select='gt(scene,0.25)',showinfo",
                          "-f", "null", "-"], capture_output=True, text=True).stderr
    cuts = [float(x) for x in re.findall(r"pts_time:([\d.]+)", out)]
    return [c for i, c in enumerate(cuts) if i == 0 or c - cuts[i - 1] > 0.4]


# ----------------------------------------------------------------- render
def hexrgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def run_render(src, out, preview=False):
    import numpy as np
    from PIL import Image, ImageDraw, ImageFont, ImageFilter
    plan = dict(DEFAULT, **json.load(open(wpath("plan.json"))))
    tl = TL(plan)
    cuts = prepare(src, tl, plan)
    WORDS, DUR = tl.words, tl.dur
    Y, VIO = hexrgb(plan["accent"]), hexrgb(plan["accent2"])
    RED, GRN, WH, INK, GREY = (255, 59, 48), (52, 199, 89), (255, 255, 255), (12, 12, 16), (170, 170, 185)
    COL = {"accent": (Y, INK), "violet": (VIO, WH), "accent2": (VIO, WH), "red": (RED, WH), "green": (GRN, WH),
           "white": (WH, INK), "dark": ((20, 20, 26), WH), "pink": ((225, 48, 108), WH)}
    FB, FX, FS = font_path("Black"), font_path("ExtraBold"), font_path("SemiBold")
    POS = {"top": 330, "upper": 430, "middle": 800, "bottom": 1460}
    _fc = {}

    def font(p, s):
        if (p, s) not in _fc:
            _fc[(p, s)] = ImageFont.truetype(p, s)
        return _fc[(p, s)]

    clamp = lambda x, a=0, b=1: min(b, max(a, x))
    ease = lambda x: 1 - (1 - clamp(x)) ** 3

    def back(x):
        x = clamp(x)
        return 1 + 2.70158 * (x - 1) ** 3 + 1.70158 * (x - 1) ** 2

    emph = {norm(e) for e in plan["emphasis"]}
    EMPH = {i for i, w in enumerate(WORDS) if any(w["n"].startswith(e) for e in emph if e)}

    # --- группы субтитров
    groups, cur = [], []
    mw, mc = plan["caption_max_words"], plan["caption_max_chars"]
    for i, w in enumerate(WORDS):
        cur.append(i)
        txt = " ".join(WORDS[j]["w"] for j in cur)
        nx = WORDS[i + 1] if i + 1 < len(WORDS) else None
        if (len(cur) >= mw or len(txt) >= mc or re.search(r"[.,!?—:;]$", w["w"]) or nx is None
                or (nx["seg"] != w["seg"] and nx["s"] - w["e"] > 0.15) or len(txt) + len(nx["w"]) > mc + 2):
            groups.append(cur)
            cur = []
    G = []
    for gi, g in enumerate(groups):
        s, e = WORDS[g[0]]["s"], WORDS[g[-1]]["e"]
        if gi + 1 < len(groups):
            e = min(max(e, s + 0.3), WORDS[groups[gi + 1][0]]["s"])
        G.append(dict(ix=g, s=s, e=e + 0.05))
    capcache = {}
    CS = plan["caption_size"]

    def cap_sprite(gi, act):
        if (gi, act) in capcache:
            return capcache[(gi, act)]
        g = G[gi]
        f = font(FB, CS)
        words = [WORDS[i]["w"].upper() for i in g["ix"]]
        sp = CS // 4
        ws = [f.getbbox(w)[2] for w in words]
        im = Image.new("RGBA", (sum(ws) + sp * (len(ws) - 1) + 120, int(CS * 2.3)), (0, 0, 0, 0))
        shd = Image.new("RGBA", im.size, (0, 0, 0, 0))
        sd = ImageDraw.Draw(shd)
        x = 60
        for w, ww in zip(words, ws):
            sd.text((x + 4, int(CS * .73)), w, font=f, fill=(0, 0, 0, 200))
            x += ww + sp
        im.alpha_composite(shd.filter(ImageFilter.GaussianBlur(10)))
        d = ImageDraw.Draw(im)
        x = 60
        for j, (w, ww) in enumerate(zip(words, ws)):
            y = int(CS * .65)
            if j == act:
                hot = g["ix"][j] in EMPH
                d.rounded_rectangle((x - 18, y - 6, x + ww + 18, y + int(CS * 1.35)), radius=26, fill=(Y if hot else VIO) + (255,))
                d.text((x, y), w, font=f, fill=INK if hot else WH)
            else:
                d.text((x, y), w, font=f, fill=WH, stroke_width=7, stroke_fill=INK)
            x += ww + sp
        capcache[(gi, act)] = im
        return im

    def put(fr, im, cx, cy, sc=1.0, alpha=1.0, dx=0, dy=0, rot=0):
        if alpha <= .01 or sc <= .02:
            return
        if sc != 1:
            im = im.resize((max(1, int(im.width * sc)), max(1, int(im.height * sc))), Image.BILINEAR)
        if rot:
            im = im.rotate(rot, resample=Image.BICUBIC, expand=True)
        if alpha < 1:
            im = im.copy()
            im.putalpha(im.getchannel("A").point(lambda v: int(v * alpha)))
        fr.alpha_composite(im, (int(cx - im.width / 2 + dx), int(cy - im.height / 2 + dy)))

    def draw_caption(fr, t):
        for gi, g in enumerate(G):
            if g["s"] <= t < g["e"]:
                act = max([j for j, i in enumerate(g["ix"]) if t >= WORDS[i]["s"]] or [0])
                im = cap_sprite(gi, act)
                p = (t - g["s"]) / .16
                sc = .72 + .28 * back(p) if p < 1 else 1.0
                q = (t - WORDS[g["ix"][act]]["s"]) / .12
                if act > 0 and q < 1:
                    sc *= 1 + .04 * (1 - q)
                iw = im.width * sc
                if iw > W - 40:
                    sc *= (W - 40) / iw
                put(fr, im, W / 2, plan["caption_y"], sc=sc)
                return

    sprites = {}

    def pill(text, color="accent", size=60):
        key = (text, color, size)
        if key not in sprites:
            bg, fg = COL.get(color, (hexrgb(color), WH) if str(color).startswith("#") else COL["accent"])
            f = font(FB, size)
            w, h = f.getbbox(text)[2] + 72, size + 46
            im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
            d = ImageDraw.Draw(im)
            d.rounded_rectangle((0, 0, w - 1, h - 1), radius=h // 2, fill=bg + (255,))
            d.text((36, 18), text, font=f, fill=fg)
            sprites[key] = im
        return sprites[key]

    def panel(sz, a=220):
        im = Image.new("RGBA", sz, (0, 0, 0, 0))
        ImageDraw.Draw(im).rounded_rectangle((0, 0, sz[0] - 1, sz[1] - 1), radius=44, fill=(14, 14, 20, a), outline=(255, 255, 255, 40), width=2)
        return im

    cursor = Image.new("RGBA", (90, 120), (0, 0, 0, 0))
    ImageDraw.Draw(cursor).polygon([(8, 6), (8, 96), (32, 74), (50, 112), (66, 104), (48, 68), (80, 66)], fill=WH, outline=INK, width=5)

    # --- разрешаем якоря
    SFX = [(0.02, "hit")] + [(c - .12, "whoosh") for c in cuts]
    CARDS = []
    prev = -1.0  # якоря ищутся по порядку: после начала предыдущей карточки

    def seq(items):
        out, p = [], prev
        for it in items:
            t = tl.find(it[1], after=p)
            out.append((it[0], t) + tuple(it[2:]))
            p = t
        return out

    for c in plan["cards"]:
        c = dict(c)
        typ = c["type"]
        if typ in ("checklist", "tags", "platforms"):
            c["its"] = seq(c["items"])
            c["t0"] = tl.find(c["at"], after=prev) if "at" in c else c["its"][0][1] - (.12 if typ != "platforms" else 0)
        else:
            c["t0"] = tl.find(c.get("at", "@0"), after=prev)
        if typ == "compare":
            c["tr"] = tl.find(c["reveal"], after=c["t0"])
            c["tf"] = tl.find(c["final_at"], after=c["tr"]) if c.get("final_at") else None
        prev = max(0.0, c["t0"])
        CARDS.append(c)
    for ci, c in enumerate(CARDS):
        nxt = [d["t0"] - .2 for d in CARDS[ci + 1:] if d.get("pos", DEF_POS(d)) == c.get("pos", DEF_POS(c)) and d["t0"] > c["t0"]]
        if c["type"] == "end":
            c["t1"] = DUR + 1
        elif "until" in c:
            c["t1"] = min([tl.find(c["until"], ref=c["t0"], end=True, after=c["t0"])] + nxt)
        else:
            c["t1"] = min([c["t0"] + 2.5] + nxt)
        typ = c["type"]
        SFX.append((c["t0"], {"title": "hit", "end": "hit", "button": "click", "compare": "hit"}.get(typ, "pop")))
        for it in c.get("its", []):
            SFX.append((it[1], "pop"))
        if typ == "compare":
            SFX += [(c["tr"], "hit"), (c["tr"] + .05, "ding")]

    def life(t, t0, t1, fin=.25, fout=.2):
        if t < t0 or t > t1 + fout:
            return None
        return clamp((t - t0) / fin), clamp((t1 + fout - t) / fout)

    def check(d, x, y, s, p):
        a, b, c2 = (x, y + s * .5), (x + s * .38, y + s * .85), (x + s, y + s * .1)
        p1 = clamp(p / .4)
        if p > 0:
            d.line([a, (a[0] + (b[0] - a[0]) * p1, a[1] + (b[1] - a[1]) * p1)], fill=WH, width=14)
        if p > .4:
            p2 = clamp((p - .4) / .6)
            d.line([b, (b[0] + (c2[0] - b[0]) * p2, b[1] + (c2[1] - b[1]) * p2)], fill=WH, width=14)

    def draw_card(fr, c, t):
        L = life(t, c["t0"], c["t1"])
        if not L:
            return
        a, o = L
        typ, y = c["type"], POS.get(c.get("pos", DEF_POS(c)), 330)
        if typ == "title":
            key = ("title", tuple(c["lines"]), c.get("kicker", ""))
            if key not in sprites:
                lines = [l for l in c["lines"] if l]
                fh = 82 if max(len(l) for l in lines) <= 18 else 64
                hh = 60 + (60 if c.get("kicker") else 0) + len(lines) * (fh + 10) + 40
                im = panel((980, hh))
                d = ImageDraw.Draw(im)
                d.rectangle((0, 40, 14, hh - 40), fill=Y + (255,))
                yy = 36
                if c.get("kicker"):
                    d.text((60, yy), c["kicker"], font=font(FX, 42), fill=Y)
                    yy += 60
                for l in lines:
                    d.text((60, yy), l, font=font(FB, fh), fill=WH)
                    yy += fh + 10
                sprites[key] = im
            put(fr, sprites[key], W / 2, y, sc=.9 + .1 * back(a), alpha=min(a, o), dx=-(1 - ease(a)) * 300)
        elif typ == "pill":
            put(fr, pill(c["text"], c.get("color", "accent"), c.get("size", 60)), W / 2, y, sc=back(a), alpha=o,
                rot=(1 - ease(a)) * -8 + c.get("tilt", 0))
        elif typ == "checklist":
            n = len(c["its"])
            im = panel((680, 70 + n * 105))
            d = ImageDraw.Draw(im)
            for i, (txt, t0) in enumerate(c["its"]):
                p = clamp((t - t0) / .35)
                yy = 40 + i * 105
                d.rounded_rectangle((36, yy, 106, yy + 70), radius=18, fill=(GRN if p > 0 else (60, 60, 70)) + (255,))
                check(d, 50, yy + 12, 44, p)
                d.text((136, yy + 2), txt, font=font(FX, 58), fill=WH if p > 0 else (140, 140, 150))
            put(fr, im, W / 2, y + 30, sc=.85 + .15 * ease(a), alpha=min(a, o), dy=(1 - ease(a)) * 80)
        elif typ == "tags":
            cols = ["accent", "white", "violet"]
            x0 = x = 90
            yy = y - 130
            for i, (txt, t0) in enumerate(c["its"]):
                k = pill(txt, cols[i % 3], 50)
                if x + k.width > W - 90:
                    x, yy = x0, yy + k.height + 22
                p = (t - t0) / .22
                if p > 0:
                    put(fr, k, x + k.width / 2, yy + k.height / 2, sc=back(p), alpha=o, rot=[-4, 3, -2, 4, -3, 2][i % 6])
                x += k.width + 22
        elif typ == "button":
            done = t > c["t0"] + .9
            k = pill(c.get("done", "✓ ГОТОВО") if done else c.get("text", "ПОДПИСАТЬСЯ"), "dark" if done else "red", 64)
            q = t - (c["t0"] + .75)
            press = 1 - .12 * math.sin(q / .25 * math.pi) if 0 < q < .25 else 1
            put(fr, k, W / 2, y, sc=back(a) * press, alpha=o)
            if t < c["t0"] + 1.2:
                cp = ease((t - c["t0"]) / .7)
                put(fr, cursor, W / 2 + 300 - 200 * cp, y + 110 - 90 * cp, sc=press, alpha=o)
        elif typ == "compare":
            im = panel((900, 420))
            d = ImageDraw.Draw(im)
            l, r = c["left"], c["right"]
            f = font(FB, 150)
            d.text((50, 36), l[0], font=font(FX, 46), fill=GREY)
            d.text((50, 92), l[1], font=f, fill=WH)
            d.text((50, 262), l[2] if len(l) > 2 else "", font=font(FS, 44), fill=GREY)
            if t >= c["tr"] - .05:
                p = ease((t - c["tr"] + .05) / .25)
                bw = f.getbbox(l[1])[2]
                d.line((40, 190, 40 + (bw + 30) * p, 170), fill=RED, width=16)
                fin = c["tf"] is not None and t >= c["tf"]
                big = c.get("final", r[1]) if fin else r[1]
                sz = 120 if fin and len(big) > 3 else int(150 * (.6 + .4 * back((t - c["tr"]) / .25)))
                d.text((470, 36), r[0], font=font(FX, 46), fill=GRN)
                d.text((470, 92 + (150 - sz) // 2), big, font=font(FB, sz), fill=GRN)
                d.text((470, 262), r[2] if len(r) > 2 else "", font=font(FS, 44), fill=GREY)
            put(fr, im, W / 2, y, sc=.8 + .2 * back(a), alpha=o)
        elif typ == "platforms":
            n = len(c["its"])
            for i, (txt, t0, col) in enumerate(c["its"]):
                if t >= t0:
                    k = pill(txt, col, 62)
                    put(fr, k, W / 2 + (i - (n - 1) / 2) * 440, y, sc=back((t - t0) / .8), alpha=o)
        elif typ == "end":
            key = ("end", tuple(c["lines"]))
            if key not in sprites:
                im = panel((900, 110 + 110 * len(c["lines"])), 230)
                d = ImageDraw.Draw(im)
                for i, l in enumerate(c["lines"]):
                    d.text((60, 40 + i * 110), l, font=font(FB, 100 if len(l) <= 12 else 72), fill=Y if i == len(c["lines"]) - 1 else WH)
                sprites[key] = im
            put(fr, sprites[key], W / 2, y, sc=.7 + .3 * back(a), alpha=a)

    # --- оверлеи и камера
    yy = np.linspace(0, 1, H)[:, None]
    A = np.repeat((np.clip((yy - .45) / .55, 0, 1) ** 1.6 * 170 + np.clip((.16 - yy) / .16, 0, 1) * 90).astype(np.uint8), W, axis=1)
    SHADE = Image.fromarray(np.dstack([np.zeros((H, W, 3), np.uint8), A]), "RGBA")
    WHITE = Image.new("RGBA", (W, H), (255, 255, 255, 255))
    estarts = [WORDS[i]["s"] for i in EMPH]

    def zoom(t):
        z = 1.03 + .025 * math.sin(t * .9)
        for c in cuts:
            if 0 <= t - c < .35:
                z += .10 * (1 - ease((t - c) / .35))
        for s in estarts:
            if 0 <= t - s < .5:
                z += .05 * math.sin((t - s) / .5 * math.pi)
        return z

    ptimes = None
    if preview:
        ct = sorted({round(min(c["t0"] + .6, DUR - .1), 2) for c in CARDS}) or [round(DUR * k / 6, 2) for k in range(1, 6)]
        ptimes = [ct[round(i * (len(ct) - 1) / 9)] for i in range(10)] if len(ct) > 10 else ct
        ptimes = sorted(set(ptimes))
    dec = subprocess.Popen(["ffmpeg", "-v", "error", "-i", wpath("cut.mkv"), "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
    enc = None
    if not preview:
        enc = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", f"{W}x{H}", "-r", str(FPS),
                                "-i", "-", "-c:v", "libx264", "-preset", "medium", "-crf", "17", "-pix_fmt", "yuv420p", wpath("vid.mp4")], stdin=subprocess.PIPE)
    shots, n = [], 0
    while True:
        raw = dec.stdout.read(W * H * 3)
        if len(raw) < W * H * 3:
            break
        t = n / FPS
        n += 1
        if preview and min(abs(t - p) for p in ptimes) > .017:
            continue
        fr = Image.frombuffer("RGB", (W, H), raw).convert("RGBA")
        z = zoom(t)
        cw, ch = W / z, H / z
        x0 = clamp((W - cw) / 2 + math.sin(t * .7) * 6, 0, W - cw)
        y0 = (H - ch) * .42
        fr = fr.resize((W, H), Image.BILINEAR, box=(x0, y0, x0 + cw, y0 + ch))
        fr.alpha_composite(SHADE)
        for c in cuts:
            if 0 <= t - c < .12:
                fr = Image.blend(fr, WHITE, .22 * (1 - (t - c) / .12))
                break
        ImageDraw.Draw(fr).rectangle((0, 0, int(W * t / DUR), 9), fill=Y + (255,))
        for c in CARDS:
            draw_card(fr, c, t)
        draw_caption(fr, t)
        if preview:
            shots.append(fr.convert("RGB").resize((270, 480)))
        else:
            enc.stdin.write(fr.tobytes())
    dec.wait()
    if preview:
        sheet = Image.new("RGB", (270 * 5, 480 * ((len(shots) + 4) // 5)))
        for i, s in enumerate(shots):
            sheet.paste(s, ((i % 5) * 270, (i // 5) * 480))
        sheet.save(wpath("sheet.jpg"), quality=80)
        print("preview:", wpath("sheet.jpg"), "times:", ptimes)
        return
    enc.stdin.close()
    enc.wait()
    make_audio(SFX, DUR, plan)
    m = plan["music"]
    fc = ("[1:a]asplit=2[v][sc];[2:a]volume={md}dB[m];[m][sc]sidechaincompress=threshold=0.03:ratio=6:attack=20:release=300[mm];"
          "[3:a]volume={sd}dB[f];[v][mm][f]amix=inputs=3:normalize=0:duration=first,alimiter=limit=0.89[a]").format(
        md=plan["music_db"] if m else -90, sd=plan["sfx_db"])
    sh(["ffmpeg", "-v", "error", "-y", "-i", wpath("vid.mp4"), "-i", wpath("cut.mkv"), "-i", wpath("mus.wav"), "-i", wpath("fx.wav"),
        "-filter_complex", fc, "-map", "0:v", "-map", "[a]", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
        "-movflags", "+faststart", "-shortest", out])
    print(f"OK {out}  {DUR:.1f} c  карточек {len(CARDS)}  склеек {len(cuts)}")


def DEF_POS(c):
    return {"title": "top", "pill": "top", "checklist": "bottom", "tags": "bottom", "button": "bottom",
            "compare": "upper", "platforms": "bottom", "end": "upper"}.get(c["type"], "top")


# ----------------------------------------------------------------- audio
def make_audio(sfx, D, plan):
    import numpy as np, soundfile as sf
    SR = 48000
    N = int((D + .5) * SR)
    rng = np.random.default_rng(7)

    def env(n, a=.005, r=None):
        t = np.arange(n) / SR
        e = np.minimum(1, t / a)
        return e * np.exp(-t / r) if r else e

    def sweep(n, f0, k, f1):
        t = np.arange(n) / SR
        return np.sin(2 * np.pi * np.cumsum(f0 * np.exp(-t * k) + f1) / SR)

    def whoosh():
        n = int(.4 * SR)
        x = rng.standard_normal(n)
        t = np.arange(n) / n
        y = np.zeros(n)
        acc = 0.0
        k = .02 + .25 * np.sin(np.pi * t) ** 2
        for i in range(n):
            acc += k[i] * (x[i] - acc)
            y[i] = acc
        return y * np.sin(np.pi * t) ** 2 * .5

    n9 = int(.09 * SR)
    pop = sweep(n9, 900, 30, 250) * env(n9, .001, .03) * .5
    n5 = int(.05 * SR)
    click = rng.standard_normal(n5) * env(n5, .0005, .006) * .5 + pop[:n5] * .4
    n7 = int(.7 * SR)
    hit = (sweep(n7, 110, 8, 45) * env(n7, .002, .25) + rng.standard_normal(n7) * env(n7, .001, .02) * .3) * .8
    n8 = int(.8 * SR)
    t8 = np.arange(n8) / SR
    ding = (np.sin(2 * np.pi * 1318 * t8) + .5 * np.sin(2 * np.pi * 1975 * t8)) * env(n8, .002, .18) * .18
    LIB = {"whoosh": whoosh() * .35, "pop": pop * .45, "click": click * .6, "hit": hit * .55, "ding": ding * .6}
    fx = np.zeros(N)
    for t, k in sfx:
        i = int(max(0, t) * SR)
        s = LIB[k]
        j = min(N, i + len(s))
        if i < N:
            fx[i:j] += s[:j - i]
    beat = 60 / plan["bpm"]
    mus = np.zeros(N)
    nk = int(.35 * SR)
    kick = sweep(nk, 150, 25, 48) * env(nk, .001, .12)
    hn = int(.04 * SR)
    hat = np.diff(np.r_[0, rng.standard_normal(hn)]) * env(hn, .0005, .008) * .25
    roots = [55.0, 43.65, 65.41, 49.0]
    b, t = 0, 0.0

    def add(arr, i, s):
        if i < N:
            j = min(N, i + len(s))
            arr[i:j] += s[:j - i]

    while t < D:
        add(mus, int(t * SR), kick)
        add(mus, int((t + .5 * beat) * SR), hat)
        bn = int(beat * .45 * SR)
        bt = np.arange(bn) / SR
        f = roots[(b // 8) % 4]
        add(mus, int((t + .5 * beat) * SR), (np.sin(2 * np.pi * f * bt) + .3 * np.sin(4 * np.pi * f * bt)) * env(bn, .005, .12) * .5)
        b += 1
        t += beat
    fe = int(1.2 * SR)
    mus[-fe:] *= np.linspace(1, 0, fe)
    sf.write(wpath("fx.wav"), np.clip(fx, -1, 1).astype(np.float32), SR)
    sf.write(wpath("mus.wav"), (mus / (np.max(np.abs(mus)) + 1e-9) * .8).astype(np.float32), SR)


# ----------------------------------------------------------------- main
if __name__ == "__main__":
    a = sys.argv[1:]
    if not a:
        print(__doc__)
    elif a[0] == "setup":
        setup()
    elif a[0] == "transcribe":
        transcribe(a[1], a[2] if len(a) > 2 else "ru")
    elif a[0] == "plan":
        auto_plan()
    elif a[0] == "preview":
        run_render(a[1], None, preview=True)
    elif a[0] == "render":
        run_render(a[1], a[2])
    elif a[0] == "auto":
        setup()
        transcribe(a[1])
        auto_plan()
        run_render(a[1], a[2])
    else:
        print(__doc__)
