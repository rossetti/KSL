"""Polish SimpleAgvShop: two carts on a one-way loop, parts from an entry station to an exit.

Run from the repository root after capturing:
  ./gradlew :KSLExamples:showcaseCapture -PmodelName=SimpleAgvShop -Pout=build/showcase
  python3 docs/animations/polish-SimpleAgvShop.py
"""
import sys
import pathlib

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from polishkit import *  # noqa: E402,F403

NAME = "SimpleAgvShop"
layout, facts = load(NAME)
path = PathFacts(SHOWCASE / f"{NAME}.atf")

# The model's loop is 54 by 108 units with 12-unit zones; four times that makes a cart a readable block
# while leaving the right half of the frame for what the picture cannot say on its own.
SCALE = 4.0
LEFT, TOP = 190.0, 70.0
x0, y0, x1, y1 = frame_path(layout, path, "AgvSystem", LEFT, TOP, scale=SCALE)
zone = path.mean_zone("AgvSystem") * SCALE

style = layout["guidedPaths"][0]
style["linkWidth"] = 3.0
style["label"] = None

style_carts(layout, size=round(zone * 0.55, 1))
for c in layout["objectClasses"]:
    if c["typeName"] == "Part":
        c["size"] = round(zone * 0.3, 1)
        c["color"] = "#2ca02c"

# Parts waiting for a cart are the pool's request queue; once a cart is assigned they wait for it to arrive
# in the system's pickup hold. Both are at the entry, so that is where they are drawn, growing away from it.
qx, qy = waiting_queue(layout, facts, "Carts:Q", "EntryStation", zone)
waiting_queue(layout, facts, "AgvSystem:AwaitingPickupHoldQ", "EntryStation", zone, below=0.9)
layout["labels"] = [
    count_only("QUEUE", "Carts:Q", dx=-10.0, dy=26.0),
    count_only("QUEUE", "AgvSystem:AwaitingPickupHoldQ", dx=-10.0, dy=26.0),
    # Below the station: a cart parked at the entry would otherwise sit on its name.
    rename("LOCATION", "EntryStation", "Entry", dy=30.0),
    rename("LOCATION", "ExitStation", "Exit", dx=-34.0, dy=4.0),
]

# ── the panel ──
px = x1 + 150.0
fs = 15.0
layout["background"] = [
    text("Two carts on a one-way loop", px, TOP + 10, 22.0, "#333333"),
    text("Parts arrive at the entry and wait there for a cart.", px, TOP + 40, fs),
    text("A cart claims the zone ahead before it moves into it,", px, TOP + 60, fs),
    text("so carts follow each other and never pass.", px, TOP + 80, fs),
    text("Idle carts go home to the spurs on the right.", px, TOP + 100, fs),
    text("Waiting for a cart", qx - 120, qy - 28, 13.0),
    text("Cart on its way", qx - 120, qy + zone * 0.9 + 32, 13.0),
    *cart_key(layout, px, TOP + 150, 14.0, {"Cart1": "Cart 1", "Cart2": "Cart 2"}),
    *ring_key(px, TOP + 195, 13.0),
]
layout["clocks"] = [clock(px, TOP + 260, 18.0, label="Time (min)")]
layout["bars"] = [
    bar("AgvSystem:NumTransportersMoving", px, TOP + 305, 220.0, 18.0, 2.0, "Carts moving", "#555555"),
    bar("Carts:Q:NumInQ", px, TOP + 355, 220.0, 18.0, float(max(3, facts.queue_peak["Carts:Q"])), "Parts waiting for a cart", "#2ca02c"),
]
layout["title"] = "Simple AGV shop"
layout["width"] = round(px + 330.0, 1)
layout["height"] = round(y1 + 90.0, 1)
save(layout, NAME)
