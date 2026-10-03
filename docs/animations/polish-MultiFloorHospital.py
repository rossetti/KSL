"""Polish MultiFloorHospital: porters carry orders between two floors through two single-zone shafts.

Run from the repository root after capturing:
  ./gradlew :KSLExamples:showcaseCapture -PmodelName=MultiFloorHospital -Pout=build/showcase
  python3 docs/animations/polish-MultiFloorHospital.py
"""
import sys
import pathlib

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from polishkit import *  # noqa: E402,F403

NAME = "MultiFloorHospital"
SPACE = "Agv:Space"
layout, facts = load(NAME)
path = PathFacts(SHOWCASE / f"{NAME}.atf")

# The model has no plan view of a building: both floors share the same x and y, and only z separates them.
# The auto-layout lifts the upper floor 27 units, about a fifth of the corridor's length, which reads as one
# slab. 60 makes them two floors with a stair between them.
style = next(s for s in layout["guidedPaths"] if s["spaceName"] == SPACE)
style["floorOffsetPerZ"] = {"x": 0.0, "y": -0.75, "z": 0.0}
SCALE = 4.0
LEFT, TOP = 170.0, 80.0
x0, y0, x1, y1 = frame_path(layout, path, SPACE, LEFT, TOP, scale=SCALE)
snap_locations(layout, path, SPACE)
corridor_zone = 10.0 * SCALE  # the corridors' zone length; the shafts' single 80-unit zone would skew a mean
style["linkWidth"] = 3.0

style_carts(layout, size=round(corridor_zone * 0.5, 1))
for c in layout["objectClasses"]:
    if c["typeName"] == "Order":
        c["size"], c["color"] = round(corridor_zone * 0.3, 1), "#2ca02c"

# The eight parking bays are the porters' homes; numbering them adds nothing and their labels crowd the fan.
layout["labels"] = [hide("LOCATION", f"Park{i}") for i in range(1, 9)] + [
    rename("LOCATION", "Lobby", "Lobby", dx=-40.0, dy=4.0),
    rename("LOCATION", "WardA", "Ward", dy=28.0),
    rename("LOCATION", "Pharmacy", "Pharmacy", dy=-16.0),
]

ground_y = next(l for l in layout["locations"] if l["locationName"] == "Lobby")["position"]["y"]
first_y = next(l for l in layout["locations"] if l["locationName"] == "Pharmacy")["position"]["y"]
mid_y = (ground_y + first_y) / 2
px = x1 + 110.0
fs = 15.0
layout["background"] = [
    text("First floor", LEFT - 150, first_y + 5, 15.0, "#333333"),
    text("Ground floor", LEFT - 150, ground_y + 5, 15.0, "#333333"),
    text("Shaft down", x0 - 95, mid_y, 13.0),
    text("Shaft up", x1 + 12, mid_y, 13.0),
    text("Porters between two floors", px, TOP + 10, 22.0, "#333333"),
    text("Porters carry orders between the lobby, a ward and", px, TOP + 40, fs),
    text("a pharmacy upstairs. Each shaft is a single zone, so", px, TOP + 60, fs),
    text("one porter at a time can use it; the queue for the", px, TOP + 80, fs),
    text("upper floor forms at the foot of the shaft, not at", px, TOP + 100, fs),
    text("the pharmacy. Idle porters park in the bays below", px, TOP + 120, fs),
    text("the lobby.", px, TOP + 140, fs),
    *cart_key(layout, px, TOP + 180, 14.0, {f"Porter{i}:Body": f"Porter {i}" for i in range(1, 4)}),
    *ring_key(px, TOP + 260, 13.0),
]
layout["clocks"] = [clock(px, TOP + 320, 18.0, label="Time (min)")]
layout["bars"] = [
    bar("Agv:NumVehiclesOnTask", px, TOP + 365, 220.0, 18.0, 3.0, "Porters on a task", "#555555"),
    bar("Agv:AwaitingPickupHoldQ:NumInQ", px, TOP + 415, 220.0, 18.0,
        float(max(3, facts.response_peak.get("Agv:AwaitingPickupHoldQ:NumInQ", 3))), "Orders waiting for a porter", "#2ca02c"),
    bar("Agv:Space:NumTransportersBlocked", px, TOP + 465, 220.0, 18.0, 3.0, "Porters blocked", "#d62728"),
]
layout["title"] = "Two-floor hospital"
layout["width"] = round(px + 380.0, 1)
layout["height"] = round(max(y1, TOP + 500.0) + 60.0, 1)
save(layout, NAME)
