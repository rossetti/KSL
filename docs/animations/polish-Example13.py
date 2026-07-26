#!/usr/bin/env python3
"""Produce the polished showcase layout for Example13MovableResources.

Kept as a script rather than as a hand-edited JSON blob so that every change carries its reason, and so
the layout can be regenerated if the model's own geometry changes.

Input:  build/showcase/Example13MovableResources.dsl.lay.json  (the example's own layout, via showcaseCapture)
Output: docs/animations/layouts/Example13MovableResources.lay.json
"""
import json, math, pathlib, sys

SRC = pathlib.Path("build/showcase/Example13MovableResources.dsl.lay.json")
OUT = pathlib.Path("docs/animations/layouts/Example13MovableResources.lay.json")

d = json.loads(SRC.read_text())

# ── 1. Orientation ──────────────────────────────────────────────────────────────────────────────────
# The five stations are placed by classical MDS from the model's own distance matrix, which is worth
# keeping: the picture then carries real information about how far apart things are. But MDS fixes only
# the SHAPE -- a configuration is determined up to rotation, reflection and translation -- and it happened
# to land with Diagnostics on the right and Repair on the left, so the process read backwards.
#
# Rotating the whole configuration so the Diagnostics -> Repair axis points along +x makes it read left to
# right, and because the transform is rigid every pairwise distance is preserved exactly. Nothing is
# traded away.
pos = {l["locationName"]: (l["position"]["x"], l["position"]["y"]) for l in d["locations"]}

def pairwise(p):
    ks = sorted(p)
    return {(a, b): math.dist(p[a], p[b]) for i, a in enumerate(ks) for b in ks[i + 1:]}

before = pairwise(pos)
dx = pos["RepairStation"][0] - pos["DiagnosticStation"][0]
dy = pos["RepairStation"][1] - pos["DiagnosticStation"][1]
theta = -math.atan2(dy, dx)
cx = sum(p[0] for p in pos.values()) / len(pos)
cy = sum(p[1] for p in pos.values()) / len(pos)

def turn(p):
    x, y = p[0] - cx, p[1] - cy
    return (x * math.cos(theta) - y * math.sin(theta), x * math.sin(theta) + y * math.cos(theta))

t = {k: turn(v) for k, v in pos.items()}
# Mirror vertically if needed so the test stations sit above the axis: their queues hang downward, and
# below the axis they would cross it.
if sum(1 for k, v in t.items() if k.startswith("TestStation") and v[1] < 0) < 2:
    t = {k: (v[0], -v[1]) for k, v in t.items()}

minx, miny = min(v[0] for v in t.values()), min(v[1] for v in t.values())
MX, MY = 90.0, 130.0
placed = {k: (round(v[0] - minx + MX, 1), round(v[1] - miny + MY, 1)) for k, v in t.items()}

drift = max(abs(pairwise(placed)[k] - before[k]) for k in before)
assert drift < 0.5, f"the transform must stay rigid; pairwise distances moved by {drift}"

# Stations, and the resources co-located with them.
for l in d["locations"]:
    x, y = placed[l["locationName"]]
    l["position"] = {"x": x, "y": y, "z": 0.0}
RES_AT = {"DiagnosticWorkers": "DiagnosticStation", "Test1": "TestStation1", "Test2": "TestStation2",
          "Test3": "TestStation3", "RepairWorkers": "RepairStation"}
for r in d["resources"]:
    x, y = placed[RES_AT[r["resourceName"]]]
    r["position"] = {"x": x, "y": y, "z": 0.0}

# ── 2. Queues ───────────────────────────────────────────────────────────────────────────────────────
# A queue's extent line is spacing x maxShown, so a generous maxShown advertises a capacity that is never
# reached and draws the longest line on screen. These rarely exceed three.
SIZES = {r["resourceName"]: r["size"] for r in d["resources"]}
# The worker pool's own queue -- workers waiting to be assigned -- says nothing about the shop.
d["queues"] = [q for q in d["queues"] if q["queueName"] != "TransportWorkerPool:Q"]
for q in d["queues"]:
    owner = q["queueName"].removesuffix(":Q")
    x, y = placed[RES_AT[owner]]
    q["position"] = {"x": x, "y": y + SIZES[owner], "z": 0.0}   # hangs below its resource
    q["growthDegrees"] = 90.0
    q["maxShown"] = 3
    q["spacing"] = 14.0

# ── 3. Workers ──────────────────────────────────────────────────────────────────────────────────────
# Red and green are the resource BUSY and IDLE colours, so red/green workers either camouflaged against a
# station or read as a state change. These hues are not in the state palette.
for mr, colour in zip(d["movableResources"], ["#ff7f0e", "#9467bd", "#17becf"]):
    mr["color"] = colour
    mr["size"] = 15.0

# ── 4. Labels ───────────────────────────────────────────────────────────────────────────────────────
# The dominant defect, and the part no DSL can express. Four labels land on each station: its own name,
# its resource's name, its queue's name and count, and any worker parked on it.
SHORT = {"DiagnosticWorkers": "Diagnostics", "RepairWorkers": "Repair",
         "Test1": "Test 1", "Test2": "Test 2", "Test3": "Test 3"}
labels = []
for l in d["locations"]:                     # duplicates the resource label sitting on it
    labels.append({"kind": "LOCATION", "name": l["locationName"], "visible": False})
for name, short in SHORT.items():            # offset derived from glyph size, not a constant
    labels.append({"kind": "RESOURCE", "name": name, "text": short, "dy": -(SIZES[name] / 2 + 8)})
for q in d["queues"]:                        # the count is informative; the name repeats the resource
    labels.append({"kind": "QUEUE", "name": q["queueName"], "visible": False,
                   "valueVisible": True, "valueDx": 10.0, "valueDy": 4.0})
for mr in d["movableResources"]:             # a parked worker sits on its station; any label lands on it
    labels.append({"kind": "MOVABLE_RESOURCE", "name": mr["name"], "visible": False})
d["labels"] = labels

# ── 5. Chrome ───────────────────────────────────────────────────────────────────────────────────────
xs, ys = [], []
for r in d["resources"]:
    xs += [r["position"]["x"] - r["size"], r["position"]["x"] + r["size"]]
    ys += [r["position"]["y"] - r["size"], r["position"]["y"] + r["size"] + 70]
d["width"] = round(max(xs) + 40.0)
d["height"] = round(max(ys) + 70.0)

# MDS leaves a hole in the lower left: Diagnostics is the only station on that side and it sits high.
# Putting the read-out there balances the frame instead of stranding it on the bottom edge, and keeps it
# clear of every queue extent (which all hang below their own station).
for b in d["bars"]:
    if b["responseName"] == "NumInSystem":
        b["width"] = 300.0
        b["position"] = {"x": 60.0, "y": round(d["height"] * 0.62, 1), "z": 0.0}

d["clocks"] = [{"position": {"x": 24.0, "y": 34.0, "z": 0.0}, "format": "0.0", "label": "Time",
                "fontSize": 13.0}]
d["background"] = [
    {"kind": "TEXT", "points": [{"x": 24.0, "y": 60.0, "z": 0.0}],
     "text": "Parts flow left to right: Diagnostics → tests → Repair. "
             "Stations are placed from the model's distance matrix.",
     "color": "#666666", "strokeWidth": 1.0, "imageRef": None, "fontSize": 11.0, "fontFamily": None},
    {"kind": "TEXT", "points": [{"x": 24.0, "y": 78.0, "z": 0.0}],
     "text": "Triangles are the 3 transport workers carrying parts between stations.",
     "color": "#666666", "strokeWidth": 1.0, "imageRef": None, "fontSize": 11.0, "fontFamily": None},
]

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(d, indent=1))
print(f"wrote {OUT}  ({d['width']}x{d['height']}, {len(labels)} label overrides, "
      f"max distance drift {drift:.4f})")
