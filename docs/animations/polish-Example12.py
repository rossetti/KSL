#!/usr/bin/env python3
"""Produce the polished showcase layout for Example12StemFairStorage.

Input:  build/showcase/Example12StemFairStorage.lay.json  (the auto-layout, via showcaseCapture)
        build/showcase/Example12StemFairStorage.atf       (routes, capacities, stage occupancy)
Output: docs/animations/layouts/Example12StemFairStorage.lay.json

The model is a DistancesModel: five named locations that students walk between. Two things have to be
drawn and they come from opposite ends of the trace.

The walking is free -- a moveTo reports the locations it runs between, so the paths are in the trace and
the renderer interpolates students along them. The *stopping* is not: where a student stops is a bare
delay, which has no geometry, and between them the two stops hold most of the people in the building.
Storages are what make those visible, which is why the model names those two delays.
"""
import json, pathlib, collections

SRC = pathlib.Path("build/showcase/Example12StemFairStorage.lay.json")
ATF = pathlib.Path("build/showcase/Example12StemFairStorage.atf")
OUT = pathlib.Path("docs/animations/layouts/Example12StemFairStorage.lay.json")

d = json.loads(SRC.read_text())

# ── 0. What the trace says ──────────────────────────────────────────────────────────────────────────
CAPACITY = {}
routes, seen = [], set()
spans = collections.defaultdict(list)
for line in ATF.read_text().splitlines():
    if '"ResourceStateChanged"' in line:
        e = json.loads(line)
        CAPACITY[e["resourceName"]] = max(1, e["capacity"])
    elif '"MoveStarted"' in line:
        e = json.loads(line)
        a, b = e.get("fromLocationName"), e.get("toLocationName")
        if a and b and a != b and (key := tuple(sorted((a, b)))) not in seen:
            seen.add(key); routes.append((a, b))
    elif '"DelayStarted"' in line:
        e = json.loads(line)
        if e.get("suspensionName"):
            spans[e["suspensionName"]].append((e["simTime"], e["arrivalTime"]))

def peak(key):
    """The most members a stage ever holds at once -- what its box has to be big enough to show."""
    v = spans[key]
    return max(sum(1 for a, b in v if a <= t < b) for t in range(0, 361))

# ── 1. The corridor ─────────────────────────────────────────────────────────────────────────────────
# The venue's distance matrix is exactly one-dimensional -- Entrance 20ft NameTags 30ft ConversationArea
# 50ft Recruiting 60ft Exit, and every other pair is the sum of the ones between -- so MDS recovers a
# straight line, and it happens to recover it in process order. Nothing needs rotating; this is the
# Example13 orientation problem in its easiest form, and the assertions below are what say so rather than
# leaving it to luck. Positions are only scaled and shifted, which is uniform, so the venue stays to scale.
ORDER = ["Entrance", "NameTags", "ConversationArea", "Recruiting", "Exit"]
mds = {l["locationName"]: (l["position"]["x"], l["position"]["y"]) for l in d["locations"]}
assert set(mds) == set(ORDER), f"unexpected locations {sorted(mds)}"
ys = [y for _, y in mds.values()]
assert max(ys) - min(ys) < 1.0, f"the venue is not collinear (y spread {max(ys) - min(ys)})"

xs = [mds[n][0] for n in ORDER]
if xs != sorted(xs):                       # a reflection would put Exit on the left
    assert xs == sorted(xs, reverse=True), f"MDS did not recover the process order: {xs}"
    xs = [-x for x in xs]

SCALE, MARGIN_X, CORRIDOR_Y = 1.55, 120.0, 400.0
X = {n: round((x - min(xs)) * SCALE + MARGIN_X, 1) for n, x in zip(ORDER, xs)}
for l in d["locations"]:
    l["position"] = {"x": X[l["locationName"]], "y": CORRIDOR_Y, "z": 0.0}

W, H = round(X["Exit"] + 120.0), 660.0
d["title"] = "STEM Fair Mixer — students walking a venue, and the delays that hold them"
d["width"], d["height"] = W, H

# A path anchors to locations by name, so the corridor is drawn from the moves that actually happened.
# Every pair is collinear here, so they overlay into one street -- which is what the venue is.
d["paths"] = [
    {"name": f"{a}->{b}", "points": [],
     "from": {"kind": "LOCATION", "name": a}, "to": {"kind": "LOCATION", "name": b},
     "bidirectional": True}
    for a, b in routes
]

# ── 2. The two stops ────────────────────────────────────────────────────────────────────────────────
# Buildings hanging off the street: the name-tag table above it, the conversation area below. Keeping them
# off the corridor itself matters -- students walking are drawn ON the corridor, and a storage sitting on
# it would have walkers crossing through a crowd they are not part of.
#
# A PACKED_REGION for the name-tag table and a PROGRESS_BELT for the conversation is not decoration. The
# table is a place students are AT, so a packed crowd is the honest picture; the conversation is 18 minutes
# long, and a belt draws each student at the fraction of it that has elapsed, so the wait itself is what
# you see moving. Showing both styles at once is also the point of the example.
#
# Sizes come from the observed peak, not from a guess: a box that could hold thirty when it never holds
# more than two reads as an empty room.
belt_w = 280.0
stops = {
    "NameTags":         dict(x=X["NameTags"] - 40.0, y=CORRIDOR_Y - 100.0, w=80.0, h=52.0,
                             style="PACKED_REGION", label="Name tags"),
    # The belt STARTS at the location rather than being centred on it: a member is drawn at the fraction
    # of its delay that has elapsed, so the left end is "just started". Anchoring it under the spot on the
    # corridor where students turn off makes the belt read as the conversation itself running its course.
    "ConversationArea": dict(x=X["ConversationArea"], y=CORRIDOR_Y + 70.0, w=belt_w, h=54.0,
                             style="PROGRESS_BELT", label="Conversing"),
}
by_name = {s["suspensionName"]: s for s in d["storages"]}
assert set(by_name) == set(stops), f"trace stages {sorted(by_name)} != laid-out stops {sorted(stops)}"
for name, spec in stops.items():
    s = by_name[name]
    s["position"] = {"x": round(spec["x"], 1), "y": spec["y"], "z": 0.0}
    s["width"], s["height"], s["style"], s["label"] = spec["w"], spec["h"], spec["style"], spec["label"]
    s["spacing"] = 16.0
    s["maxShown"] = max(12, peak(name) * 2)     # clear of the peak, so it never degrades to a gauge
d["storages"] = [by_name["NameTags"], by_name["ConversationArea"]]

# ── 3. The recruiters ───────────────────────────────────────────────────────────────────────────────
# The only resources in the model, stacked above the Recruiting end of the street. A resource is one cell
# per unit of capacity in a row centred on its position, so a queue head has to clear capacity x size / 2
# -- J.H. Bunt has three recruiters, and assuming one cell would tuck its line under its own block.
SIZE = 34.0
RECRUITERS = {"JHBuntR": ("J.H. Bunt", 232.0), "MalWartR": ("Mal-Wart", 322.0)}
for r in d["resources"]:
    r["position"] = {"x": X["Recruiting"], "y": RECRUITERS[r["resourceName"]][1], "z": 0.0}
    r["size"] = SIZE
    r["showValue"] = False
for q in d["queues"]:
    owner = q["queueName"].removesuffix(":Q")
    half = CAPACITY.get(owner, 1) * SIZE / 2
    q["position"] = {"x": round(X["Recruiting"] - half - 16.0, 1), "y": RECRUITERS[owner][1], "z": 0.0}
    q["growthDegrees"] = 180.0      # head beside its recruiter, line growing away to the left
    q["spacing"] = 16.0
    q["maxShown"] = 8

# ── 4. Labels ───────────────────────────────────────────────────────────────────────────────────────
# Each recruiter attracts its own name and its queue's at nearly the same point, overprinted. The queue's
# name repeats the recruiter's, so only its count survives. The five location names would land on the
# corridor under the walkers, and the ones that matter are already named by a storage box or a caption.
labels = [
    {"kind": "RESOURCE", "name": n, "text": t, "dy": -(SIZE / 2 + 12)} for n, (t, _) in RECRUITERS.items()
] + [
    {"kind": "QUEUE", "name": f"{n}:Q", "visible": False, "valueVisible": True,
     "valueDx": -6.0, "valueDy": 22.0} for n in RECRUITERS
] + [
    {"kind": "LOCATION", "name": n, "visible": False} for n in ORDER
]
d["labels"] = labels

# ── 5. Chrome ───────────────────────────────────────────────────────────────────────────────────────
def text(s, x, y, size=14.0, color="#666666"):
    return {"kind": "TEXT", "points": [{"x": x, "y": y, "z": 0.0}], "text": s, "color": color,
            "strokeWidth": 1.0, "imageRef": None, "fontSize": size, "fontFamily": None}

def line(x1, y1, x2, y2, color="#c8c8c8"):
    return {"kind": "LINE", "points": [{"x": x1, "y": y1, "z": 0.0}, {"x": x2, "y": y2, "z": 0.0}],
            "text": None, "color": color, "strokeWidth": 1.0, "imageRef": None,
            "fontSize": 12.0, "fontFamily": None}

d["background"] = [
    # Spurs joining each stop to the street it hangs off, so the venue reads as connected.
    line(X["NameTags"], CORRIDOR_Y - 48.0, X["NameTags"], CORRIDOR_Y),
    line(X["ConversationArea"], CORRIDOR_Y, X["ConversationArea"], CORRIDOR_Y + 70.0),
    line(X["Recruiting"], RECRUITERS["MalWartR"][1] + SIZE / 2, X["Recruiting"], CORRIDOR_Y),
    text("Students walk a five-location venue; the line is the corridor and the dots on it are students in transit.", 32.0, 86.0),
    text("Where they stop is a bare delay() with no geometry — a storage bound to the delay's name is what draws it.", 32.0, 110.0),
    text("A conversation runs ~18 minutes against a few seconds of walking, so the belt below is where the fair is.", 32.0, 148.0, 12.5, "#999999"),
    text("Entrance", 34.0, CORRIDOR_Y + 5.0, 14.0, "#333333"),
    text("Exit", X["Exit"] + 16.0, CORRIDOR_Y + 5.0, 14.0, "#333333"),
    text("Recruiting", X["Recruiting"] - 34.0, CORRIDOR_Y + 24.0, 14.0, "#333333"),
]

d["clocks"] = [{"position": {"x": 32.0, "y": 44.0, "z": 0.0}, "format": "0.0", "label": "Time",
                "fontSize": 20.0}]
# The second bar counts exactly what the conversation belt draws, so one can be read against the other.
BARS = {"NumInSystem": ("Students in the mixer", 560.0, 40.0),
        "NumInConversationArea": ("In conversation", 606.0, 10.0)}
d["bars"] = [
    {"responseName": name, "position": {"x": 32.0, "y": y, "z": 0.0}, "width": 300.0, "height": 22.0,
     "maxValue": mx, "color": "#1f77b4", "label": label}
    for name, (label, y, mx) in BARS.items()
]
for oc in d["objectClasses"]:
    oc["size"] = 13.0
    oc["color"] = "#1f77b4"

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(d, indent=1))
print(f"wrote {OUT}  ({W}x{int(H)}, {len(routes)} corridor segments, "
      f"peaks: name tags {peak('NameTags')}, conversing {peak('ConversationArea')})")
