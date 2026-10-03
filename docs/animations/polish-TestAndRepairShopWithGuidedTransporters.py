"""Polish TestAndRepairShopWithGuidedTransporters: the textbook test-and-repair shop, with workers carrying
parts along a one-way aisle between diagnostics, three test stations and repair.

Run from the repository root after capturing:
  ./gradlew :KSLExamples:showcaseCapture -PmodelName=TestAndRepairShopWithGuidedTransporters -Pout=build/showcase
  python3 docs/animations/polish-TestAndRepairShopWithGuidedTransporters.py
"""
import sys
import pathlib

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from polishkit import *  # noqa: E402,F403

NAME = "TestAndRepairShopWithGuidedTransporters"
SPACE = "ShopTransport"
layout, facts = load(NAME)
path = PathFacts(SHOWCASE / f"{NAME}.atf")

# A 60 by 65 loop with 5-unit zones. The auto-layout stacked every station's queue and server in one column
# beside the loop, far from the stations they belong to; here each station's server stands beside its own
# point on the aisle, outside the loop, with its queue growing away from the aisle.
SCALE = 7.0
LEFT, TOP = 190.0, 150.0
x0, y0, x1, y1 = frame_path(layout, path, SPACE, LEFT, TOP, scale=SCALE)
snap_locations(layout, path, SPACE)
zone = path.mean_zone(SPACE) * SCALE
layout["guidedPaths"][0]["linkWidth"] = 3.0

style_carts(layout, size=round(zone * 0.7, 1), colors=["#1f77b4", "#9467bd", "#17becf"])
for c in layout["objectClasses"]:
    if c["typeName"] == "Part":
        c["size"], c["color"] = round(zone * 0.4, 1), "#ff7f0e"

at = {l["locationName"]: (l["position"]["x"], l["position"]["y"]) for l in layout["locations"]}
CELL = 22.0
GAP = 48.0
# station -> (resource, where the server stands relative to the station, the queue's growth direction)
plan = {
    "DiagnosticStation": ("DiagnosticWorkers", (0.0, GAP), 180.0),
    "TestStation1": ("Test1", (0.0, GAP), 180.0),
    "TestStation2": ("Test2", (GAP + 10, 0.0), 90.0),
    "TestStation3": ("Test3", (GAP + 10, 0.0), 270.0),
    "RepairStation": ("RepairWorkers", (0.0, -GAP), 180.0),
}
placed = {}
for station, (resource, (dx, dy), growth) in plan.items():
    sx, sy = at[station]
    placed[resource] = (sx + dx, sy + dy, growth)
for r in layout["resources"]:
    if r["resourceName"] in placed:
        x, y, _ = placed[r["resourceName"]]
        r["position"] = {"x": round(x, 1), "y": round(y, 1), "z": 0.0}
        r["size"] = CELL
        # Green shades, not the default green and red: red is the blocked ring on the workers in this frame.
        r["idleColor"], r["busyColor"] = "#c7e9c0", "#31a354"
for q in layout["queues"]:
    owner = q["queueName"].removesuffix(":Q")
    if owner in placed:
        x, y, growth = placed[owner]
        half = facts.half_width(owner, CELL)
        if growth == 180.0:
            q["position"] = {"x": round(x - half - CELL * 0.6, 1), "y": round(y, 1), "z": 0.0}
        else:
            step = CELL * 0.9 if growth == 90.0 else -CELL * 0.9
            q["position"] = {"x": round(x, 1), "y": round(y + step, 1), "z": 0.0}
        q["growthDegrees"] = growth
        q["spacing"] = round(CELL * 0.6, 1)
        q["maxShown"] = facts.max_shown(q["queueName"])
# Parts waiting for a transport worker wait at whichever station they have just left, so no single place
# can show them; the panel counts them instead.
drop_queues(layout, "TransportWorkerPool:Q")

shown = {"DiagnosticStation": "Diagnostics", "TestStation1": "Test 1", "TestStation2": "Test 2",
         "TestStation3": "Test 3", "RepairStation": "Repair"}
layout["labels"] = (
    [hide("RESOURCE", r) for r, _, _ in plan.values()]
    + [count_only("QUEUE", f"{r}:Q", dx=-8.0, dy=22.0) for r, _, _ in plan.values()]
    + [rename("LOCATION", "DiagnosticStation", "Diagnostics", dx=-72.0, dy=4.0),
       rename("LOCATION", "TestStation1", "Test 1", dy=-14.0),
       rename("LOCATION", "TestStation2", "Test 2", dx=10.0, dy=-14.0),
       rename("LOCATION", "TestStation3", "Test 3", dx=10.0, dy=-14.0),
       rename("LOCATION", "RepairStation", "Repair", dy=-14.0)]
    + [hide("LOCATION", f"Park{i}") for i in range(1, 4)]
)

px = x1 + 190.0
fs = 15.0
layout["background"] = [
    text("Test and repair, carried by hand", px, TOP - 40, 22.0, "#333333"),
    text("The textbook shop, with workers carrying parts along", px, TOP - 10, fs),
    text("a one-way aisle: diagnostics, three test stations,", px, TOP + 10, fs),
    text("repair, and back. A worker can be held up by the", px, TOP + 30, fs),
    text("worker in front of it, which the movable-resource", px, TOP + 50, fs),
    text("version of this shop cannot show. Idle workers park", px, TOP + 70, fs),
    text("on the spurs beside diagnostics.", px, TOP + 90, fs),
    *cart_key(layout, px, TOP + 130, 14.0, {f"Worker{i}": f"Worker {i}" for i in range(1, 4)}),
    *ring_key(px, TOP + 210, 13.0),
    text("Station cells: dark green busy, light green idle.", px, TOP + 250, 13.0),
]
layout["clocks"] = [clock(px, TOP + 300, 18.0, label="Time (min)")]
layout["bars"] = [
    bar("ShopTransport:NumTransportersBlocked", px, TOP + 345, 220.0, 18.0, 3.0, "Workers blocked", "#d62728"),
    bar("TransportWorkerPool:Q:NumInQ", px, TOP + 395, 220.0, 18.0,
        float(max(3, facts.queue_peak["TransportWorkerPool:Q"])), "Parts waiting for a worker", "#ff7f0e"),
    bar("NumInSystem", px, TOP + 445, 220.0, 18.0,
        float(max(5, round(facts.response_peak.get("NumInSystem", 10) * 1.1))), "Parts in the shop", "#555555"),
]
layout["title"] = "Test and repair shop"
layout["width"] = round(px + 380.0, 1)
layout["height"] = round(max(y1 + GAP + 60.0, TOP + 490.0) + 40.0, 1)
save(layout, NAME)
