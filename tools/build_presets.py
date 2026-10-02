#!/usr/bin/env python3
"""Regenerates app/src/main/assets/osm_presets.tsv from the OpenStreetMap iD
editor's tagging schema (https://github.com/openstreetmap/id-tagging-schema, ISC).

Each line is:  name <TAB> other names and search words, "|"-separated <TAB> tag filter
where the tag filter uses the app's own syntax ("key=value", "&" for AND, "key=*"
for any value). Run from the project root:  python3 tools/build_presets.py
"""
import json
import urllib.request

BASE = "https://cdn.jsdelivr.net/npm/@openstreetmap/id-tagging-schema@latest/dist/"
OUT = "app/src/main/assets/osm_presets.tsv"


def fetch(path):
    with urllib.request.urlopen(BASE + path) as r:
        return json.load(r)


def clean(text):
    return " ".join(str(text).replace("\t", " ").replace("|", " ").split())


presets = fetch("presets.min.json")
names = fetch("translations/en.min.json")["en"]["presets"]["presets"]

lines = []
for pid, preset in sorted(presets.items()):
    tags = preset.get("tags") or {}
    info = names.get(pid) or {}
    name = clean(info.get("name", ""))
    if pid.startswith("@") or not tags or not name or preset.get("searchable") is False:
        continue
    if any(ch in k + v for k, v in tags.items() for ch in "&~\"\t|") or any(" OR " in v for v in tags.values()):
        continue
    words = [clean(w) for w in list(info.get("aliases", [])) + list(info.get("terms", []))]
    spec = "&".join(f"{k}={v}" for k, v in tags.items())
    lines.append("\t".join([name, "|".join(w for w in words if w), spec]))

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print(f"wrote {len(lines)} presets to {OUT}")
