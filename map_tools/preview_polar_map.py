"""ASCII preview of the polar map. Center = North Pole."""
import json, gzip, base64, sys, math, os

src = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                   "android", "assets", "maps", "TWv2 World Polar - R80")
src = os.path.normpath(src)

raw_b64 = open(src).read().strip()
raw = gzip.decompress(base64.b64decode(raw_b64))
m = json.loads(raw)
R = m["mapParameters"]["mapSize"]["radius"]
pos2 = {}
for t in m["tileList"]:
    p = t["position"]
    x = p.get("x", 0); y = p.get("y", 0)
    pos2[(x, y)] = t["baseTerrain"]

SYM = {"Ocean":".", "Coast":",", "Grassland":"#", "Snow":"*", "Plains":"o",
       "Desert":"~", "Tundra":"-", "Mountain":"^"}

# Map hex (x,y) onto a Cartesian grid for visual.
# Pointy-top: px = x + y/2, py = y * sqrt(3)/2
SCALE_X = 1.0
SCALE_Y = 0.866
# We'll render in a square of side ~ 2R, sampling by lookup.
# Image size: 161 cols (-80..80) wide on x-axis, ~140 rows tall.
import math
W = 2*R + 1
H = int(round(2 * R * math.sqrt(3) / 2)) + 1   # ~139

img = [[" "] * W for _ in range(H)]
for (x, y), terr in pos2.items():
    px = x + y / 2.0
    py = y * math.sqrt(3) / 2.0
    col = int(round(px + R))
    row = int(round(py + R * math.sqrt(3) / 2))
    if 0 <= col < W and 0 <= row < H:
        s = SYM.get(terr, "?")
        img[row][col] = s

# Print, flipping vertically so North (lower y) is at top — wait, in my projection,
# the North Pole is at (0,0) which is the center. So center of image is the pole.
# y axis in hex isn't north/south directly. Just render as-is.
for row in img:
    print("".join(row))
