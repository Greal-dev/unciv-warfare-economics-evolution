"""Generate TWv2 polar-projection world map using Hexworld - XXG as direct visual source.

Approach: project Hexworld's tile world-coords onto an equirectangular rectangle,
then sample that rectangle from my polar map's (lat, lon).
"""
import json, math, gzip, base64, os

R = 80
R_INNER = R - 1
# Equator at ring 45: NH gets 45 rings (2°/ring), SH gets 34 rings (2.65°/ring).
# Closer to true azimuthal projection — south no longer squashed, less wasted polar area.
R_EQ = 45

# ===== Load Hexworld terrain into a 2D grid =====
HW_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "hexworld.json")
hw_map = json.load(open(HW_PATH))

LAND_TYPES = {"Grassland", "Plains", "Desert", "Tundra", "Snow", "Mountain"}

# Compute Hexworld tile world coords + bounds.
hw_tiles_world = []  # list of (wx, wy, is_land)
for t in hw_map["tileList"]:
    p = t.get("position", {})
    hx, hy = p.get("x", 0), p.get("y", 0)
    wx = 1.5 * (hy - hx)
    wy = (math.sqrt(3) / 2.0) * (hx + hy)
    is_land = t.get("baseTerrain", "Ocean") in LAND_TYPES
    hw_tiles_world.append((wx, wy, is_land))

min_wx = min(t[0] for t in hw_tiles_world)
max_wx = max(t[0] for t in hw_tiles_world)
min_wy = min(t[1] for t in hw_tiles_world)
max_wy = max(t[1] for t in hw_tiles_world)
print(f"Hexworld world bounds: x[{min_wx:.1f}..{max_wx:.1f}] y[{min_wy:.1f}..{max_wy:.1f}]")
print(f"Hexworld tile count: {len(hw_tiles_world)}, land: {sum(1 for _,_,l in hw_tiles_world if l)}")

# Rasterize Hexworld into a fine 2D grid for fast lookup.
GRID_W = 600
GRID_H = 200
hw_grid_land = [[None]*GRID_H for _ in range(GRID_W)]  # None = unknown, True/False
for wx, wy, is_land in hw_tiles_world:
    # Map world coord to grid index
    gx = int(round((wx - min_wx) / (max_wx - min_wx) * (GRID_W - 1)))
    gy = int(round((wy - min_wy) / (max_wy - min_wy) * (GRID_H - 1)))
    if 0 <= gx < GRID_W and 0 <= gy < GRID_H:
        hw_grid_land[gx][gy] = is_land

# Fill unknown cells by nearest-neighbor expansion.
def fill_unknowns():
    changed = True
    iters = 0
    while changed and iters < 10:
        changed = False
        iters += 1
        new_grid = [row[:] for row in hw_grid_land]
        for gx in range(GRID_W):
            for gy in range(GRID_H):
                if hw_grid_land[gx][gy] is not None: continue
                # check 8 neighbors
                land = 0; sea = 0
                for dx in (-1, 0, 1):
                    for dy in (-1, 0, 1):
                        if dx == 0 and dy == 0: continue
                        nx, ny = gx+dx, gy+dy
                        if 0 <= nx < GRID_W and 0 <= ny < GRID_H:
                            v = hw_grid_land[nx][ny]
                            if v is True: land += 1
                            elif v is False: sea += 1
                if land + sea > 0:
                    new_grid[gx][gy] = land > sea
                    changed = True
        for gx in range(GRID_W):
            hw_grid_land[gx] = new_grid[gx]
    print(f"Unknown fill iterations: {iters}")

fill_unknowns()
unknowns = sum(1 for gx in range(GRID_W) for gy in range(GRID_H) if hw_grid_land[gx][gy] is None)
print(f"Remaining unknowns: {unknowns}/{GRID_W*GRID_H}")

# ===== Polar (lat, lon) → Hexworld grid lookup =====
# Treat Hexworld as equirectangular: world_x range maps to lon [-180..180],
# world_y range maps to lat [-90..90] (with y axis: positive = north).
def is_land_at(lat, lon):
    # Normalize lon
    while lon > 180: lon -= 360
    while lon < -180: lon += 360
    # Convert to Hexworld world coords (equirectangular assumption)
    wx = min_wx + (lon + 180) / 360.0 * (max_wx - min_wx)
    wy = min_wy + (lat + 90) / 180.0 * (max_wy - min_wy)
    gx = int(round((wx - min_wx) / (max_wx - min_wx) * (GRID_W - 1)))
    gy = int(round((wy - min_wy) / (max_wy - min_wy) * (GRID_H - 1)))
    if 0 <= gx < GRID_W and 0 <= gy < GRID_H:
        v = hw_grid_land[gx][gy]
        return v is True
    return False

# ===== Polar hex generation =====
def hex_distance(x, y):
    if x * y >= 0:
        return max(abs(x), abs(y))
    return abs(x) + abs(y)

def hex_to_cartesian(x, y):
    return 1.5 * (y - x), (math.sqrt(3) / 2.0) * (x + y)

def tile_to_latlon(x, y):
    d = hex_distance(x, y)
    if d == 0:
        return 90.0, 0.0
    if d <= R_EQ:
        lat = 90.0 * (1.0 - d / R_EQ)
    else:
        lat = -90.0 * (d - R_EQ) / (R - R_EQ)
    wx, wy = hex_to_cartesian(x, y)
    lon = math.degrees(math.atan2(wy, wx))
    return lat, lon

tiles = []
for x in range(-R, R + 1):
    for y in range(-R, R + 1):
        d = hex_distance(x, y)
        if d > R: continue
        if d == R:
            tiles.append({"position": {"x": x, "y": y}, "baseTerrain": "Snow"})
            continue
        if d == R_INNER:
            tiles.append({"position": {"x": x, "y": y}, "baseTerrain": "Coast"})
            continue
        lat, lon = tile_to_latlon(x, y)
        if is_land_at(lat, lon):
            tiles.append({"position": {"x": x, "y": y}, "baseTerrain": "Grassland"})
        else:
            tiles.append({"position": {"x": x, "y": y}, "baseTerrain": "Ocean"})

for t in tiles:
    p = t["position"]
    if p.get("y", 0) == 0 and p.get("x", 0) != 0:
        t["position"] = {"x": p["x"]}

land = sum(1 for t in tiles if t["baseTerrain"] == "Grassland")
ocean = sum(1 for t in tiles if t["baseTerrain"] == "Ocean")
coast = sum(1 for t in tiles if t["baseTerrain"] == "Coast")
snow = sum(1 for t in tiles if t["baseTerrain"] == "Snow")
print(f"\nTiles generated: {len(tiles)}")
print(f"  Grassland: {land} ({100*land/len(tiles):.1f}%)")
print(f"  Ocean: {ocean}, Coast: {coast}, Snow: {snow}")

map_obj = {
    "mapParameters": {
        "name": "TWv2 World Polar - R80",
        "type": "Custom",
        "shape": "Hexagonal",
        "mapSize": {"name": "Custom", "radius": R, "width": 0, "height": 0},
        "worldWrap": True,
        "createdWithVersion": "4.1.12-TWv2",
        "seed": 20260521
    },
    "tileList": tiles,
    "startingLocations": []
}

raw = json.dumps(map_obj, separators=(",", ":")).encode("utf-8")
gz = gzip.compress(raw)
b64 = base64.b64encode(gz).decode("ascii")

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                        "android", "assets", "maps", "TWv2 World Polar - R80")
out_path = os.path.normpath(out_path)
with open(out_path, "w") as f:
    f.write(b64)
print(f"Wrote: {out_path}")
