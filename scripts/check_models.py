#!/usr/bin/env python3
"""Resolve every catalog entry against the Hugging Face API with the same rules as
HfResolver.kt, so a typo in models.json or a renamed upload fails CI instead of the phone."""
import json, os, re, sys, urllib.request, pathlib

CATALOG = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/models.json"
SPLIT = re.compile(r"-\d{5}-of-\d{5}", re.I)
AUX = ("mmproj", "mtp", "draft", "imatrix")

def list_files(repo, sub="", depth=0):
    url = f"https://huggingface.co/api/models/{repo}/tree/main" + (f"/{sub}" if sub else "")
    with urllib.request.urlopen(url, timeout=30) as r:
        items = json.load(r)
    out = []
    for it in items:
        if it.get("type") == "file":
            size = (it.get("lfs") or {}).get("size") or it.get("size", 0)
            out.append((it["path"], size))
        elif it.get("type") == "directory" and depth < 1:
            out += list_files(repo, it["path"], depth + 1)
    return out

def qre(q):
    return re.compile(r"(?<![A-Za-z0-9])" + re.escape(q) + r"(?![A-Za-z0-9_])", re.I)

def first_by_pref(files, prefs):
    for q in prefs:
        m = [f for f in files if qre(q).search(f[0].rsplit("/", 1)[-1])]
        if m:
            return min(m, key=lambda f: len(f[0]))
    return None

def download(repo, path, dest):
    import os
    url = f"https://huggingface.co/{repo}/resolve/main/{path}?download=true"
    out = os.path.join(dest, path.rsplit("/", 1)[-1])
    if not os.path.exists(out):
        urllib.request.urlretrieve(url, out)
    out = os.path.abspath(out)
    print(out, file=sys.stderr)
    return out

def main():
    # --download DIR [ROLE...]: also fetch the chosen weights (used by the host smoke test)
    dl_dir, dl_roles = None, None
    if "--download" in sys.argv:
        i = sys.argv.index("--download"); dl_dir = sys.argv[i + 1]; dl_roles = set(sys.argv[i + 2:])
    models = json.loads(CATALOG.read_text(encoding="utf-8"))["models"]
    ok = True
    # every entry must parse in the app (ModelCatalog.base): required fields and a known role
    roles = {"PROPOSER", "MELCHIOR", "BALTHASAR", "CASPER"}
    for m in models:
        missing = [k for k in ("id", "role", "name", "vendor", "madeInJapan", "license", "repo", "quant", "approxGb") if k not in m]
        if missing or m.get("role") not in roles:
            print(f"BAD ENTRY {m.get('id')}: missing={missing} role={m.get('role')}")
            ok = False
    for m in models:
        try:
            files = list_files(m["repo"])
        except Exception as e:
            print(f"FAIL {m['id']}: cannot list {m['repo']}: {e}"); ok = False; continue
        ggufs = [f for f in files if f[0].lower().endswith(".gguf") and not SPLIT.search(f[0])]
        weights = [f for f in ggufs if not any(a in f[0].rsplit("/", 1)[-1].lower() for a in AUX)]
        w = first_by_pref(weights, m["quant"])
        line = f"{m['id']:<18} {m['repo']}\n    model : {w[0] if w else 'NOT FOUND'} ({(w[1] if w else 0)/1e9:.2f} GB)"
        if not w:
            ok = False
        if m.get("mmproj"):
            projs = [f for f in ggufs if "mmproj" in f[0].rsplit("/", 1)[-1].lower()]
            p = first_by_pref(projs, m["mmproj"]) or (projs[0] if projs else None)
            line += f"\n    mmproj: {p[0] if p else 'NOT FOUND'} ({(p[1] if p else 0)/1e9:.2f} GB)"
            if not p:
                ok = False
        fam = m.get("family")
        if fam:
            pat = re.compile(fam["pattern"].replace("(?<", "(?P<"), re.I)
            fm = pat.match(m["repo"].split("/", 1)[1])
            line += f"\n    family: {'ok ' + str(fm.groupdict()) if fm else 'PATTERN DOES NOT MATCH CURRENT REPO'}"
            if not fm:
                ok = False
        print(line)
        if w and dl_dir and (not dl_roles or m["role"] in dl_roles):
            with open(f"{dl_dir}/paths.txt", "a") as fh:
                fh.write(download(m["repo"], w[0], dl_dir) + "\n")
        if not w:
            print("    available:", ", ".join(f[0] for f in ggufs) or "(no gguf)")
    sys.exit(0 if ok else 1)

if __name__ == "__main__":
    main()
