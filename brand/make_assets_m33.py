"""Writes the Mouse 33 logo files: logo.svg (wordmark), favicon.svg (M mark on a blue tile),
apple-touch-icon.png and og.png. Final look: 44 deg slant, 3s +12 deg, M-to-33 gap 1."""
import os, sys, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from PIL import Image, ImageDraw
from shapely.geometry import MultiPolygon
from shapely import affinity
from logo_gen import build, to_path

HERE = os.path.dirname(os.path.abspath(__file__)); SITE = os.path.join(HERE, "..", "site")
BLUE, BLUE_D, INK, PAPER = "#1530A8", "#5872F2", "#0A0B0F", "#FFFFFF"
word, (ww, wh) = build(44, 12, mgap=1.0)
mark, (mw, mh) = build(44, 12, mark_only=True)
D_WORD, D_MARK = to_path(word), to_path(mark)
pad = 0.6

def svg_word(fill=BLUE):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{-pad} {-pad} {ww+2*pad:.2f} {wh+2*pad:.2f}">'
            f'<title>Mouse 33</title><path fill="{fill}" d="{D_WORD}"/></svg>\n')
def svg_icon():
    s = 22 / max(mw, mh); ox, oy = (32 - mw*s)/2, (32 - mh*s)/2
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="8" fill="{BLUE}"/>'
            f'<path fill="#fff" transform="translate({ox:.2f} {oy:.2f}) scale({s:.4f})" d="{D_MARK}"/></svg>\n')

def raster(g, size, scale, offset, img, color):
    """draw shapely geometry into a PIL image (already supersampled)."""
    d = ImageDraw.Draw(img)
    for p in (g.geoms if isinstance(g, MultiPolygon) else [g]):
        ext = [(offset[0] + x*scale, offset[1] + y*scale) for x, y in p.exterior.coords]
        d.polygon(ext, fill=color)
        for ring in p.interiors:
            d.polygon([(offset[0] + x*scale, offset[1] + y*scale) for x, y in ring.coords], fill=img.getpixel((0, 0)))

def touch_icon(n=180, ss=4):
    N = n*ss; img = Image.new("RGB", (N, N), BLUE)
    s = N*0.62 / max(mw, mh)
    raster(mark, N, s, ((N - mw*s)/2, (N - mh*s)/2), img, "#ffffff")
    return img.resize((n, n), Image.LANCZOS)

def og(ss=2):
    W, H = 1200*ss, 630*ss; img = Image.new("RGB", (W, H), "#F4F5F8")
    hero = Image.open(os.path.join(SITE, "img", "exploded_34.webp")).convert("RGBA")
    k = (H*0.98) / hero.height; hero = hero.resize((int(hero.width*k), int(hero.height*k)), Image.LANCZOS)
    img.paste(hero, (W - hero.width + int(60*ss), int(H*0.01)), hero)
    s = 470*ss / ww
    raster(word, W, s, (80*ss, 210*ss), img, BLUE)
    return img.resize((1200, 630), Image.LANCZOS)

if __name__ == "__main__":
    open(os.path.join(HERE, "logo.svg"), "w").write(svg_word())
    open(os.path.join(HERE, "logo-white.svg"), "w").write(svg_word("#ffffff"))
    open(os.path.join(SITE, "logo.svg"), "w").write(svg_word())
    open(os.path.join(SITE, "favicon.svg"), "w").write(svg_icon())
    touch_icon().save(os.path.join(SITE, "apple-touch-icon.png"))
    og().save(os.path.join(SITE, "og.png"), optimize=True)
    json.dump({"word": D_WORD, "w": round(ww+2*pad, 2), "h": round(wh+2*pad, 2), "pad": pad, "mark": D_MARK}, open(os.path.join(HERE, "logo.json"), "w"))
    print("word", round(ww, 2), round(wh, 2), len(D_WORD), "chars; mark", round(mw, 2), round(mh, 2))
