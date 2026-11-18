# Isotope Website

This branch hosts the Isotope website, deployed with GitHub Pages from the
repository root to https://ophidianp.github.io/isotope/.

## Pages
- **Home** — `index.html`
- **How it works** — `how-it-works.html`
- **Compatibility** — `compatibility.html` (real test results, limits, other projects)
- **Download** — `downloads.html` (newest GitHub Release, loaded live)
- **Transparency** — `transparency.html`
- **404** — `404.html`

Shared assets: `assets/site.css` (one plain, minimal stylesheet; light and dark
follow the visitor's system), `assets/liquid-glass.*` (glass effect, header only)
and `assets/releases.js` (download data). The design is shared with the Chlorine
site, recoloured to Isotope's violet.

If you change `site.css` or `releases.js`, bump the
`?v=` cache key on the matching tag in every HTML page.

Every release must attach the mod `.jar` (not `-sources.jar`) or the download
page will skip it. Pre-releases are shown, labelled as such.

The mod source code lives on the `main` branch.
