"""Writes the Isotope logo files: logo.svg (wordmark), favicon.svg (the i mark on a violet
tile), apple-touch-icon.png and og.png, into the site root, plus logo.json (path data that
is pasted into each page's inline <symbol id="iso">).
Run from anywhere:  python -I brand/make_assets.py"""
import os, sys, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from PIL import Image, ImageDraw, ImageFont
from shapely.geometry import MultiPolygon
from logo_gen import build, to_path

HERE = os.path.dirname(os.path.abspath(__file__)); SITE = os.path.join(HERE, "..")
VIOLET, VIOLET_D, INK, PAPER = "#5B3CC4", "#B79CF0", "#111111", "#FFFFFF"
word, (ww, wh) = build()
mark, (mw, mh) = build(mark_only=True)
D_WORD, D_MARK = to_path(word), to_path(mark)
pad = 0.6

def svg_word(fill=VIOLET):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{-pad} {-pad} {ww+2*pad:.2f} {wh+2*pad:.2f}">'
            f'<title>Isotope</title><path fill="{fill}" d="{D_WORD}"/></svg>\n')

def svg_icon():
    s = 22 / max(mw, mh); ox, oy = (32 - mw*s)/2, (32 - mh*s)/2
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="8" fill="{VIOLET}"/>'
            f'<path fill="#fff" transform="translate({ox:.2f} {oy:.2f}) scale({s:.4f})" d="{D_MARK}"/></svg>\n')

def raster(g, scale, offset, img, color, bg):
    d = ImageDraw.Draw(img)
    for p in (g.geoms if isinstance(g, MultiPolygon) else [g]):
        d.polygon([(offset[0] + x*scale, offset[1] + y*scale) for x, y in p.exterior.coords], fill=color)
        for ring in p.interiors:
            d.polygon([(offset[0] + x*scale, offset[1] + y*scale) for x, y in ring.coords], fill=bg)

def touch_icon(n=180, ss=4):
    N = n*ss; img = Image.new("RGB", (N, N), VIOLET)
    s = N*0.6 / max(mw, mh)
    raster(mark, s, ((N - mw*s)/2, (N - mh*s)/2), img, "#ffffff", VIOLET)
    return img.resize((n, n), Image.LANCZOS)

def font(names, size):
    for n in names:
        for d in (r"C:\Windows\Fonts", "/usr/share/fonts/truetype/dejavu", "/Library/Fonts"):
            p = os.path.join(d, n)
            if os.path.exists(p):
                return ImageFont.truetype(p, size)
    return ImageFont.load_default()

def og(ss=2):
    W, H = 1200*ss, 630*ss; img = Image.new("RGB", (W, H), INK)
    s = 820*ss / ww
    raster(word, s, ((W - ww*s)/2, 150*ss), img, VIOLET_D, INK)
    d = ImageDraw.Draw(img)
    mono = font(["consola.ttf", "DejaVuSansMono.ttf", "Menlo.ttc"], 26*ss)
    sans = font(["segoeui.ttf", "DejaVuSans.ttf", "Helvetica.ttc"], 40*ss)
    line = "Same mod. Different number."
    tw = d.textlength(line, font=sans); d.text(((W - tw)/2, 470*ss), line, font=sans, fill="#F2EFE6")
    sub = "FABRIC  ·  MINECRAFT 26.1 – 26.3  ·  FORWARDS AND BACKWARDS"
    tw = d.textlength(sub, font=mono); d.text(((W - tw)/2, 540*ss), sub, font=mono, fill="#8D8A80")
    return img.resize((1200, 630), Image.LANCZOS)

if __name__ == "__main__":
    for name, body in (("logo.svg", svg_word()), ("favicon.svg", svg_icon())):
        open(os.path.join(SITE, name), "w", newline="\n").write(body)
    open(os.path.join(HERE, "logo.svg"), "w", newline="\n").write(svg_word())
    open(os.path.join(HERE, "logo-white.svg"), "w", newline="\n").write(svg_word("#ffffff"))
    touch_icon().save(os.path.join(SITE, "apple-touch-icon.png"), optimize=True)
    og().save(os.path.join(SITE, "og.png"), optimize=True)
    parts = len(getattr(word, "geoms", [word]))
    json.dump({"word": D_WORD, "w": round(ww+2*pad, 2), "h": round(wh+2*pad, 2), "pad": pad, "mark": D_MARK, "parts": parts},
              open(os.path.join(HERE, "logo.json"), "w"))
    print("word", round(ww, 2), round(wh, 2), len(D_WORD), "chars, parts", parts, "; mark", round(mw, 2), round(mh, 2))
