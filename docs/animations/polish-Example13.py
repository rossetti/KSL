#!/usr/bin/env python3
"""Produce the polished showcase layout for Example13MovableResources.

Kept as a script rather than as a hand-edited JSON blob so that every change carries its reason, and so
the layout can be regenerated if the model's own geometry changes.

Input:  build/showcase/Example13MovableResources.dsl.lay.json  (the example's own layout, via showcaseCapture)
Output: docs/animations/layouts/Example13MovableResources.lay.json
"""
import json, math, pathlib

SRC = pathlib.Path("build/showcase/Example13MovableResources.dsl.lay.json")
ATF = pathlib.Path("build/showcase/Example13MovableResources.atf")
OUT = pathlib.Path("docs/animations/layouts/Example13MovableResources.lay.json")

d = json.loads(SRC.read_text())

# A resource is drawn as one cell per unit of CAPACITY, in a row centred on its position, so its half-width
# is capacity x size / 2 -- not size / 2. Diagnostics has two servers and Repair three, so assuming a single
# cell would tuck their queues underneath the block. Capacity is not in the layout (it is a property of the
# run), so it is read from the trace.
CAPACITY = {}
for line in ATF.read_text().splitlines():
    if '"ResourceStateChanged"' in line:
        e = json.loads(line)
        CAPACITY[e["resourceName"]] = max(1, e["capacity"])

# ── 1. Orientation ──────────────────────────────────────────────────────────────────────────────────
# The five stations are placed by classical MDS from the model's own distance matrix, which is worth
# keeping: the picture then carries real information about how far apart things are. But MDS fixes only
# the SHAPE -- a configuration is determined up to rotation, reflection and translation -- and it happened
# to land with Diagnostics on the right and Repair on the left, so the process read backwards.
#
# Re-orienting the whole configuration so the process reads left to right therefore costs nothing: the
# transform is rigid, so every pairwise distance survives it exactly.
pos = {l["locationName"]: (l["position"]["x"], l["position"]["y"]) for l in d["locations"]}

def pairwise(p):
    ks = sorted(p)
    return {(a, b): math.dist(p[a], p[b]) for i, a in enumerate(ks) for b in ks[i + 1:]}

before = pairwise(pos)
cx = sum(p[0] for p in pos.values()) / len(pos)
cy = sum(p[1] for p in pos.values()) / len(pos)

def oriented(theta, mirror):
    out = {}
    for k, (x, y) in pos.items():
        x, y = x - cx, y - cy
        rx = x * math.cos(theta) - y * math.sin(theta)
        ry = x * math.sin(theta) + y * math.cos(theta)
        out[k] = (rx, -ry if mirror else ry)
    return out

# Search every rotation (and both reflections) that puts Diagnostics leftmost and Repair rightmost, then
# take the FLATTEST of them -- least vertical spread. Forcing the Diagnostics -> Repair axis exactly
# horizontal, as a first attempt did, satisfies the reading order but can leave a station stranded far off
# to one side. Optimising for flatness instead keeps the reading order and pulls the whole arrangement
# into a wide band, which is what makes an outlier station look part of the shop rather than lost.
#
# Every candidate is rigid, so this chooses only the orientation -- never the distances.
best = None
for deg in range(360):
    for mirror in (False, True):
        cand = oriented(math.radians(deg), mirror)
        xs = {k: v[0] for k, v in cand.items()}
        if xs["DiagnosticStation"] != min(xs.values()):   # first step of the process, so leftmost
            continue
        if xs["RepairStation"] != max(xs.values()):       # last step, so rightmost
            continue
        spread = max(v[1] for v in cand.values()) - min(v[1] for v in cand.values())
        if best is None or spread < best[0]:
            best = (spread, deg, mirror, cand)
assert best is not None, "no orientation puts Diagnostics leftmost and Repair rightmost"
_, DEG, MIRROR, t = best

# Translate into the canvas. The left margin has to clear a queue's extent, since a queue now grows leftward
# out of its station rather than downward.
minx, miny = min(v[0] for v in t.values()), min(v[1] for v in t.values())
MX, MY = 170.0, 190.0
placed = {k: (round(v[0] - minx + MX, 1), round(v[1] - miny + MY, 1)) for k, v in t.items()}

# The claim above -- that nothing is traded away -- is worth checking rather than asserting, since a sign
# error in the rotation would still produce a plausible-looking picture with the wrong distances in it.
after = pairwise(placed)
drift = max(abs(after[k] - before[k]) for k in before)
assert drift < 0.5, f"the transform is not rigid: {drift} units of distortion"

# Stations, and the resources co-located with them.
for l in d["locations"]:
    x, y = placed[l["locationName"]]
    l["position"] = {"x": x, "y": y, "z": 0.0}
RES_AT = {"DiagnosticWorkers": "DiagnosticStation", "Test1": "TestStation1", "Test2": "TestStation2",
          "Test3": "TestStation3", "RepairWorkers": "RepairStation"}
# A transport worker's drawn position resolves through the LOCATION of the same name, so a machine sitting
# exactly on its location has workers parked on top of it -- a triangle inside the server cell, hiding the
# part being worked on. Lifting the machine clear leaves the location as the spot on the floor where a
# worker stands, with the machine just above it, which is both readable and the truer picture.
MACHINE_LIFT = 30.0
for r in d["resources"]:
    x, y = placed[RES_AT[r["resourceName"]]]
    r["position"] = {"x": x, "y": round(y - MACHINE_LIFT, 1), "z": 0.0}

# ── 2. Scale ────────────────────────────────────────────────────────────────────────────────────────
# The sizes carried over from the DSL layout were chosen against a small hand-placed canvas; against the MDS
# arrangement, whose extent is set by real inter-station distances (~540 x 400 units), they left the stations
# as specks in a mostly white frame. Sizing them to the arrangement is what turns it into a picture of a
# shop floor. Repair is the widest block at three cells, so it anchors the choice: STATION x 3 is about a
# fifth of the arrangement's width, which reads as a machine rather than a dot or a wall.
STATION = 33.0
for r in d["resources"]:
    r["size"] = STATION
    r["showValue"] = False
for oc in d["objectClasses"]:
    # A part drawn inside a busy cell is sized off the cell (0.6 x), so matching that keeps a part the same
    # size whether it is waiting in a queue or being worked on.
    oc["size"] = round(STATION * 0.6, 1)

# ── 3. Queues ───────────────────────────────────────────────────────────────────────────────────────
# A queue's extent line is spacing x maxShown, so a generous maxShown advertises a capacity that is never
# reached and draws the longest line on screen. These rarely exceed three.
SIZES = {r["resourceName"]: r["size"] for r in d["resources"]}
HALF = {n: CAPACITY.get(n, 1) * s / 2 for n, s in SIZES.items()}
# The worker pool's own queue -- workers waiting to be assigned -- says nothing about the shop.
d["queues"] = [q for q in d["queues"] if q["queueName"] != "TransportWorkerPool:Q"]
# A station is really "queue + resource", and it has to read left to right like everything else: waiting
# parts on the left, then the head of the line, then the server they are waiting for. That is the
# convention the auto-layout already uses -- head just left of the resource, members growing further left
# (growthDegrees 180) -- and it is a better read than the DSL layout's downward queues, which hung as long
# vertical stalks below each station and carried the eye the wrong way.
for q in d["queues"]:
    owner = q["queueName"].removesuffix(":Q")
    x, y = placed[RES_AT[owner]]
    q["position"] = {"x": round(x - (HALF[owner] + 16.0), 1), "y": round(y - MACHINE_LIFT, 1), "z": 0.0}
    q["growthDegrees"] = 180.0
    q["maxShown"] = 3
    q["spacing"] = round(STATION * 0.78, 1)

# ── 3b. Routes ──────────────────────────────────────────────────────────────────────────────────────
# Without these the stations float in white space and the eye has nothing to follow between them. Drawing
# the routes gives the arrangement a floor plan, and it is the honest way to fill that space: each line is
# a station-to-station move a PART actually made during the run, read off the trace rather than assumed.
#
# Only part moves count. Including the workers' own repositioning would make the graph almost complete --
# a worker will travel anywhere to fetch its next job -- which says nothing about how the shop is routed.
routes, seen = [], set()
for line in ATF.read_text().splitlines():
    if '"MoveStarted"' not in line:
        continue
    e = json.loads(line)
    a, b = e.get("fromLocationName"), e.get("toLocationName")
    if not a or not b or a == b:
        continue
    key = tuple(sorted((a, b)))       # a route is undirected; drawing both ways would just double the line
    if key in seen:
        continue
    seen.add(key)
    routes.append({"name": f"{a}->{b}", "points": [],
                   "from": {"kind": "LOCATION", "name": a}, "to": {"kind": "LOCATION", "name": b},
                   "bidirectional": True})
d["paths"] = routes

# ── 4. Workers ──────────────────────────────────────────────────────────────────────────────────────
# Red and green are the resource BUSY and IDLE colours, so red/green workers either camouflaged against a
# station or read as a state change. These hues are not in the state palette.
for mr, colour in zip(d["movableResources"], ["#ff7f0e", "#9467bd", "#17becf"]):
    mr["color"] = colour
    mr["size"] = round(STATION * 0.76, 1)

# ── 5. Labels ───────────────────────────────────────────────────────────────────────────────────────
# The dominant defect, and the part no DSL can express. Four labels land on each station: its own name,
# its resource's name, its queue's name and count, and any worker parked on it.
SHORT = {"DiagnosticWorkers": "Diagnostics", "RepairWorkers": "Repair",
         "Test1": "Test 1", "Test2": "Test 2", "Test3": "Test 3"}
labels = []
for l in d["locations"]:                     # duplicates the resource label sitting on it
    labels.append({"kind": "LOCATION", "name": l["locationName"], "visible": False})
for name, short in SHORT.items():            # offset derived from glyph size, not a constant
    labels.append({"kind": "RESOURCE", "name": name, "text": short, "dy": -(SIZES[name] / 2 + 11)})
for q in d["queues"]:                        # the count is informative; the name repeats the resource
    labels.append({"kind": "QUEUE", "name": q["queueName"], "visible": False,
                   "valueVisible": True, "valueDx": -6.0, "valueDy": round(STATION * 0.62, 1)})
for mr in d["movableResources"]:             # a parked worker sits on its station; any label lands on it
    labels.append({"kind": "MOVABLE_RESOURCE", "name": mr["name"], "visible": False})
d["labels"] = labels

# ── 6. Chrome ───────────────────────────────────────────────────────────────────────────────────────
xs, ys = [], []
for q in d["queues"]:                    # a queue reaches left of its station by spacing x maxShown
    xs.append(q["position"]["x"] - q["spacing"] * q["maxShown"] - STATION)
for r in d["resources"]:
    half = HALF[r["resourceName"]]
    xs += [r["position"]["x"] - half, r["position"]["x"] + half]
    ys += [r["position"]["y"] - r["size"] / 2 - 30, r["position"]["y"] + r["size"] / 2 + 30]
for l in d["locations"]:                 # a worker standing below its machine
    ys.append(l["position"]["y"] + 24.0)
d["width"] = round(max(xs) + 40.0)
d["height"] = round(max(ys) + 80.0)

# MDS puts Diagnostics low-left and Test 3 high-right, which leaves a genuine hole in the middle-left. That
# hole is where the read-out belongs -- pinned to an edge it would only add to the frame's emptiness, and
# here it fills the one region no station wants.
for b in d["bars"]:
    if b["responseName"] == "NumInSystem":
        b["width"] = 340.0
        b["position"] = {"x": 40.0, "y": round(d["height"] * 0.46, 1), "z": 0.0}

# The chrome is in world units too, so it has to grow with the stations or the caption ends up shouting
# over them.
d["clocks"] = [{"position": {"x": 30.0, "y": 44.0, "z": 0.0}, "format": "0.0", "label": "Time",
                "fontSize": round(STATION * 0.58, 1)}]
d["background"] = [
    {"kind": "TEXT", "points": [{"x": 30.0, "y": 82.0, "z": 0.0}],
     "text": "Parts flow left to right: Diagnostics → tests → Repair.",
     "color": "#666666", "strokeWidth": 1.0, "imageRef": None, "fontSize": round(STATION * 0.42, 1), "fontFamily": None},
    {"kind": "TEXT", "points": [{"x": 30.0, "y": 106.0, "z": 0.0}],
     "text": "Station spacing is the model's own distance matrix; triangles are the 3 transport workers.",
     "color": "#666666", "strokeWidth": 1.0, "imageRef": None, "fontSize": round(STATION * 0.42, 1), "fontFamily": None},
]

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(d, indent=1))
print(f"wrote {OUT}  ({d['width']}x{d['height']}, {len(labels)} label overrides, "
      f"max distance drift {drift:.4f})")
