"""Polish FreePathFleetYard: a dispatcher sending carts across an open yard with no aisles.

There is no guide path here: the carts drive straight between points in the model's own coordinates, and
those coordinates are what the trace records, so this layout works in them (a yard 300 by 200) instead of
rescaling anything.

Run from the repository root after capturing:
  ./gradlew :KSLExamples:showcaseCapture -PmodelName=FreePathFleetYard -Pout=build/showcase
  python3 docs/animations/polish-FreePathFleetYard.py
"""
import sys
import pathlib

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from polishkit import *  # noqa: E402,F403

NAME = "FreePathFleetYard"
layout, facts = load(NAME)

where = {l["locationName"]: (l["position"]["x"], l["position"]["y"]) for l in layout["locations"]}
UNIT = 1.0  # world units are the model's; the yard is 300 across, so a 16-unit cart is a twentieth of it

colors = {"Cart1:Body": "#1f77b4", "Cart2:Body": "#9467bd"}  # never orange: that is the charging ring
for mover in layout["movableResources"]:
    mover["color"] = colors.get(mover["name"], mover["color"])
    # A transporting mover is ringed, in red unless it has a busy colour. Red is the blocked ring in every
    # vehicle model, and nothing blocks on a free path; a ring in the cart's own colour just says "carrying".
    mover["busyColor"] = mover["color"]
    mover["size"] = 18.0
drop_types(layout, "Source")
for c in layout["objectClasses"]:
    if c["typeName"] == "Pallet":
        c["size"], c["color"], c["shape"] = 8.0, "#8c564b", "SQUARE"

# Pallets wait only at the press, so the fleet's pickup hold can be drawn there.
press = where["Press"]
layout["queues"] = [{"queueName": "Yard:AwaitingPickupHoldQ", "position": {"x": press[0], "y": press[1] + 30.0, "z": 0.0},
                     "growthDegrees": 90.0, "spacing": 10.0, "maxShown": facts.max_shown("Yard:AwaitingPickupHoldQ")}]
layout["labels"] = [
    hide("MOVABLE_RESOURCE", "Cart1:Body"), hide("MOVABLE_RESOURCE", "Cart2:Body"),
    count_only("QUEUE", "Yard:AwaitingPickupHoldQ", dx=14.0, dy=0.0),
    rename("LOCATION", "Depot", "Depot", dy=20.0),
    rename("LOCATION", "Press", "Press", dx=-22.0, dy=20.0),
    rename("LOCATION", "Ship", "Ship", dy=22.0),
]

# The yard has no aisles, so nothing marks where carts can go; faint lines between the three points say
# where they do go, which is all a free path has.
legs = [("Depot", "Press"), ("Press", "Ship"), ("Ship", "Depot")]
px, fs = 360.0, 7.0
layout["background"] = [
    *[line(*where[a], *where[b], "#e3e3e3") for a, b in legs],
    text("Pallets waiting", press[0] + 14, press[1] + 22, 7.0),
    text("An open yard", px, 40.0, 11.0, "#333333"),
    text("No guide path and nothing that blocks: carts drive", px, 56.0, fs),
    text("straight between points, so the fleet's limit is", px, 66.0, fs),
    text("how many carts there are, not where they can go.", px, 76.0, fs),
    text("A cart carries up to four pallets from the press", px, 86.0, fs),
    text("to shipping; the count beside it is how many.", px, 96.0, fs),
    text("\u25a0 Cart 1", px, 116.0, 8.0, colors["Cart1:Body"]),
    text("\u25a0 Cart 2", px, 127.0, 8.0, colors["Cart2:Body"]),
    text("A ring in the cart's colour: carrying pallets.", px, 140.0, 6.5, "#7f7f7f"),
    text("An orange ring: charging. Grey: out of service.", px, 149.0, 6.5, "#7f7f7f"),
]
layout["clocks"] = [clock(px, 166.0, 9.0, label="Time (min)")]
layout["bars"] = [
    bar("Yard:NumVehiclesOnTask", px, 178.0, 130.0, 8.0, 2.0, "Carts on a task", "#555555"),
    bar("Yard:AwaitingPickupHoldQ:NumInQ", px, 200.0, 130.0, 8.0,
        float(max(3, facts.response_peak.get("Yard:AwaitingPickupHoldQ:NumInQ", 3))), "Pallets waiting at the press", "#8c564b"),
]
layout["title"] = "Free-path fleet yard"
layout["width"] = 540.0
layout["height"] = 222.0
save(layout, NAME)
