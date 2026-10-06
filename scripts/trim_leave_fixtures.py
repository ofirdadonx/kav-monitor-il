#!/usr/bin/env python3
"""Trims raw curlbus polls recorded by `leaveLiveSmoke` into small test fixtures.

Keeps every visit of the watched line (line_id 37121 = Egged 194) plus the first visit of another line (so the poll
still counts as "reliable": the stop returned something), drops static_info / stop_info.
Usage: trim_leave_fixtures.py RAW_DIR OUT_DIR [LINE_ID]
"""
import json, os, sys

raw, out = sys.argv[1], sys.argv[2]
line = sys.argv[3] if len(sys.argv) > 3 else "37121"
os.makedirs(out, exist_ok=True)
for name in sorted(os.listdir(raw)):
    if not name.endswith(".json"):
        continue
    d = json.load(open(os.path.join(raw, name)))
    visits = {}
    for code, lst in (d.get("visits") or {}).items():
        mine = [v for v in lst if v.get("line_id") == line]
        other = [v for v in lst if v.get("line_id") != line][:1]
        kept = []
        for v in lst:  # keep original order
            if v in mine or v in other:
                v = dict(v); v.pop("static_info", None); kept.append(v)
        visits[code] = kept
    json.dump({"errors": d.get("errors"), "timestamp": d.get("timestamp"), "visits": visits},
              open(os.path.join(out, name), "w"), ensure_ascii=False, separators=(",", ":"))
print("trimmed", len([n for n in os.listdir(out) if n.endswith('.json')]), "polls into", out)
