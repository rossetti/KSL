#!/usr/bin/env python3
"""Produce the polished showcase layout for Example17TandemBlocking.

Input:  build/showcase/Example17TandemBlocking.lay.json  (the auto-layout, via showcaseCapture)
        build/showcase/Example17TandemBlocking.atf       (capacities, queue peaks)
Output: build/showcase/polished/Example17TandemBlocking.lay.json

The generator gets the arrangement right on its own here -- three stations left to right in flow order,
each queue's head clear of its block -- because it mines the flow from the trace and the buffer genuinely
sits between the two workers in every part's seize sequence.

What it cannot know is what the picture is *about*. This model exists to show blocking, and blocking is not
a thing you can see by looking at a resource: a blocked server and a busy server are both "not idle". The
whole polish is naming the three queues so that the blockage is readable, because in this model each queue
means something quite different and none of them is what its name says.
"""
import json, pathlib, collections

SRC = pathlib.Path("build/showcase/Example17TandemBlocking.lay.json")
ATF = pathlib.Path("build/showcase/Example17TandemBlocking.atf")
OUT = pathlib.Path("build/showcase/polished/Example17TandemBlocking.lay.json")

d = json.loads(SRC.read_text())

peak = collections.Counter()
for line in ATF.read_text().splitlines():
    if '"QueueLengthChanged"' in line:
        e = json.loads(line)
        peak[e["queueName"]] = max(peak[e["queueName"]], e["length"])

# ── 1. The line ─────────────────────────────────────────────────────────────────────────────────────
SIZE = 44.0
ROW_Y = 210.0
PLACE = {"worker1": 250.0, "buffer": 560.0, "worker2": 870.0}
W, H = 1080.0, 470.0
d["title"] = "Tandem queue with blocking — one buffer space between two stations"
d["width"], d["height"] = W, H

for r in d["resources"]:
    r["position"] = {"x": PLACE[r["resourceName"]], "y": ROW_Y, "z": 0.0}
    r["size"] = SIZE
    r["showValue"] = False
for q in d["queues"]:
    owner = q["queueName"].removesuffix(":Q")
    q["position"] = {"x": PLACE[owner] - (SIZE / 2 + 18.0), "y": ROW_Y, "z": 0.0}
    q["growthDegrees"] = 180.0
    q["spacing"] = 22.0
    # The peak is what each queue ever reached: seven at stage one, one apiece at the other two, because a
    # single buffer space can hold exactly one blocked part and one waiting one.
    q["maxShown"] = max(2, peak[q["queueName"]])

for oc in d["objectClasses"]:
    oc["size"] = round(SIZE * 0.55, 1)
    oc["color"] = "#1f77b4"

# ── 2. Naming what is actually happening ────────────────────────────────────────────────────────────
# Every queue in this model means something other than "a line for this resource", and getting that across
# is the difference between a picture of three boxes and a picture of blocking:
#
#   worker1:Q  parts that have not started -- an ordinary waiting line.
#   buffer:Q   parts that have FINISHED stage one and cannot leave it, because the buffer is full. Stage one
#              is standing idle-but-occupied behind them. This queue IS the blockage.
#   worker2:Q  a part sitting in the buffer waiting for stage two. While it is there the buffer is full,
#              which is what causes the queue above.
#
# So the chain reads right to left: stage two is busy, so the buffer is held, so stage one cannot let go.
SHORT = {"worker1": "Stage 1", "buffer": "Buffer", "worker2": "Stage 2"}
d["labels"] = (
    [{"kind": "RESOURCE", "name": n, "text": t, "dy": -(SIZE / 2 + 14)} for n, t in SHORT.items()]
    + [{"kind": "QUEUE", "name": f"{n}:Q", "visible": False, "valueVisible": True,
        "valueDx": -6.0, "valueDy": 26.0} for n in SHORT]
)

def text(s, x, y, size=15.0, color="#666666"):
    return {"kind": "TEXT", "points": [{"x": x, "y": y, "z": 0.0}], "text": s, "color": color,
            "strokeWidth": 1.0, "imageRef": None, "fontSize": size, "fontFamily": None}

CAPTION_Y = ROW_Y + 92.0
d["background"] = [
    text("A part must take the buffer before it can let go of stage 1, and take stage 2 before it can let go of the buffer.", 36.0, 92.0),
    text("So when the buffer is full, stage 1 finishes its work and then just stands there holding the part: blocked, not busy.", 36.0, 116.0),
    # Each line sits under the queue it explains, so the three meanings are read off the picture itself.
    text("waiting to start", PLACE["worker1"] - 150.0, CAPTION_Y, 13.5, "#999999"),
    text("finished stage 1 —", PLACE["buffer"] - 150.0, CAPTION_Y, 13.5, "#d62728"),
    text("stage 1 is blocked", PLACE["buffer"] - 150.0, CAPTION_Y + 20.0, 13.5, "#d62728"),
    text("in the buffer,", PLACE["worker2"] - 150.0, CAPTION_Y, 13.5, "#999999"),
    text("waiting for stage 2", PLACE["worker2"] - 150.0, CAPTION_Y + 20.0, 13.5, "#999999"),
]

d["clocks"] = [{"position": {"x": 36.0, "y": 50.0, "z": 0.0}, "format": "0.0", "label": "Time",
                "fontSize": 22.0}]
d["bars"] = [
    {"responseName": "TandemBlocking:NumInSystem", "position": {"x": 36.0, "y": 400.0, "z": 0.0},
     "width": 380.0, "height": 24.0, "maxValue": 16.0, "color": "#1f77b4", "label": "Number in system"}
]
d["plots"] = [
    {"responseName": "TandemBlocking:NumInSystem", "position": {"x": 620.0, "y": 356.0, "z": 0.0},
     "width": 420.0, "height": 84.0, "color": "#1f77b4", "label": "Number in system over time",
     "windowDuration": None}
]

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(d, indent=1))
print(f"wrote {OUT}  ({int(W)}x{int(H)}, queue peaks "
      f"{ {k: v for k, v in sorted(peak.items())} })")
