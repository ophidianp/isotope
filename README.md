# Isotope Website

This branch hosts the Isotope website, deployed with GitHub Pages from the
repository root to https://ophidianp.github.io/isotope/.

## Pages
- **Home** — `index.html` (hero atom and three interactive demos)
- **How it works** — `how-it-works.html`
- **Compatibility** — `compatibility.html` (real test results, limits, other projects)
- **Download** — `downloads.html` (newest GitHub Release, loaded live)
- **Transparency** — `transparency.html`
- **404** — `404.html`

Shared assets: `assets/site.css` (styles and motion), `assets/site.js` (theme,
navigation, scroll reveals), `assets/liquid-glass.*` (glass effect),
`assets/isotope.js` (hero and demos) and `assets/releases.js` (download data).
The design is shared with the Chlorine site, recoloured to Isotope's violet
(`#B79CF0`).

If you change `site.css`, `site.js`, `isotope.js` or `releases.js`, bump the
`?v=` cache key on the matching tag in every HTML page.

Every release must attach the mod `.jar` (not `-sources.jar`) or the download
page will skip it. Pre-releases are shown, labelled as such.

The mod source code lives on the `main` branch.
