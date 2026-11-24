# Isotope Website

This branch hosts the Isotope website, deployed with GitHub Pages from the
repository root to https://ophidianp.github.io/isotope/.

## Pages
- **Home** — `index.html`
- **How it works** — `how-it-works.html`
- **Compatibility** — `compatibility.html` (real test results, limits, other projects)
- **Download** — `downloads.html` (newest GitHub Release, loaded live)
- **404** — `404.html`

Shared assets: `assets/site.css` (the whole design: the Mouse 33 layout and motion,
recoloured to Isotope's violet, light and dark with a toggle), `assets/site.js` (theme,
header, mobile menu, FAQ, the pinned launch stage, first-visit preloader) and
`assets/releases.js` (download data).

The logo is the one-piece "isotope" wordmark made by `brand/logo_gen.py` (a flat pen nib
swept along Bezier curves and sheared 44 degrees, the same technique as the Mouse 33 logo).
`python -I brand/make_assets.py` rewrites `logo.svg`, `favicon.svg`,
`apple-touch-icon.png`, `og.png` and `brand/logo.json`; the path in `logo.json` is the one
inlined as `<symbol id="iso">` in every page.

If you change `site.css`, `site.js` or `releases.js`, bump the
`?v=` cache key on the matching tag in every HTML page.

Every release must attach the mod `.jar` (not `-sources.jar`) or the download
page will skip it. Pre-releases are shown, labelled as such.

The mod source code lives on the `main` branch.
