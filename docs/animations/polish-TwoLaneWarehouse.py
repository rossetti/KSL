"""Polish TwoLaneWarehouse: four carts moving pallets on a grid of two-lane aisles, one lane each way.

Run from the repository root after capturing:
  ./gradlew :KSLExamples:showcaseCapture -PmodelName=TwoLaneWarehouse -Pout=build/showcase
  python3 docs/animations/polish-TwoLaneWarehouse.py
"""
import sys
import pathlib

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from polishkit import *  # noqa: E402,F403

NAME = "TwoLaneWarehouse"
layout, facts = load(NAME)
path = PathFacts(SHOWCASE / f"{NAME}.atf")

# 240 by 130 units of aisle; the grid is the subject, so it gets most of a landscape frame.
SCALE = 2.8
LEFT, TOP = 70.0, 80.0
x0, y0, x1, y1 = frame_path(layout, path, "Fleet:Space", LEFT, TOP, scale=SCALE)
zone = path.mean_zone("Fleet:Space") * SCALE
layout["guidedPaths"][0]["linkWidth"] = 2.5

style_carts(layout, size=round(zone * 0.45, 1))
drop_types(layout, "Source")
for c in layout["objectClasses"]:
    if c["typeName"] == "Pallet":
        c["size"], c["color"], c["shape"] = round(zone * 0.25, 1), "#8c564b", "SQUARE"

names = {"Pick0": "Pick 1", "Pick1": "Pick 2", "Pick2": "Pick 3", "Dock": "Dock"}
layout["labels"] = [rename("LOCATION", k, v, dy=26.0) for k, v in names.items() if k != "Dock"]
layout["labels"].append(rename("LOCATION", "Dock", "Dock", dx=-30.0, dy=4.0))

px = x1 + 90.0
fs = 15.0
layout["background"] = [
    text("Two lanes per aisle", px, TOP + 10, 22.0, "#333333"),
    text("Each aisle is a pair of one-way lanes, drawn side", px, TOP + 40, fs),
    text("by side, so carts going opposite ways pass instead", px, TOP + 60, fs),
    text("of waiting for each other. Pallets go from the three", px, TOP + 80, fs),
    text("pick faces to the dock; idle carts park on spurs", px, TOP + 100, fs),
    text("so they are out of the traffic.", px, TOP + 120, fs),
    *cart_key(layout, px, TOP + 160, 14.0, {f"Cart{i}:Body": f"Cart {i}" for i in range(1, 5)}),
    *ring_key(px, TOP + 260, 13.0),
]
layout["clocks"] = [clock(px, TOP + 320, 18.0, label="Time (min)")]
layout["bars"] = [
    bar("Fleet:NumVehiclesOnTask", px, TOP + 365, 220.0, 18.0, 4.0, "Carts on a task", "#555555"),
    bar("Fleet:AwaitingPickupHoldQ:NumInQ", px, TOP + 415, 220.0, 18.0,
        float(max(3, facts.response_peak.get("Fleet:AwaitingPickupHoldQ:NumInQ", 3))), "Pallets waiting at the picks", "#8c564b"),
    bar("Fleet:Space:NumTransportersBlocked", px, TOP + 465, 220.0, 18.0, 4.0, "Carts blocked", "#d62728"),
]
layout["title"] = "Two-lane warehouse"
layout["width"] = round(px + 360.0, 1)
layout["height"] = round(max(y1, TOP + 500.0) + 60.0, 1)
save(layout, NAME)
