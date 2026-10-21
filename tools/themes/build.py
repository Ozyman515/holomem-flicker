"""Turn card-art color clusters into app themes, and render a swatch sheet for review."""
import colorsys, json, sys
from PIL import Image, ImageDraw, ImageFont

src, overrides_path, out_json, out_png = sys.argv[1:5]
pal = json.load(open(src))
overrides = json.load(open(overrides_path))

def hls(c): return colorsys.rgb_to_hls(*[v / 255 for v in c[:3]])
def hexo(h, l, s):
    r, g, b = colorsys.hls_to_rgb(h, max(0, min(1, l)), max(0, min(1, s)))
    return "#%02X%02X%02X" % (round(r * 255), round(g * 255), round(b * 255))

def is_skin(h, l, s):
    deg = h * 360
    return (deg < 45 or deg > 340) and l > 0.55 and s < 0.75

def score(c):
    h, l, s = hls(c)
    if s < 0.18 or l < 0.12 or l > 0.92: return -1
    if is_skin(h, l, s): return -1
    return s * (c[3] ** 0.5) * (1 - abs(l - 0.5))

def hue_dist(a, b):
    d = abs(a - b) % 1
    return min(d, 1 - d) * 360

themes = {}
for cid, m in pal.items():
    o = overrides.get(m["short"], {})
    if "primary" in o:
        themes[cid] = dict(name=m["name"], short=m["short"], group=m["sub"], **overrides[m["short"]])
        continue
    ranked = sorted(m["pal"], key=score, reverse=True)
    p = ranked[0]
    ph, pl, ps = hls(p)
    sec = next((c for c in ranked[1:] if score(c) > 0 and hue_dist(hls(c)[0], ph) > 35), None)
    if sec is None:  # fall back to a split-complement
        sh, ss = (ph + 0.42) % 1, max(ps, 0.55)
    else:
        sh, _, ss = hls(sec); ss = max(ss, 0.5)
    ps = max(ps, 0.45)
    if "ph" in o: ph, ps = o["ph"] / 360, max(ps, o.get("ps", 0.6))
    if "ah" in o: sh, ss = o["ah"] / 360, o.get("as", 0.6)
    themes[cid] = dict(
        name=m["name"], short=m["short"], group=m["sub"],
        primary=hexo(ph, 0.40, ps),        # buttons
        deep=hexo(ph, 0.22, ps * 0.9),     # header / banners
        soft=hexo(ph, 0.55, ps * 0.85),    # secondary buttons
        bg=hexo(ph, 0.92, min(ps, 0.45)),  # page background
        line=hexo(ph, 0.80, min(ps, 0.40)),# card borders
        card=hexo(ph, 0.975, 0.5),         # cards
        accent=hexo(sh, 0.58, ss),         # start / save / sliders
        alert=hexo(sh, 0.36, ss),          # stop / selected
        night=hexo(ph, 0.09, ps * 0.6),    # dark screens
        auto=not o,
    )

json.dump(themes, open(out_json, "w"), indent=1)

# Swatch sheet
keys = ["primary", "deep", "soft", "bg", "card", "accent", "alert", "night"]
W, rowh = 1100, 46
img = Image.new("RGB", (W, 40 + rowh * len(themes)), "#ffffff")
d = ImageDraw.Draw(img)
try:
    f = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 15)
except Exception:
    f = ImageFont.load_default()
for i, k in enumerate(keys):
    d.text((230 + i * 105, 12), k, fill="#000", font=f)
for r, (cid, t) in enumerate(themes.items()):
    y = 40 + r * rowh
    d.text((10, y + 12), ("* " if not t.get("auto") else "") + t["short"] + "  " + t["group"], fill="#000", font=f)
    for i, k in enumerate(keys):
        d.rectangle([230 + i * 105, y + 4, 325 + i * 105, y + rowh - 4], fill=t[k], outline="#888")
img.save(out_png)
print(len(themes), "themes")
