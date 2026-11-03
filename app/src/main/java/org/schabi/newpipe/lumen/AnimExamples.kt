/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

/** A ready animation of the wizard: works without AI and shows the AI what a good lumen.anim v1 answer looks like. */
data class AnimExample(val id: String, val type: String, val ru: String, val en: String, val code: String)

/** Built-in lumen.anim v1 examples (checked in Chromium on the same prelude and harness as the app). */
object AnimExamples {
    /** Helpers every example carries: the plate of the brief, the font, caps, the entrance and exit, the moment a word is said. */
    val COMMON = """
function plate(ctx, W, H, d) {
  var b = d.brief || {}, bg = b.bg || 'semi', a = b.bgAlpha == null ? 0.7 : b.bgAlpha;
  if (bg === 'none') return false;
  ctx.save(); ctx.globalAlpha = bg === 'full' ? 1 : a; ctx.fillStyle = d.card || '#101318';
  if (bg === 'full') ctx.fillRect(0, 0, W, H); else { roundRect(ctx, 0, 0, W, H, Math.min(W, H) * (b.radius === 'none' ? 0 : b.radius === 's' ? 0.06 : b.radius === 'l' ? 0.22 : 0.12)); ctx.fill(); }
  ctx.restore(); return true;
}
function fnt(d, wt, size) { var f = (d.brief && d.brief.font) || d.font || 'sans'; var fam = f === 'serif' ? 'serif' : f === 'mono' ? 'monospace' : f === 'condensed' ? 'sans-serif-condensed' : 'sans-serif'; return wt + ' ' + Math.round(size) + 'px ' + fam; }
function up(d, s) { return d.brief && d.brief.upper ? String(s).toUpperCase() : String(s); }
function appear(t, d) { var b = d.brief || {}, a = 1; if (b.enter === 'code') a *= ease.out(prog(t, 0, 0.4)); if (b.exit === 'code') a *= 1 - prog(t, d.dur - 0.35, d.dur); return a; }
function sayAt(d, word, fallback) { var ws = d.words || [], w = String(word || '').toLowerCase().replace(/[^0-9a-zа-яё%]/gi, ''); if (!w) return fallback; for (var i = 0; i < ws.length; i++) { var x = ws[i].w.toLowerCase().replace(/[^0-9a-zа-яё%]/gi, ''); if (x && (x === w || x.indexOf(w) === 0 || w.indexOf(x) === 0)) return ws[i].s; } return fallback; }
""".trim()

    private val NUMBER = """
// name: Счётчик числа
// число досчитывает до значения, подпись снизу
function setup(d) {
  var f = (d.brief && d.brief.fields) || {}, it = (d.brief && d.brief.items) || [];
  var v = String(f.value || (it[0] && it[0].value) || '100').replace(',', '.');
  d.target = parseFloat(v.replace(/[^0-9.\-]/g, '')) || 0;
  d.dec = (v.split('.')[1] || '').length;
  d.from = parseFloat(String(f.from || '0').replace(',', '.')) || 0;
  d.unit = f.unit || (it[0] && it[0].unit) || '';
  d.label = up(d, f.label || (it[0] && it[0].label) || d.title || '');
  d.at = sayAt(d, f.value, 0.1);
}
function frame(t, ctx, W, H, d) {
  var a = appear(t, d); if (a <= 0) return false;
  ctx.globalAlpha = a; plate(ctx, W, H, d);
  var u = Math.min(W, H), k = ease.out(prog(t, d.at, d.at + 1.2));
  var n = lerp(d.from, d.target, k), s = n.toFixed(d.dec).replace('.', d.lang === 'English' ? '.' : ',');
  var txt = s + (d.unit === '%' ? '%' : '');
  var size = fitFont(ctx, txt, W * 0.86, Math.max(u * 0.42, (d.brief.minFont || 0) * 2), 900);
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  var pop = 1 + 0.08 * Math.sin(Math.PI * prog(t, d.at + 1.1, d.at + 1.5));
  ctx.save(); ctx.translate(W / 2, H * (d.label ? 0.42 : 0.5)); ctx.scale(pop, pop);
  ctx.font = fnt(d, 900, size); ctx.fillStyle = d.accent; ctx.fillText(txt, 0, 0); ctx.restore();
  var sub = [d.unit && d.unit !== '%' ? d.unit : '', d.label].filter(Boolean).join(' ');
  if (sub) {
    var ls = fitFont(ctx, sub, W * 0.86, Math.max(size * 0.32, d.brief.minFont || 0), 700);
    ctx.globalAlpha = a * ease.out(prog(t, d.at + 0.3, d.at + 0.8));
    ctx.font = fnt(d, 700, ls); ctx.fillStyle = d.ink; ctx.fillText(up(d, sub), W / 2, H * 0.42 + size * 0.62);
  }
}
""".trim()

    private val BARS = """
// name: Растущие столбцы
// столбцы растут по очереди или на своих словах, главный — акцентом
function setup(d) {
  var it = (d.brief && d.brief.items) || [];
  if (!it.length) it = [{ label: 'A', value: '40' }, { label: 'B', value: '75' }, { label: 'C', value: '55' }];
  d.bars = it.slice(0, 6).map(function (x, i) { return { label: up(d, x.label || ''), v: parseFloat(String(x.value).replace(',', '.').replace(/[^0-9.\-]/g, '')) || 0, raw: (x.value || '') + (x.unit === '%' ? '%' : ''), main: !!x.main, at: 0 }; });
  if (d.brief.fields && d.brief.fields.sort === 'desc') d.bars.sort(function (a, b) { return b.v - a.v; });
  var max = 0; d.bars.forEach(function (b) { max = Math.max(max, b.v); }); d.max = max || 1;
  var step = Math.max(0.25, Math.min(0.7, (d.dur - 1.8) / d.bars.length));
  d.bars.forEach(function (b, i) { b.at = d.brief.reveal === 'all' ? 0.2 : d.brief.reveal === 'words' ? sayAt(d, b.label, 0.2 + i * step) : 0.2 + i * step; });
  if (!d.bars.some(function (b) { return b.main; })) { var m = 0; d.bars.forEach(function (b, i) { if (b.v > d.bars[m].v) m = i; }); d.bars[m].main = true; }
}
function frame(t, ctx, W, H, d) {
  var a = appear(t, d); if (a <= 0) return false;
  ctx.globalAlpha = a; plate(ctx, W, H, d);
  var n = d.bars.length, padX = W * 0.08, top = H * (d.title ? 0.2 : 0.1), base = H * 0.78, gap = (W - padX * 2) / n, bw = gap * 0.6;
  var fs = Math.max(d.brief.minFont || 0, Math.min(gap * 0.28, H * 0.07));
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  if (d.title) { ctx.font = fnt(d, 800, fitFont(ctx, d.title, W * 0.86, fs * 1.3, 800)); ctx.fillStyle = d.ink; ctx.fillText(up(d, d.title), W / 2, H * 0.1); }
  ctx.fillStyle = d.ink; ctx.globalAlpha = a * 0.35; ctx.fillRect(padX * 0.6, base, W - padX * 1.2, Math.max(2, H * 0.006)); ctx.globalAlpha = a;
  for (var i = 0; i < n; i++) {
    var b = d.bars[i], k = ease.back(prog(t, b.at, b.at + 0.6)); if (k <= 0) continue;
    var x = padX + gap * i + (gap - bw) / 2, bh = (base - top - fs * 1.6) * Math.max(0.04, b.v / d.max) * Math.min(1, k);
    ctx.fillStyle = b.main ? d.accent : 'rgba(255,255,255,0.55)';
    if (d.brief.palette === 'bright' && !b.main) ctx.fillStyle = ['#4FC3F7', '#81C784', '#FFB74D', '#F06292', '#BA68C8'][i % 5];
    roundRect(ctx, x, base - bh, bw, bh, bw * 0.16); ctx.fill();
    ctx.globalAlpha = a * prog(t, b.at + 0.3, b.at + 0.6);
    ctx.font = fnt(d, 900, fs); ctx.fillStyle = b.main ? d.accent : d.ink; ctx.fillText(b.raw, x + bw / 2, base - bh - fs * 0.8);
    ctx.font = fnt(d, 600, fitFont(ctx, b.label, gap * 0.95, fs * 0.85, 600)); ctx.fillStyle = d.ink; ctx.fillText(b.label, x + bw / 2, base + fs * 0.9);
    ctx.globalAlpha = a;
  }
}
""".trim()

    private val RING = """
// name: Кольцо прогресса
// кольцо заполняется от from до to, число в центре
function setup(d) {
  var f = d.brief.fields || {}, it = d.brief.items || [];
  function num(s, z) { var v = parseFloat(String(s == null ? '' : s).replace(',', '.').replace(/[^0-9.\-]/g, '')); return isNaN(v) ? z : v; }
  d.p0 = num(f.from, 0); d.p1 = num(f.to || f.value || (it[0] && it[0].value), 75);
  d.label = up(d, f.label || (it[0] && it[0].label) || d.title || '');
}
function frame(t, ctx, W, H, d) {
  var a = appear(t, d); if (a <= 0) return false;
  ctx.globalAlpha = a; plate(ctx, W, H, d);
  var r = Math.min(W, H) * (d.label ? 0.3 : 0.36), cx = W / 2, cy = H * (d.label ? 0.42 : 0.5), lw = r * 0.22;
  var k = ease.inOut(prog(t, 0.2, Math.min(d.dur - 0.6, 2.0))), p = lerp(d.p0, d.p1, k);
  ctx.lineCap = 'round'; ctx.lineWidth = lw;
  ctx.strokeStyle = 'rgba(255,255,255,0.18)'; ctx.beginPath(); ctx.arc(cx, cy, r, 0, Math.PI * 2); ctx.stroke();
  ctx.strokeStyle = d.accent; ctx.beginPath(); ctx.arc(cx, cy, r, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * clamp(p / 100, 0, 1)); ctx.stroke();
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; ctx.fillStyle = d.ink;
  var s = Math.round(p) + '%'; ctx.font = fnt(d, 900, fitFont(ctx, s, r * 1.3, r * 0.62, 900)); ctx.fillText(s, cx, cy);
  if (d.label) { ctx.font = fnt(d, 700, fitFont(ctx, d.label, W * 0.86, Math.max(r * 0.3, d.brief.minFont || 0), 700)); ctx.fillText(d.label, cx, cy + r + lw * 1.6); }
}
""".trim()

    private val CHECKLIST = """
// name: Список с галочками
// пункты появляются по очереди, галочка рисуется штрихом
function setup(d) {
  var it = d.brief.items || [];
  if (!it.length) it = (d.wish || 'Первый пункт\nВторой пункт\nТретий пункт').split(/\n|;/).map(function (s) { return { label: s.trim() }; }).filter(function (x) { return x.label; });
  d.rows = it.slice(0, 6).map(function (x) { return { label: up(d, (x.icon ? x.icon + ' ' : '') + x.label + (x.value ? ' — ' + x.value + (x.unit ? ' ' + x.unit : '') : '')), bad: /^[✗✕❌×-]/.test(x.icon || '') }; });
  var step = Math.max(0.3, Math.min(0.8, (d.dur - 1.6) / Math.max(1, d.rows.length)));
  d.rows.forEach(function (r, i) { r.at = d.brief.reveal === 'all' ? 0.15 : d.brief.reveal === 'words' ? sayAt(d, r.label.split(' ').pop(), 0.2 + i * step) : 0.2 + i * step; });
}
function frame(t, ctx, W, H, d) {
  var a = appear(t, d); if (a <= 0) return false;
  ctx.globalAlpha = a; plate(ctx, W, H, d);
  var n = d.rows.length, top = H * (d.title ? 0.24 : 0.1), rh = (H * 0.9 - top) / Math.max(1, n), fs = Math.max(d.brief.minFont || 0, Math.min(rh * 0.42, W * 0.07));
  ctx.textBaseline = 'middle';
  if (d.title) { ctx.textAlign = 'center'; ctx.fillStyle = d.ink; ctx.font = fnt(d, 900, fitFont(ctx, up(d, d.title), W * 0.86, fs * 1.2, 900)); ctx.fillText(up(d, d.title), W / 2, H * 0.12); }
  ctx.textAlign = 'left';
  for (var i = 0; i < n; i++) {
    var r = d.rows[i], k = prog(t, r.at, r.at + 0.45); if (k <= 0) continue;
    var y = top + rh * (i + 0.5), x0 = W * 0.08 - (1 - ease.out(k)) * W * 0.1, s = fs * 1.1;
    ctx.globalAlpha = a * ease.out(k);
    ctx.fillStyle = r.bad ? '#FF5252' : d.accent; ctx.beginPath(); ctx.arc(x0 + s / 2, y, s / 2, 0, Math.PI * 2); ctx.fill();
    var c = prog(t, r.at + 0.15, r.at + 0.5); ctx.strokeStyle = '#111'; ctx.lineWidth = s * 0.14; ctx.lineCap = 'round'; ctx.beginPath();
    if (r.bad) { ctx.moveTo(x0 + s * 0.32, y - s * 0.18); ctx.lineTo(x0 + s * 0.32 + s * 0.36 * c, y - s * 0.18 + s * 0.36 * c); }
    else { ctx.moveTo(x0 + s * 0.27, y); ctx.lineTo(x0 + s * 0.27 + s * 0.16 * Math.min(1, c * 2), y + s * 0.16 * Math.min(1, c * 2)); if (c > 0.5) ctx.lineTo(x0 + s * 0.43 + s * 0.32 * (c - 0.5) * 2, y + s * 0.16 - s * 0.36 * (c - 0.5) * 2); }
    ctx.stroke();
    ctx.fillStyle = d.ink; ctx.font = fnt(d, 700, fitFont(ctx, r.label, W * 0.78, fs, 700)); ctx.fillText(r.label, x0 + s * 1.4, y);
  }
  ctx.globalAlpha = 1;
}
""".trim()

    private val POINTER = """
// name: Стрелка-указатель
// стрелка рисуется к точке, вокруг точки пульсирует круг, подпись в плашке
function setup(d) {
  var tg = d.brief.target || { x: 0.62, y: 0.4 };
  d.tx = tg.x; d.ty = tg.y;
  d.label = up(d, (d.brief.fields && d.brief.fields.label) || d.title || d.wish || '');
}
function frame(t, ctx, W, H, d) {
  var a = appear(t, d); if (a <= 0) return false;
  ctx.globalAlpha = a;
  var u = Math.min(W, H), tx = d.tx * W, ty = d.ty * H;
  var lx = tx < W / 2 ? Math.min(W * 0.8, tx + u * 0.38) : Math.max(W * 0.2, tx - u * 0.38), ly = ty < H / 2 ? Math.min(H * 0.8, ty + u * 0.3) : Math.max(H * 0.15, ty - u * 0.3);
  var k = ease.out(prog(t, 0.15, 0.75)), ex = lerp(lx, tx, k * 0.86), ey = lerp(ly, ty, k * 0.86);
  ctx.strokeStyle = d.accent; ctx.lineWidth = u * 0.016; ctx.lineCap = 'round'; ctx.shadowColor = 'rgba(0,0,0,0.5)'; ctx.shadowBlur = u * 0.02;
  ctx.beginPath(); ctx.moveTo(lx, ly); ctx.lineTo(ex, ey); ctx.stroke();
  if (k > 0.95) { var ang = Math.atan2(ey - ly, ex - lx), hl = u * 0.05; ctx.beginPath(); ctx.moveTo(ex, ey); ctx.lineTo(ex - hl * Math.cos(ang - 0.5), ey - hl * Math.sin(ang - 0.5)); ctx.moveTo(ex, ey); ctx.lineTo(ex - hl * Math.cos(ang + 0.5), ey - hl * Math.sin(ang + 0.5)); ctx.stroke(); }
  var p = prog(t, 0.6, 1.0), pulse = 1 + 0.12 * Math.sin(t * 6);
  if (p > 0) { ctx.globalAlpha = a * p; ctx.beginPath(); ctx.arc(tx, ty, u * 0.07 * pulse, 0, Math.PI * 2); ctx.stroke(); }
  ctx.shadowBlur = 0;
  if (d.label) {
    var fs = Math.max(d.brief.minFont || 0, u * 0.05); ctx.font = fnt(d, 800, fs);
    var tw = Math.min(W * 0.7, ctx.measureText(d.label).width) + fs * 1.2, th = fs * 1.7;
    var bx = clamp(lx - tw / 2, W * 0.03, W * 0.97 - tw), by = clamp(ly + (ly > ty ? fs * 0.4 : -th - fs * 0.4), H * 0.03, H * 0.97 - th);
    ctx.globalAlpha = a * ease.out(prog(t, 0.05, 0.4)); ctx.fillStyle = d.card; roundRect(ctx, bx, by, tw, th, th / 2); ctx.fill();
    ctx.fillStyle = d.ink; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; ctx.font = fnt(d, 800, fitFont(ctx, d.label, tw - fs, fs, 800)); ctx.fillText(d.label, bx + tw / 2, by + th / 2);
  }
}
""".trim()

    private val KINETIC = """
// name: Кинетический титр
// слова фразы выскакивают в такт речи, слова-акценты крупнее и цветом
function setup(d) {
  var f = d.brief.fields || {}, ws = d.words || [];
  var phrase = f.phrase || ws.map(function (w) { return w.w; }).join(' ') || d.title || d.wish || 'Главная мысль';
  var stress = String(f.stress || '').toLowerCase().split(/[ ,;]+/).filter(Boolean);
  var list = phrase.split(/\s+/).filter(Boolean).slice(0, 14), step = Math.max(0.12, Math.min(0.4, (d.dur - 1.5) / Math.max(1, list.length)));
  d.ws = list.map(function (w, i) { var c = w.toLowerCase().replace(/[^0-9a-zа-яё%]/gi, ''); return { w: up(d, w), big: stress.indexOf(c) >= 0 || (!stress.length && (c.length > 6 || /\d/.test(c))), at: sayAt(d, w, 0.15 + i * step) }; });
  for (var i = 1; i < d.ws.length; i++) if (d.ws[i].at < d.ws[i - 1].at) d.ws[i].at = d.ws[i - 1].at + 0.05;
}
function frame(t, ctx, W, H, d) {
  var a = appear(t, d); if (a <= 0) return false;
  ctx.globalAlpha = a; plate(ctx, W, H, d);
  var base = Math.max(d.brief.minFont || 0, Math.min(W, H) * 0.14), lines = [], line = [], lw = 0, maxW = W * 0.88;
  ctx.textBaseline = 'middle';
  for (var i = 0; i < d.ws.length; i++) {
    var w = d.ws[i], s = base * (w.big ? 1.35 : 1); ctx.font = fnt(d, w.big ? 900 : 800, s); var ww = ctx.measureText(w.w + ' ').width;
    if (lw + ww > maxW && line.length) { lines.push({ ws: line, w: lw }); line = []; lw = 0; }
    line.push({ w: w, s: s, ww: ww }); lw += ww;
  }
  if (line.length) lines.push({ ws: line, w: lw });
  var lh = base * 1.45, y0 = H / 2 - (lines.length - 1) * lh / 2;
  ctx.shadowColor = 'rgba(0,0,0,0.55)'; ctx.shadowBlur = d.brief.bg === 'none' ? base * 0.18 : 0;
  for (var l = 0; l < lines.length; l++) {
    var x = (W - lines[l].w) / 2, y = y0 + l * lh;
    for (var j = 0; j < lines[l].ws.length; j++) {
      var q = lines[l].ws[j], k = prog(t, q.w.at, q.w.at + 0.28);
      if (k > 0) {
        var sc = ease.back(k); ctx.save(); ctx.globalAlpha = a * Math.min(1, k * 2); ctx.translate(x + q.ww / 2, y); ctx.scale(sc, sc);
        ctx.font = fnt(d, q.w.big ? 900 : 800, q.s); ctx.textAlign = 'center'; ctx.fillStyle = q.w.big ? d.accent : d.ink; ctx.fillText(q.w.w, 0, 0); ctx.restore();
      }
      x += q.ww;
    }
  }
}
""".trim()

    val ALL = listOf(
        AnimExample("number", "number", "Счётчик числа", "Counter", "// name: Счётчик числа\n" + COMMON + "\n" + NUMBER),
        AnimExample("bars", "chart", "Растущие столбцы", "Growing bars", "// name: Растущие столбцы\n" + COMMON + "\n" + BARS),
        AnimExample("ring", "progress", "Кольцо прогресса", "Progress ring", "// name: Кольцо прогресса\n" + COMMON + "\n" + RING),
        AnimExample("checklist", "list", "Список с галочками", "Checklist", "// name: Список с галочками\n" + COMMON + "\n" + CHECKLIST),
        AnimExample("pointer", "pointer", "Стрелка-указатель", "Pointer arrow", "// name: Стрелка-указатель\n" + COMMON + "\n" + POINTER),
        AnimExample("kinetic", "kinetic", "Кинетический титр", "Kinetic title", "// name: Кинетический титр\n" + COMMON + "\n" + KINETIC)
    )

    fun forType(type: String): AnimExample? = ALL.firstOrNull { it.type == type }
}
