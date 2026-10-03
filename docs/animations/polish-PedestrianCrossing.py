"""Polish PedestrianCrossing: a cart on a two-lane aisle and pedestrians crossing it, one discipline deciding
who goes first. This showcase runs the bounded-batch discipline.

Run from the repository root after capturing:
  ./gradlew :KSLExamples:showcaseCapture -PmodelName=PedestrianCrossing -Pout=build/showcase
  python3 docs/animations/polish-PedestrianCrossing.py
"""
import sys
import pathlib

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from polishkit import *  # noqa: E402,F403

NAME = "PedestrianCrossing"
SPACE = "Sys"
layout, facts = load(NAME)
path = PathFacts(SHOWCASE / f"{NAME}.atf")

# A 72-unit aisle with 12-unit zones; eight times that is a road wide enough to walk across.
SCALE = 8.0
LEFT, TOP = 60.0, 200.0
x0, y0, x1, y1 = frame_path(layout, path, SPACE, LEFT, TOP, scale=SCALE)
zone = path.mean_zone(SPACE) * SCALE
style = layout["guidedPaths"][0]
style["linkWidth"] = 3.0
# Amber, as in the disturbance model: the crossing closing the aisle is a cause, and red is the blocked ring.
style["closureColor"] = "#f2a900"
style_carts(layout, size=round(zone * 0.35, 1))
for c in layout["objectClasses"]:
    if c["typeName"] == "Walker":
        c["size"], c["color"] = round(zone * 0.1, 1), "#2ca02c"

# The crossing is the aisle's third zone, measured from the aisle's own start, which is not necessarily its
# left end: read both ends from the model and interpolate, so the stripes land on the zone the trace closes.
ints = {i["name"]: i for i in path.paths[SPACE]["intersections"]}
aisle = next(l for l in path.paths[SPACE]["links"] if l["name"] == "Aisle")
start, end = ints[aisle["from"]]["x"], ints[aisle["to"]]["x"]
cx = style["offset"]["x"] + SCALE * (start + (end - start) * 2.5 / aisle["numZones"])
road_top, road_bottom = y0 - zone * 0.45, y1 + zone * 0.45
stripes = [line(cx - zone * 0.35, y, cx + zone * 0.35, y, "#d9d9d9") for y in
           [road_top + k * (road_bottom - road_top) / 7 for k in range(8)]]
for s in stripes:
    s["strokeWidth"] = 5.0

# Walkers wait below the road and cross upward; the crossing delay is drawn as a belt over the stripes, so a
# walker moves across as its crossing time elapses.
layout["queues"] = [{"queueName": "WalkQ", "position": {"x": round(cx, 1), "y": round(road_bottom + 22.0, 1), "z": 0.0},
                     "growthDegrees": 90.0, "spacing": round(zone * 0.13, 1), "maxShown": facts.max_shown("WalkQ")}]
layout["storages"] = [{"suspensionName": "Crossing", "position": {"x": round(cx, 1), "y": round(road_bottom, 1), "z": 0.0},
                       "style": "PROGRESS_BELT", "width": round(road_bottom - road_top, 1), "height": 10.0,
                       "growthDegrees": -90.0, "spacing": 10.0, "capacity": 0, "maxShown": 30, "byType": True,
                       "label": None}]
layout["labels"] = [count_only("QUEUE", "WalkQ", dx=16.0, dy=4.0)]

px = LEFT
by = road_bottom + 150.0
fs = 15.0
layout["background"] = [
    *stripes,
    text("Waiting to cross", cx + 16, road_bottom + 50, 13.0),
    text("A crossing two populations want", px, 50.0, 22.0, "#333333"),
    text("A cart runs up and down the aisle; pedestrians cross it. While walkers are on the crossing it is closed",
         px, 85.0, fs),
    text("to the cart (shaded amber), and the discipline decides when it reopens. This run lets walkers across in",
         px, 107.0, fs),
    text("bounded batches; two of the other three disciplines starve one side outright.", px, 129.0, fs),
    *ring_key(px, by, 13.0),
]
layout["clocks"] = [clock(px + 420.0, by, 18.0, label="Time (min)")]
layout["bars"] = [
    bar("Walkway:NumWaitingToCross", px + 420.0, by + 40.0, 200.0, 16.0,
        float(max(3, facts.response_peak.get("Walkway:NumWaitingToCross", 3))), "Walkers waiting", "#2ca02c"),
]
layout["title"] = "Pedestrian crossing"
layout["width"] = round(max(x1, px + 700.0) + 60.0, 1)
layout["height"] = round(by + 90.0, 1)
save(layout, NAME)
