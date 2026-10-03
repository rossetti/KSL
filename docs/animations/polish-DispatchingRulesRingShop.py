"""Polish DispatchingRulesRingShop: three carts on a one-way ring, dispatched by one of six rules.

Run from the repository root after capturing:
  ./gradlew :KSLExamples:showcaseCapture -PmodelName=DispatchingRulesRingShop -Pout=build/showcase
  python3 docs/animations/polish-DispatchingRulesRingShop.py
"""
import sys
import pathlib

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from polishkit import *  # noqa: E402,F403

NAME = "DispatchingRulesRingShop"
SPACE = "Agv:Space"
layout, facts = load(NAME)
path = PathFacts(SHOWCASE / f"{NAME}.atf")

# The model's y axis points north; the screen's points down, so drawn as is "NorthPickup" lands at the
# bottom. A negative scale turns the ring through 180 degrees (a rotation, not a mirror, so the ring's
# direction of travel is kept) and puts north at the top.
SCALE = -1.7
LEFT, TOP = 90.0, 80.0
x0, y0, x1, y1 = frame_path(layout, path, SPACE, LEFT, TOP, scale=SCALE)
snap_locations(layout, path, SPACE)
zone = path.mean_zone(SPACE) * abs(SCALE)
layout["guidedPaths"][0]["linkWidth"] = 3.0

style_carts(layout, size=round(zone * 0.6, 1))
drop_types(layout, "Source")
for c in layout["objectClasses"]:
    if c["typeName"] == "Load":
        c["size"], c["color"], c["shape"] = round(zone * 0.35, 1), "#8c564b", "SQUARE"

shown = {"NorthPickup": "North pickup", "SouthPickup": "South pickup", "Shipping": "Shipping",
         "DepotA": "Depot A", "DepotB": "Depot B", "DepotC": "Depot C"}
layout["labels"] = [rename("LOCATION", k, v, dx=14.0 if k.startswith("Depot") else 0.0, dy=-16.0)
                    for k, v in shown.items()]

px = x1 + 140.0
fs = 15.0
layout["background"] = [
    text("Which cart goes?", px, TOP + 10, 22.0, "#333333"),
    text("Loads appear at the north and south pickups and go", px, TOP + 40, fs),
    text("to shipping, around a one-way ring. A dispatching", px, TOP + 60, fs),
    text("rule picks the cart for each load; this run uses", px, TOP + 80, fs),
    text("Nearest vehicle, under which the cart sent is rarely", px, TOP + 100, fs),
    text("the one that has waited longest. The rule is an", px, TOP + 120, fs),
    text("input, so the comparison is one model run six ways.", px, TOP + 140, fs),
    *cart_key(layout, px, TOP + 185, 14.0, {f"Cart{i}:Body": f"Cart {i}" for i in range(1, 4)}),
    *ring_key(px, TOP + 265, 13.0),
]
layout["clocks"] = [clock(px, TOP + 325, 18.0, label="Time (min)")]
layout["bars"] = [
    bar("Agv:NumVehiclesOnTask", px, TOP + 370, 220.0, 18.0, 3.0, "Carts on a task", "#555555"),
    bar("Agv:AwaitingPickupHoldQ:NumInQ", px, TOP + 420, 220.0, 18.0,
        float(max(3, facts.response_peak.get("Agv:AwaitingPickupHoldQ:NumInQ", 3))), "Loads waiting for a cart", "#8c564b"),
]
layout["title"] = "Dispatching rules on a ring"
layout["width"] = round(px + 380.0, 1)
layout["height"] = round(max(y1, TOP + 450.0) + 70.0, 1)
save(layout, NAME)
