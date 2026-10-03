"""Polish GuidePathDisturbances: the two-cart loop, disturbed by spills and a maintenance window.

Run from the repository root after capturing:
  ./gradlew :KSLExamples:showcaseCapture -PmodelName=GuidePathDisturbances -Pout=build/showcase
  python3 docs/animations/polish-GuidePathDisturbances.py
"""
import sys
import pathlib

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from polishkit import *  # noqa: E402,F403

NAME = "GuidePathDisturbances"
layout, facts = load(NAME)
path = PathFacts(SHOWCASE / f"{NAME}.atf")

# Same loop as SimpleAgvShop, drawn the same way so the two read as one shop with and without trouble.
SCALE = 4.0
LEFT, TOP = 190.0, 70.0
x0, y0, x1, y1 = frame_path(layout, path, "AgvSystem", LEFT, TOP, scale=SCALE)
zone = path.mean_zone("AgvSystem") * SCALE
style = layout["guidedPaths"][0]
style["linkWidth"] = 3.0
# Amber, not the default red: red is already the blocked ring, and a closure is the cause of a block, not one.
style["closureColor"] = "#f2a900"

style_carts(layout, size=round(zone * 0.55, 1),
            colors=["#1f77b4", "#9467bd"])
for c in layout["objectClasses"]:
    if c["typeName"] == "Part":
        c["size"], c["color"] = round(zone * 0.3, 1), "#2ca02c"
# A spill is the cause of a closure, not a thing that moves; its queue is the cleaning crew's backlog.
drop_types(layout, "Spill")
drop_queues(layout, "SpillQ")

qx, qy = waiting_queue(layout, facts, "Carts:Q", "EntryStation", zone)
waiting_queue(layout, facts, "AgvSystem:AwaitingPickupHoldQ", "EntryStation", zone, below=0.9)
layout["labels"] = [
    count_only("QUEUE", "Carts:Q", dx=-10.0, dy=26.0),
    count_only("QUEUE", "AgvSystem:AwaitingPickupHoldQ", dx=-10.0, dy=26.0),
    rename("LOCATION", "EntryStation", "Entry", dy=30.0),
    rename("LOCATION", "ExitStation", "Exit", dx=-34.0, dy=4.0),
]

px = x1 + 150.0
fs = 15.0
layout["background"] = [
    text("The loop, disturbed", px, TOP + 10, 22.0, "#333333"),
    text("Spills close a zone until they are cleaned up, and a", px, TOP + 40, fs),
    text("maintenance crew closes a whole link on a schedule.", px, TOP + 60, fs),
    text("Shaded zones are closed: faint while carts drain out,", px, TOP + 80, fs),
    text("solid once held. A cart stopped at the edge of one is", px, TOP + 100, fs),
    text("waiting on space that looks empty.", px, TOP + 120, fs),
    text("Waiting for a cart", qx - 120, qy - 28, 13.0),
    text("Cart on its way", qx - 120, qy + zone * 0.9 + 32, 13.0),
    *cart_key(layout, px, TOP + 165, 14.0, {"Cart1": "Cart 1", "Cart2": "Cart 2"}),
    *ring_key(px, TOP + 210, 13.0),
]
layout["clocks"] = [clock(px, TOP + 275, 18.0, label="Time (min)")]
layout["bars"] = [
    bar("AgvSystem:NumTransportersBlocked", px, TOP + 320, 220.0, 18.0, 2.0, "Carts blocked", "#d62728"),
    bar("Carts:Q:NumInQ", px, TOP + 370, 220.0, 18.0, float(max(3, facts.queue_peak["Carts:Q"])),
        "Parts waiting for a cart", "#2ca02c"),
]
layout["title"] = "Guide path disturbances"
layout["width"] = round(px + 360.0, 1)
layout["height"] = round(y1 + 90.0, 1)
save(layout, NAME)
