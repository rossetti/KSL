"""Polish ActiveFleetShop: the PassiveTransporterShop modelled actively, a dispatcher assigning each task.

Drawn exactly like PassiveTransporterShop so that the two can be compared frame for frame.

Run from the repository root after capturing:
  ./gradlew :KSLExamples:showcaseCapture -PmodelName=ActiveFleetShop -Pout=build/showcase
  python3 docs/animations/polish-ActiveFleetShop.py
"""
import sys
import pathlib

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from polishkit import *  # noqa: E402,F403

NAME = "ActiveFleetShop"
layout, facts = load(NAME)
path = PathFacts(SHOWCASE / f"{NAME}.atf")

SCALE = 4.0
LEFT, TOP = 190.0, 70.0
x0, y0, x1, y1 = frame_path(layout, path, "Agv:Space", LEFT, TOP, scale=SCALE)
zone = path.mean_zone("Agv:Space") * SCALE
layout["guidedPaths"][0]["linkWidth"] = 3.0

style_carts(layout, size=round(zone * 0.55, 1))
drop_types(layout, "Source")
for c in layout["objectClasses"]:
    if c["typeName"] == "Part":
        c["size"], c["color"] = round(zone * 0.3, 1), "#2ca02c"

# In the active paradigm a part posts a task and waits to be collected; it never queues for the cart itself,
# so the only place it waits is the fleet's pickup hold, which the auto-layout leaves out as bookkeeping.
qx, qy = waiting_queue(layout, facts, "Agv:AwaitingPickupHoldQ", "EntryStation", zone)
layout["labels"] = [
    count_only("QUEUE", "Agv:AwaitingPickupHoldQ", dx=-10.0, dy=26.0),
    rename("LOCATION", "EntryStation", "Entry", dy=30.0),
    rename("LOCATION", "ExitStation", "Exit", dx=-34.0, dy=4.0),
    rename("LOCATION", "CartDepot", "Depot", dx=30.0, dy=4.0),
]

px = x1 + 150.0
fs = 15.0
layout["background"] = [
    text("Active: the dispatcher decides", px, TOP + 10, 22.0, "#333333"),
    text("Each part posts a task and waits to be collected.", px, TOP + 40, fs),
    text("A dispatcher commits the cart to a task, and the cart", px, TOP + 60, fs),
    text("carries it out. Turn on Show assignments to see each", px, TOP + 80, fs),
    text("commitment as a line to the cart's next stop.", px, TOP + 100, fs),
    text("Waiting to be collected", qx - 150, qy - 28, 13.0),
    *ring_key(px, TOP + 150, 13.0),
]
layout["clocks"] = [clock(px, TOP + 215, 18.0, label="Time (min)")]
layout["bars"] = [
    bar("Agv:NumVehiclesOnTask", px, TOP + 260, 220.0, 18.0, 1.0, "Cart on a task", "#555555"),
    bar("Agv:Dispatcher:TaskQ:NumInQ", px, TOP + 310, 220.0, 18.0,
        float(max(3, facts.queue_peak["Agv:Dispatcher:TaskQ"])), "Tasks not yet assigned", "#2ca02c"),
]
layout["title"] = "Active fleet shop"
layout["width"] = round(px + 360.0, 1)
layout["height"] = round(y1 + 90.0, 1)
save(layout, NAME)
