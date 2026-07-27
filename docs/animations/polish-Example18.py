#!/usr/bin/env python3
"""Produce the polished showcase layout for Example18ConveyorTestRepair.

Input:  build/showcase/Example18ConveyorTestRepair.lay.json  (the auto-layout, via showcaseCapture)
        build/showcase/Example18ConveyorTestRepair.atf       (belt geometry, capacities, queue peaks)
Output: build/showcase/polished/Example18ConveyorTestRepair.lay.json

The generator gets the belt's *topology* right on its own -- five anchors chained in the order the conveyor
visits them, and now the stations assembled onto them -- but it lays the chain out as a straight line,
because that is the only thing it can do without knowing the shape of the floor.

For a loop conveyor a straight line is not merely plain, it is false. The belt runs one way and comes back
to where it started, so a part that wants the station it just left has to ride most of a lap to reach it.
Drawn as a line, the return leg is a jump from the right-hand end back to the left. Drawn as a loop, it is
the lap it actually is, and the reason a test plan's ordering matters becomes visible.
"""
import json, pathlib, collections

SRC = pathlib.Path("build/showcase/Example18ConveyorTestRepair.lay.json")
ATF = pathlib.Path("build/showcase/Example18ConveyorTestRepair.atf")
OUT = pathlib.Path("build/showcase/polished/Example18ConveyorTestRepair.lay.json")

d = json.loads(SRC.read_text())

# ── 0. The belt, as the run reports it ──────────────────────────────────────────────────────────────
# A ConveyorDefined event carries each anchor and the cell it sits at, so the belt's real proportions come
# out of the trace rather than being invented. Each anchor appears twice -- an exit cell and the entry cell
# just before it -- and the entry is the one to place on.
CAPACITY, peak = {}, collections.Counter()
anchors = None
for line in ATF.read_text().splitlines():
    if '"ResourceStateChanged"' in line:
        e = json.loads(line)
        CAPACITY[e["resourceName"]] = max(1, e["capacity"])
    elif '"QueueLengthChanged"' in line:
        e = json.loads(line)
        peak[e["queueName"]] = max(peak[e["queueName"]], e["length"])
    elif '"ConveyorDefined"' in line and anchors is None:
        e = json.loads(line)
        first = {}
        for name, cell in zip(e["anchorLocations"], e["anchorCells"]):
            first.setdefault(name, cell)
        anchors = sorted(first.items(), key=lambda kv: kv[1])
assert anchors is not None, "no ConveyorDefined in the trace"

segments = [s["entryLocation"] for s in d["conveyors"][0]["segments"]]
assert [n for n, _ in anchors] == segments, f"belt order {segments} != anchor order {[n for n, _ in anchors]}"

# The lap is one past the highest cell reported. Each station is reported twice, at the cell a part leaves
# the belt and the cell just before it, so the highest is the entry immediately before the belt closes back
# onto its first anchor. Using the lap rather than that entry keeps every drawn segment proportional to the
# cell count the model declared for it.
_defined = json.loads(next(l for l in ATF.read_text().splitlines() if '"ConveyorDefined"' in l))
LAP = max(_defined["anchorCells"]) + 1

# ── 1. The loop ─────────────────────────────────────────────────────────────────────────────────────
# A rectangle walked clockwise from its top-left corner, with every anchor placed at its own cell's share of
# the perimeter. Keeping drawn distance proportional to cells is what makes the animation honest: a part
# rides at constant speed, so a segment drawn short for its cell count would show parts hurrying through it.
X0, Y0, RECT_W, RECT_H = 150.0, 170.0, 700.0, 380.0
PERIMETER = 2 * (RECT_W + RECT_H)
UNITS_PER_CELL = PERIMETER / LAP

def on_loop(cell):
    """The point at [cell] along the belt, walking the rectangle clockwise from its top-left corner."""
    s = cell * UNITS_PER_CELL
    if s <= RECT_W:
        return X0 + s, Y0
    s -= RECT_W
    if s <= RECT_H:
        return X0 + RECT_W, Y0 + s
    s -= RECT_H
    if s <= RECT_W:
        return X0 + RECT_W - s, Y0 + RECT_H
    return X0, Y0 + RECT_H - (s - RECT_W)

PLACE = {name: on_loop(cell) for name, cell in anchors}
CORNERS = {"TR": (X0 + RECT_W, Y0), "BR": (X0 + RECT_W, Y0 + RECT_H), "BL": (X0, Y0 + RECT_H)}

for l in d["locations"]:
    x, y = PLACE[l["locationName"]]
    l["position"] = {"x": round(x, 1), "y": round(y, 1), "z": 0.0}

# Waypoints turn each segment that crosses a corner into two straight runs. Without them the belt cuts the
# corner diagonally, which both shortens the ride and draws parts travelling through the middle of the shop.
def corners_between(a, b):
    """The rectangle corners a part passes while riding from anchor [a] to anchor [b]."""
    order = [n for n, _ in anchors]
    cell_of = dict(anchors)
    start, end = cell_of[a], cell_of[b] if cell_of[b] > cell_of[a] else LAP
    out = []
    for name, cell in (("TR", RECT_W), ("BR", RECT_W + RECT_H), ("BL", 2 * RECT_W + RECT_H)):
        at = cell / UNITS_PER_CELL
        if start < at < end:
            out.append(CORNERS[name])
    return out

belt = d["conveyors"][0]
belt["segments"] = [
    {"entryLocation": s["entryLocation"], "exitLocation": s["exitLocation"],
     "waypoints": [{"x": round(x, 1), "y": round(y, 1), "z": 0.0}
                   for x, y in corners_between(s["entryLocation"], s["exitLocation"])]}
    for s in belt["segments"]
]
# A belt draws as one square per cell, sized at half the declared width. Left at the default those squares
# are a fraction of the cell pitch and 130 of them read as a dotted outline rather than as a belt, so the
# width is set from the pitch: squares that very nearly touch look like a conveyor, and the small gaps still
# let a reader count cells and see which are occupied.
CELL_PITCH = PERIMETER / LAP
belt["width"] = round(CELL_PITCH * 1.75, 1)
belt["color"] = "#5b6470"
belt["showDirection"] = True

# ── 2. The stations, outside the belt they serve ────────────────────────────────────────────────────
# Each machine sits just beyond the loop at its own anchor, so the belt stays clear for the parts riding it
# and a station reads as something the belt passes rather than something sitting on it.
SIZE = 34.0
OUTSIDE = {  # anchor -> (machine offset from the anchor)
    "Diagnostics": (0.0, -70.0), "Test1": (0.0, -70.0), "Test2": (0.0, -70.0),
    # Repair is a three-cell block, so its queue reaches far enough back that a 115-unit offset had the
    # waiting line crossing the belt it is fed from. Far enough out that the whole station clears it.
    "Repair": (185.0, 0.0), "Test3": (0.0, 72.0),
}
res_at = {}
for r in d["resources"]:
    name = r["resourceName"]
    ax, ay = PLACE[name]
    dx, dy = OUTSIDE[name]
    res_at[name] = (round(ax + dx, 1), round(ay + dy, 1))
    r["position"] = {"x": res_at[name][0], "y": res_at[name][1], "z": 0.0}
    r["size"] = SIZE
    r["showValue"] = False

for q in d["queues"]:
    owner = q["queueName"].removesuffix(":Q")
    x, y = res_at[owner]
    half = CAPACITY.get(owner, 1) * SIZE / 2
    q["position"] = {"x": round(x - half - 16.0, 1), "y": y, "z": 0.0}
    q["growthDegrees"] = 180.0
    q["spacing"] = 20.0
    q["maxShown"] = max(3, peak[q["queueName"]] + 1)

for oc in d["objectClasses"]:
    oc["size"] = 15.0
    oc["color"] = "#1f77b4"

SHORT = {"Diagnostics": "Diagnostics", "Test1": "Test 1", "Test2": "Test 2",
         "Test3": "Test 3", "Repair": "Repair"}
d["labels"] = (
    [{"kind": "RESOURCE", "name": n, "text": t, "dy": -(SIZE / 2 + 13)} for n, t in SHORT.items()]
    + [{"kind": "QUEUE", "name": f"{n}:Q", "visible": False, "valueVisible": True,
        "valueDx": -6.0, "valueDy": 22.0} for n in SHORT]
    + [{"kind": "LOCATION", "name": n, "visible": False} for n in SHORT]
)

# ── 3. Chrome, inside the loop ──────────────────────────────────────────────────────────────────────
# The middle of a loop is a hole no element wants, which makes it the right place for the read-out.
W, H = round(max(x for x, _ in res_at.values()) + 150.0), round(Y0 + RECT_H + 160.0)
d["width"], d["height"] = W, H
d["title"] = "Test & repair shop — parts riding a loop conveyor"

def text(s, x, y, size=15.0, color="#666666"):
    return {"kind": "TEXT", "points": [{"x": x, "y": y, "z": 0.0}], "text": s, "color": color,
            "strokeWidth": 1.0, "imageRef": None, "fontSize": size, "fontFamily": None}

INNER_X = X0 + 105.0
d["clocks"] = [{"position": {"x": INNER_X, "y": Y0 + 95.0, "z": 0.0}, "format": "0.0", "label": "Time",
                "fontSize": 24.0}]
d["background"] = [
    text("The same job shop as the movable-resource version, with the transport replaced by an", INNER_X, Y0 + 145.0),
    text("accumulating loop belt. Each part rides to whichever station its test plan calls for next.", INNER_X, Y0 + 170.0),
    text(f"The belt runs one way, so a part that wants the station it just left rides {LAP} cells", INNER_X, Y0 + 215.0, 13.5, "#999999"),
    text("round to reach it. Segment lengths here are drawn to the cell counts the model", INNER_X, Y0 + 236.0, 13.5, "#999999"),
    text("declares, so a ride takes as long on screen as it does in the run.", INNER_X, Y0 + 257.0, 13.5, "#999999"),
]
d["bars"] = [
    {"responseName": "ConveyorTestRepair:NumInSystem", "position": {"x": INNER_X, "y": Y0 + 295.0, "z": 0.0},
     "width": 380.0, "height": 24.0, "maxValue": 24.0, "color": "#1f77b4", "label": "Number in system"}
]

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(d, indent=1))
print(f"wrote {OUT}  ({W}x{H}, {LAP}-cell loop, anchors "
      f"{ {n: c for n, c in anchors} })")
