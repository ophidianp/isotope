"""Isotope logo: "isotope" written as one connected piece with a flat, horizontal pen nib
(thick strokes on the downstrokes, hairlines on the joins), swept along smooth Bezier
centrelines and sheared forward 44 degrees. Same technique as the Mouse 33 logo
(mouse33/brand/logo_gen.py, kept here as logo_gen_m33.py).

The i has no separate dot: the t's crossbar runs back over "iso" as one long hairline and
carries a broad nib dab where the i's dot would be, so the whole word stays one polygon.

Letters are drawn in an upright design space with vertical stems. Like the Mouse 33 wave,
the downstrokes drift forward a little (LEAN) before the 44 degree shear, so the finished
stems sit near 32 degrees, the same angle as the M33 stems."""
import sys, json
import numpy as np
from shapely.geometry import Polygon, MultiPolygon
from shapely.ops import unary_union
from shapely import affinity

# design-space lines (y grows downwards)
A, X, B, D = 1.1, 10.0, 30.0, 38.0      # ascender, x-height, baseline, descender
LEAN = 0.34                              # forward drift of the downstrokes (M33: 9.2 / 27)
BAR = 4.8                                # height of the long t crossbar

def bez(p0, p1, p2, p3, n=56):
    t = np.linspace(0, 1, n)[:, None]
    p0, p1, p2, p3 = map(np.array, (p0, p1, p2, p3))
    return (1-t)**3*p0 + 3*(1-t)**2*t*p1 + 3*(1-t)*t**2*p2 + t**3*p3

def stroke(curves, w, t):
    pts = np.vstack([bez(*c) for c in curves])
    nib = np.array([[-w/2, -t/2], [w/2, -t/2], [w/2, t/2], [-w/2, t/2]])
    return unary_union([Polygon(np.vstack([nib + a, nib + b])).convex_hull for a, b in zip(pts[:-1], pts[1:])])

def to_path(g, nd=2):
    out = []
    for p in (g.geoms if isinstance(g, MultiPolygon) else [g]):
        for ring in [p.exterior, *p.interiors]:
            c = list(ring.coords)[:-1]
            out.append("M" + " L".join(f"{x:.{nd}f},{y:.{nd}f}" for x, y in c) + "Z")
    return "".join(out)

def oval(cx, rx):
    """an o centreline, from its top, anticlockwise, back to its top."""
    k = rx * 1.33
    return [((cx, X), (cx - k, X), (cx - k, B), (cx, B)),
            ((cx, B), (cx + k, B), (cx + k, X), (cx, X))]

def word_curves():
    """centreline of the main pen path: i s o t o p e, never lifting."""
    c = []
    i = 7.0
    # i: entry hairline up to the x-height, stem down, out along the baseline
    c += [((0, B), (3.5, B), (i, X + 7), (i, X)),
          ((i, X), (i, X + 8), (i, B), (i + 4, B))]
    # s: up to its point, belly swings right and under, curls back, exits
    s = i + 11
    c += [((i + 4, B), (i + 8, B), (s - 1.5, X + 5), (s, X)),
          ((s, X), (s + 3, X + 3.5), (s + 8.5, X + 9), (s + 7.5, B - 4.5)),
          ((s + 7.5, B - 4.5), (s + 6.8, B), (s + 1, B + 0.4), (s - 2.8, B - 2.6)),
          ((s - 2.8, B - 2.6), (s - 0.5, B - 0.6), (s + 6, B - 0.4), (s + 9.5, B - 3))]
    # o: rise to the top, round anticlockwise, flick out at the x-height
    o = s + 19
    c += [((s + 9.5, B - 3), (s + 12, B - 5), (o - 3, X), (o, X))]
    c += oval(o, 5.6)
    t = o + 12
    c += [((o, X), (o + 2, X + 1.6), (o + 5, X + 1.6), (t - 2.2, X - 0.6)),
          # t: climb to the ascender, retrace the stem down, out along the baseline
          ((t - 2.2, X - 0.6), (t - 0.6, X - 2), (t, A + 4), (t, A)),
          ((t, A), (t, A + 10), (t, B), (t + 4, B))]
    # second o
    o2 = t + 13
    c += [((t + 4, B), (t + 7, B), (o2 - 4, X + 1), (o2, X))]
    c += oval(o2, 5.6)
    # p: stem to the descender, back up, bowl to the right closing on the stem
    p = o2 + 11
    c += [((o2, X), (o2 + 2, X + 1.6), (p - 4, X + 1.6), (p - 1.6, X - 0.4)),
          ((p - 1.6, X - 0.4), (p - 0.4, X + 0.4), (p, X + 1), (p, X + 3)),
          ((p, X + 3), (p, X + 12), (p, D - 4), (p, D)),
          ((p, D), (p, D - 8), (p, X + 8), (p + 1.5, X + 3)),
          ((p + 1.5, X + 3), (p + 4, X - 0.6), (p + 10, X), (p + 10, X + 9)),
          ((p + 10, X + 9), (p + 10, B - 2), (p + 5, B), (p + 1.5, B - 1.5))]
    # e: from the p's bowl, up into the loop, round and out with a rising tail
    e = p + 18
    c += [((p + 1.5, B - 1.5), (p + 6, B - 1.2), (e - 1, X + 12), (e + 4.5, X + 8)),
          ((e + 4.5, X + 8), (e + 6.5, X + 5.5), (e + 4, X), (e + 1, X)),
          ((e + 1, X), (e - 5.5, X), (e - 6, B), (e, B)),
          ((e, B), (e + 4, B), (e + 7, B - 2), (e + 10, B - 6))]
    return c, i, t

def build(skew=44.0, W=5.6, T=2.2, lean=LEAN, mark_only=False):
    curves, ix, tx = word_curves()
    if mark_only:
        # the favicon mark: the i with its crossbar dab, as a dotted-i monogram
        main = stroke(curves[:2], W, T)
        dot = stroke([((ix - 0.4, BAR - 2.2), (ix - 0.2, BAR - 1), (ix + 0.2, BAR + 0.6), (ix + 0.4, BAR + 1.8))], W, T)
        g = unary_union([main, dot])
    else:
        main = stroke(curves, W, T)
        # the t crossbar runs back over "iso"; one broad dab on it is the i's dot
        bar = stroke([((ix - 3.5, BAR + 0.6), (ix + 10, BAR + 0.2), (tx - 6, BAR), (tx + 7, BAR - 0.8))], W, T)
        dab = stroke([((ix - 0.4, BAR - 2.2), (ix - 0.2, BAR - 1), (ix + 0.2, BAR + 0.6), (ix + 0.4, BAR + 1.8))], W, T)
        g = unary_union([main, bar, dab])
    g = affinity.skew(g, xs=np.degrees(np.arctan(lean)), origin=(0, B))   # downstrokes drift forward
    g = affinity.skew(g, xs=-skew, origin=(0, B)).simplify(0.035)
    minx, miny, maxx, maxy = g.bounds
    g = affinity.translate(g, -minx, -miny)
    return g, (maxx - minx, maxy - miny)

if __name__ == "__main__":
    g, (w, h) = build(mark_only="mark" in sys.argv[1:])
    print(json.dumps({"w": round(w, 2), "h": round(h, 2), "parts": len(getattr(g, "geoms", [g])),
                      "holes": sum(len(p.interiors) for p in getattr(g, "geoms", [g]))}))
