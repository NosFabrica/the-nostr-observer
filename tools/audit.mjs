// A WCAG pass over a rendered page: every element that paints text, its
// effective background composed through transparency, contrast, size and
// weight, judged against AA -- 4.5:1, relaxed to 3:1 for large text.
//
// The same rule `Proof` applies to every generated edition, in a form you can
// point at a file while you are working on a stylesheet. `Proof` reports the
// worst offender and stops an edition; this lists all of them.
//
//   node tools/audit.mjs page.html=LABEL another.html=LABEL
//
// Needs playwright and a chromium: PW browsers live at /opt/pw-browsers here.
import { chromium } from 'playwright';


// A real WCAG pass over every element that paints text: effective background
// composed through transparency, contrast ratio, size and weight, judged
// against AA (4.5:1, or 3:1 for large text).
const AUDIT = () => {
  const lum = (c) => {
    const [r, g, b] = c.map((v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  };
  const parse = (s) => {
    const n = (s.match(/[\d.]+/g) || []).map(Number);
    return n.length >= 3 ? { rgb: n.slice(0, 3), a: n.length > 3 ? n[3] : 1 } : null;
  };
  const over = (fg, bg) => fg.rgb.map((c, i) => c * fg.a + bg[i] * (1 - fg.a));
  const ratio = (a, b) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };

  const bgOf = (el) => {
    let node = el, stack = [];
    while (node) {
      const c = parse(getComputedStyle(node).backgroundColor);
      if (c && c.a > 0) { stack.push(c); if (c.a === 1) break; }
      node = node.parentElement;
    }
    let base = [255, 255, 255];
    for (const layer of stack.reverse()) base = over(layer, base);
    return base;
  };

  const out = [];
  for (const el of document.querySelectorAll('*')) {
    const own = [...el.childNodes].filter((n) => n.nodeType === 3 && n.textContent.trim()).map((n) => n.textContent.trim()).join(' ');
    if (!own) continue;
    const s = getComputedStyle(el);
    if (s.visibility === 'hidden' || s.display === 'none' || +s.opacity === 0) continue;
    const box = el.getBoundingClientRect();
    if (!box.width || !box.height) continue;
    const fg = parse(s.color);
    if (!fg) continue;
    const bg = bgOf(el);
    const size = parseFloat(s.fontSize);
    const weight = +s.fontWeight || 400;
    const large = size >= 24 || (size >= 18.66 && weight >= 700);
    out.push({
      tag: el.tagName.toLowerCase(),
      cls: (el.className && String(el.className).split(' ')[0]) || '',
      text: own.slice(0, 42),
      size: Math.round(size * 10) / 10,
      weight,
      ratio: Math.round(ratio(over(fg, bg), bg) * 100) / 100,
      need: large ? 3 : 4.5,
      color: s.color,
    });
  }
  return out;
};

const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium' });
for (const [file, label] of process.argv.slice(2).map((a) => a.split('='))) {
  for (const scheme of ['dark', 'light']) {
    const p = await b.newPage({ viewport: { width: 1000, height: 900 }, colorScheme: scheme });
    await p.route('**/*', (r) => (r.request().resourceType() === 'image' ? r.abort() : r.continue()));
    await p.goto('file://' + process.cwd() + '/' + file);
    await p.waitForTimeout(600);
    const rows = await p.evaluate(AUDIT);
    const bad = rows.filter((r) => r.ratio < r.need);
    const tiny = rows.filter((r) => r.size < 12);
    console.log(`\n=== ${label} · ${scheme} — ${rows.length} text elements, ${bad.length} below AA, ${tiny.length} under 12px`);
    const seen = new Set();
    for (const r of bad.sort((x, y) => x.ratio - y.ratio)) {
      const key = r.tag + '.' + r.cls + r.size + r.color;
      if (seen.has(key)) continue;
      seen.add(key);
      console.log(`  ${String(r.ratio).padStart(5)} : ${r.need}  ${r.size}px/${r.weight}  ${(r.tag + (r.cls ? '.' + r.cls : '')).padEnd(22)} ${r.color.padEnd(22)} "${r.text}"`);
    }
    for (const r of tiny) { const k = 'tiny' + r.tag + r.cls + r.size; if (!seen.has(k)) { seen.add(k); console.log(`  SMALL ${r.size}px  ${r.tag}${r.cls ? '.' + r.cls : ''}  "${r.text}"`); } }
    await p.close();
  }
}
await b.close();
