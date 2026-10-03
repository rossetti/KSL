"""Polish PassiveTransporterShop: one cart on the loop, requested and steered by each part.

Drawn exactly like ActiveFleetShop, which models the same shop actively: the two are meant to be watched side
by side, and the only differences in the frame should be the ones between the paradigms.

Run from the repository root after capturing:
  ./gradlew :KSLExamples:showcaseCapture -PmodelName=PassiveTransporterShop -Pout=build/showcase
  python3 docs/animations/polish-PassiveTransporterShop.py
"""
import sys
import pathlib

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from polishkit import *  # noqa: E402,F403

NAME = "PassiveTransporterShop"
layout, facts = load(NAME)
path = PathFacts(SHOWCASE / f"{NAME}.atf")

SCALE = 4.0
LEFT, TOP = 190.0, 70.0
x0, y0, x1, y1 = frame_path(layout, path, "Space", LEFT, TOP, scale=SCALE)
zone = path.mean_zone("Space") * SCALE
layout["guidedPaths"][0]["linkWidth"] = 3.0

style_carts(layout, size=round(zone * 0.55, 1))
drop_types(layout, "Source")
for c in layout["objectClasses"]:
    if c["typeName"] == "Part":
        c["size"], c["color"] = round(zone * 0.3, 1), "#2ca02c"

qx, qy = waiting_queue(layout, facts, "Carts:Q", "EntryStation", zone)
waiting_queue(layout, facts, "Space:AwaitingPickupHoldQ", "EntryStation", zone, below=0.9)
layout["labels"] = [
    count_only("QUEUE", "Carts:Q", dx=-10.0, dy=26.0),
    count_only("QUEUE", "Space:AwaitingPickupHoldQ", dx=-10.0, dy=26.0),
    rename("LOCATION", "EntryStation", "Entry", dy=30.0),
    rename("LOCATION", "ExitStation", "Exit", dx=-34.0, dy=4.0),
    rename("LOCATION", "CartDepot", "Depot", dx=30.0, dy=4.0),
]

px = x1 + 150.0
fs = 15.0
layout["background"] = [
    text("Passive: the part drives", px, TOP + 10, 22.0, "#333333"),
    text("Each part requests the cart, steers it to the entry,", px, TOP + 40, fs),
    text("rides it to the exit and releases it. The cart has", px, TOP + 60, fs),
    text("no plan of its own, so between parts it goes back", px, TOP + 80, fs),
    text("to its depot. Compare with the active shop.", px, TOP + 100, fs),
    text("Waiting for the cart", qx - 130, qy - 28, 13.0),
    text("Cart on its way", qx - 120, qy + zone * 0.9 + 32, 13.0),
    *ring_key(px, TOP + 150, 13.0),
]
layout["clocks"] = [clock(px, TOP + 215, 18.0, label="Time (min)")]
layout["bars"] = [
    bar("Space:NumTransportersMoving", px, TOP + 260, 220.0, 18.0, 1.0, "Cart moving", "#555555"),
    bar("Carts:Q:NumInQ", px, TOP + 310, 220.0, 18.0, float(max(3, facts.queue_peak["Carts:Q"])),
        "Parts waiting for the cart", "#2ca02c"),
]
layout["title"] = "Passive transporter shop"
layout["width"] = round(px + 360.0, 1)
layout["height"] = round(y1 + 90.0, 1)
save(layout, NAME)
