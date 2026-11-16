/*
 * Download page: shows the newest Isotope release from GitHub.
 * One Isotope jar runs on every Minecraft 26.x, so there is no version picker.
 * Pre-releases count (early builds are published that way) and are labelled.
 */
const releasesEndpoint = "https://api.github.com/repos/ophidianp/isotope/releases?per_page=30";
const releaseStatus = document.querySelector("#release-status");
const releaseResult = document.querySelector("#release-result");
const el = {
  kind: document.querySelector("#release-kind"),
  name: document.querySelector("#release-name"),
  page: document.querySelector("#release-page"),
  download: document.querySelector("#download-button"),
};

function jarAsset(release) {
  return release.assets.find((asset) => {
    const name = asset.name.toLowerCase();
    return name.endsWith(".jar") && !name.endsWith("-sources.jar");
  });
}

function show(release) {
  const asset = jarAsset(release);
  el.kind.textContent = release.prerelease ? "Latest pre-release" : "Latest release";
  el.name.textContent = release.name || release.tag_name;
  el.page.href = release.html_url;
  el.download.href = asset.browser_download_url;
  el.download.setAttribute("aria-disabled", "false");
  el.download.setAttribute("download", asset.name);
  releaseResult.hidden = false;
  const date = new Date(release.published_at).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
  releaseStatus.textContent = `${asset.name} · ${date}`;
}

fetch(releasesEndpoint, { headers: { Accept: "application/vnd.github+json" } })
  .then((response) => {
    if (!response.ok) throw new Error(`GitHub returned ${response.status}`);
    return response.json();
  })
  .then((releases) => {
    const release = releases.find((item) => !item.draft && jarAsset(item));
    if (release) show(release);
    else releaseStatus.textContent = "No release has been published yet. The first build is on its way; until then you can build it from the repository.";
  })
  .catch((error) => {
    releaseStatus.textContent = `Could not load release data (${error.message}). You can still download directly from GitHub.`;
  });
