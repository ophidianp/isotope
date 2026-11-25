#!/usr/bin/env python3
"""Build Modrinth .mrpack files for testing Isotope in a real client.

Each pack targets one game version. It installs Fabric API for that version
and the Isotope release jar into mods/, and puts the test mods - built for a
DIFFERENT version - into isotope/mods/ so Isotope rewrites them at start.
Nothing is bundled: every file is a download link (Modrinth CDN, GitHub
release) with its hash, so no third-party jar ends up in the pack or repo.

Usage: tools/make-test-pack.py [--isotope-version 1.0.1] [--out DIR]
"""
import argparse, hashlib, json, os, urllib.request, zipfile

UA = {"User-Agent": "isotope-test-pack/1.0 (github.com/ophidianp/isotope)"}
API = "https://api.modrinth.com/v2"
FABRIC_API = "P7dR8mSH"  # Fabric API's Modrinth project id

# Packs: the mods that passed the dedicated-server tests (see README).
PACKS = {
    "forwards-26.3": {
        "target": "26.3",
        "from": ["26.1", "26.1.1", "26.1.2", "26.2"],
        "mods": ["too-fast", "fishing-real", "nyfs-spiders", "patchouli",
                 "thermoo", "the-lost-castle", "medieval-buildings",
                 "fwaystones", "owo-lib", "guard-villagers-fabricquilt"],
        "note": "Do not add Lithostitched: Wraith Waystones only loads without it.",
    },
    "backwards-26.2": {
        "target": "26.2",
        "from": ["26.3"],
        "mods": ["backslot", "dragonloot", "herdspanic", "reciperemover", "libz"],
        "note": "",
    },
}


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return json.load(r)


def versions(project):
    return get(f"{API}/project/{project}/version?loaders=%5B%22fabric%22%5D")


def pick(project, allowed, target):
    """Newest Fabric version built for an `allowed` game version but not for `target`."""
    for v in versions(project):  # newest first
        gv = set(v["game_versions"])
        if gv & set(allowed) and target not in gv:
            return v
    return None


def native(project, target):
    for v in versions(project):
        if target in v["game_versions"]:
            return v
    return None


def env_of(project):
    p = get(f"{API}/project/{project}")
    m = {"required": "required", "optional": "optional", "unsupported": "unsupported"}
    return p["slug"], p["title"], {"client": m.get(p["client_side"], "optional"),
                                   "server": m.get(p["server_side"], "optional")}


def entry(v, folder, env):
    f = next((f for f in v["files"] if f["primary"]), v["files"][0])
    return {"path": f"{folder}/{f['filename']}", "hashes": f["hashes"], "env": env,
            "downloads": [f["url"]], "fileSize": f["size"]}


def isotope_entry(version, cache):
    url = f"https://github.com/ophidianp/isotope/releases/download/v{version}/isotope-{version}.jar"
    path = os.path.join(cache, f"isotope-{version}.jar")
    if not os.path.exists(path):
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as r, \
                open(path, "wb") as out:
            out.write(r.read())
    data = open(path, "rb").read()
    return {"path": f"mods/isotope-{version}.jar",
            "hashes": {"sha1": hashlib.sha1(data).hexdigest(),
                       "sha512": hashlib.sha512(data).hexdigest()},
            "env": {"client": "required", "server": "required"},
            "downloads": [url], "fileSize": len(data)}


def build(name, spec, iso_version, loader, out, cache):
    target, files, seen, lines = spec["target"], [], set(), []
    fapi = native(FABRIC_API, target)
    files.append(entry(fapi, "mods", {"client": "required", "server": "required"}))
    seen.add(FABRIC_API)
    lines.append(f"mods/          Fabric API {fapi['version_number']}")
    files.append(isotope_entry(iso_version, cache))
    lines.append(f"mods/          Isotope {iso_version}")

    queue = [(m, True) for m in spec["mods"]]
    while queue:
        project, listed = queue.pop(0)
        slug, title, env = env_of(project)
        v = pick(slug, spec["from"], target)
        folder = "isotope/mods"
        if not listed:  # a dependency: use a build for the target when there is one
            nv = native(slug, target)
            if nv:
                v, folder = nv, "mods"
        if v is None:
            raise SystemExit(f"{name}: no usable Fabric version of {slug}")
        if v["project_id"] in seen:
            continue
        seen.add(v["project_id"])
        files.append(entry(v, folder, env))
        lines.append(f"{folder + '/':15}{title} {v['version_number']} "
                     f"(built for {', '.join(v['game_versions'])})")
        for d in v["dependencies"]:
            if d["dependency_type"] == "required" and d.get("project_id") \
                    and d["project_id"] not in seen \
                    and d["project_id"] not in [q for q, _ in queue]:
                queue.append((d["project_id"], False))

    index = {"formatVersion": 1, "game": "minecraft",
             "versionId": f"{iso_version}-{name}",
             "name": f"Isotope test ({name})",
             "summary": f"Isotope {iso_version} on {target} with mods built for "
                        f"{'/'.join(spec['from'])} in isotope/mods/.",
             "files": files,
             "dependencies": {"minecraft": target, "fabric-loader": loader}}
    readme = (f"Isotope test pack: {name}\nGame {target}, Fabric Loader {loader}\n\n"
              + "\n".join(lines) + "\n\n"
              "After launching, check isotope/report.txt and logs/latest.log\n"
              "(grep: Isotope, FAILED during APPLY, was not applied).\n"
              + (spec["note"] + "\n" if spec["note"] else ""))
    os.makedirs(out, exist_ok=True)
    path = os.path.join(out, f"isotope-test-{name}.mrpack")
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("modrinth.index.json", json.dumps(index, indent=2))
        z.writestr("overrides/isotope/TEST-PACK.txt", readme)
    print(path)
    print(readme)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--isotope-version", default="1.0.1")
    ap.add_argument("--loader", default=None, help="Fabric Loader version (default: latest stable)")
    ap.add_argument("--out", default="build/test-packs")
    ap.add_argument("packs", nargs="*", default=list(PACKS))
    a = ap.parse_args()
    loader = a.loader or next(v["version"] for v in
                              get("https://meta.fabricmc.net/v2/versions/loader") if v["stable"])
    cache = os.path.join(a.out, "cache")
    os.makedirs(cache, exist_ok=True)
    for name in a.packs:
        build(name, PACKS[name], a.isotope_version, loader, a.out, cache)


if __name__ == "__main__":
    main()
