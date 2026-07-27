#!/usr/bin/env python3
"""Produce the polished showcase layout for Example05PedestrianCrowd.

Input:  build/showcase/Example05PedestrianCrowd.lay.json  (the auto-layout, via showcaseCapture)
        build/showcase/Example05PedestrianCrowd.atf       (population, evacuation window)
Output: docs/animations/layouts/Example05PedestrianCrowd.lay.json

The generator gets more right here than anywhere else: the room, the wall (from the model's own obstacle
map), the doorway locations, the pedestrians with heading indicators, and the flow field they are steering
down all come out of the capture without help. What is missing is everything that says what is being looked
at — no clock, no counts, and three doorway markers labelled "Door 1/2/3" stacked on top of each other.
"""
import json, pathlib

SRC = pathlib.Path("build/showcase/Example05PedestrianCrowd.lay.json")
ATF = pathlib.Path("build/showcase/Example05PedestrianCrowd.atf")
OUT = pathlib.Path("docs/animations/layouts/Example05PedestrianCrowd.lay.json")

d = json.loads(SRC.read_text())

# ── 0. What the trace says ──────────────────────────────────────────────────────────────────────────
agents = set()
last_motion = 0.0
for line in ATF.read_text().splitlines():
    if '"AgentPositionChanged"' in line:
        e = json.loads(line)
        agents.add(e["agentName"]); last_motion = max(last_motion, e["simTime"])
POPULATION = float(len(agents))
assert POPULATION > 0, "no pedestrians in the trace"

room = next(s for s in d["spaces"] if s["type"] == "Continuous")
ROOM_W = float(room["xMax"]) - float(room["xMin"])
ROOM_H = float(room["yMax"]) - float(room["yMin"])

# ── 1. The doorway ──────────────────────────────────────────────────────────────────────────────────
# The model's three doorway cells are three separate locations, so the generated layout prints "Door 1",
# "Door 2" and "Door 3" one under the other at one-cell spacing -- three labels for one door, overlapping.
# The middle one is retitled to what the reader needs to know and the others are hidden; the markers stay,
# because together they are the width of the gap in the wall.
doors = sorted((l["locationName"] for l in d["locations"]))
assert len(doors) == 3, f"expected a three-cell doorway, found {doors}"
d["labels"] = [
    {"kind": "LOCATION", "name": doors[0], "visible": False},
    {"kind": "LOCATION", "name": doors[1], "text": "Exit", "dx": 14.0, "dy": 0.0},
    {"kind": "LOCATION", "name": doors[2], "visible": False},
]

for oc in d["objectClasses"]:
    oc["size"] = 0.62          # smaller than the generated 0.75: forty of these crowd the doorway
    oc["color"] = "#1f77b4"

# ── 2. A panel beside the room ──────────────────────────────────────────────────────────────────────
# Beside rather than below, for the same reason as the epidemic: it makes the frame landscape, and the room
# is square. The panel starts below the top-right corner, which the screen-space legend owns and no layout
# can move.
PANEL_X = ROOM_W + 1.8
PANEL_W = 12.0
PANEL_TOP = 5.4
W, H = PANEL_X + PANEL_W + 1.4, ROOM_H + 1.4
d["title"] = "Crowd evacuating a room through a three-cell doorway"
d["width"], d["height"] = round(W, 1), round(H, 1)

d["clocks"] = [{"position": {"x": PANEL_X, "y": PANEL_TOP, "z": 0.0}, "format": "0.0", "label": "Time",
                "fontSize": 1.15}]

# Two halves of one number: everyone is either still in the room or already out, so a shared scale makes the
# pair read as a single emptying process rather than as two unrelated meters.
d["bars"] = [
    {"responseName": "PopulationInRoom", "position": {"x": PANEL_X, "y": PANEL_TOP + 5.1, "z": 0.0},
     "width": PANEL_W, "height": 1.1, "maxValue": POPULATION, "color": "#1f77b4", "label": "Still in the room"},
    {"responseName": "NumEvacuated", "position": {"x": PANEL_X, "y": PANEL_TOP + 7.8, "z": 0.0},
     "width": PANEL_W, "height": 1.1, "maxValue": POPULATION, "color": "#2ca02c", "label": "Evacuated"},
]

def text(s, x, y, size=0.66, color="#666666"):
    return {"kind": "TEXT", "points": [{"x": x, "y": y, "z": 0.0}], "text": s, "color": color,
            "strokeWidth": 1.0, "imageRef": None, "fontSize": size, "fontFamily": None}

d["background"] = [
    text(f"{int(POPULATION)} pedestrians under Helbing social-force", PANEL_X, PANEL_TOP + 1.7),
    text("dynamics. Blue is where one is going; orange", PANEL_X, PANEL_TOP + 2.6),
    text("is the force of the crowd pushing on it.", PANEL_X, PANEL_TOP + 3.5),
    text("The wash behind them is the flow field: green", PANEL_X, PANEL_TOP + 10.7, 0.58, "#999999"),
    text("is near the exit, red is far. It is the gradient", PANEL_X, PANEL_TOP + 11.5, 0.58, "#999999"),
    text("they steer down, and the reason the crowd", PANEL_X, PANEL_TOP + 12.3, 0.58, "#999999"),
    text("funnels rather than spreads out.", PANEL_X, PANEL_TOP + 13.1, 0.58, "#999999"),
]

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(d, indent=1))
print(f"wrote {OUT}  ({d['width']}x{d['height']}, {int(POPULATION)} pedestrians, "
      f"last movement t={last_motion:.1f})")
