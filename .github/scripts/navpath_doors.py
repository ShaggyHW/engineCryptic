#!/usr/bin/env python3
"""Adds the doors and gates the route planner's own data lacks, and the rooms behind them, before its graph is built.

navpathService ships a few hundred doors; the game has thousands, and a route planner that does not know a door
walks around it or finds no way at all. Its tiles were flooded out through its own doors only, so the rooms behind
the others are missing too. The engine's `:tools:navDoors` writes both lists from the game cache: navpath-doors.tsv,
every door by the rule the engine's local planner opens doors by, and navpath-tiles.tsv, the floor of each room
behind one that hangs off the planner's ground, with walk masks in the planner's bit order. Everything upstream does
not already have is added to a copy of the database, never to upstream's own file.

    python3 navpath-doors.py --db worldReachableTiles.db --doors navpath-doors.tsv --tiles navpath-tiles.tsv --out navpath.db
"""
import argparse
import shutil
import sqlite3

# One step to the other side of the wall, by the side of its tile the wall stands on: west, north, east, south.
ACROSS = {0: (-1, 0), 1: (0, 1), 2: (1, 0), 3: (0, -1)}
# The walk-mask bits for stepping across that wall and back: the planner's bits are 0 west, 1 south, 2 east, 3 north.
STEP_BITS = {0: (1 << 0, 1 << 2), 1: (1 << 3, 1 << 1), 2: (1 << 2, 1 << 0), 3: (1 << 1, 1 << 3)}
DOOR_COST_MS = 1200
# How far from the door an object link for the same loc may start and still be that door, modelled upstream's way.
OBJECT_REACH = 2


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", required=True)
    parser.add_argument("--doors", required=True)
    parser.add_argument("--tiles")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    shutil.copyfile(args.db, args.out)
    db = sqlite3.connect(args.out)
    tiles_added = 0
    if args.tiles:
        with open(args.tiles, encoding="utf8") as lines:
            for line in lines:
                parts = line.rstrip("\n").split("\t")
                if len(parts) != 4:
                    continue
                x, y, plane, mask = (int(p) for p in parts)
                region = ((x >> 6) << 8) | (y >> 6)
                tiles_added += db.execute(
                    "INSERT OR IGNORE INTO tiles (x, y, plane, walk_mask, RegionID) VALUES (?, ?, ?, ?, ?)", (x, y, plane, mask, region)
                ).rowcount
    known = set()
    for row in db.execute(
        "SELECT location_closed_x, location_closed_y, location_closed_plane, location_open_x, location_open_y, location_open_plane,"
        " tile_inside_x, tile_inside_y, tile_inside_plane, tile_outside_x, tile_outside_y, tile_outside_plane FROM teleports_door_nodes"
    ):
        for i in range(0, 12, 3):
            if row[i] is not None:
                known.add((row[i], row[i + 1], row[i + 2]))

    objects = db.execute(
        "SELECT object_id, orig_min_x, COALESCE(orig_max_x, orig_min_x), orig_min_y, COALESCE(orig_max_y, orig_min_y), orig_plane"
        " FROM teleports_object_nodes WHERE orig_min_x IS NOT NULL AND orig_min_y IS NOT NULL"
    ).fetchall()

    def modelled_as_object(loc_id, tiles):
        """Upstream already crosses here with an object link of its own, such as a gate that asks questions first."""
        for object_id, min_x, max_x, min_y, max_y, plane in objects:
            reach = OBJECT_REACH if object_id == loc_id else 0
            for x, y, p in tiles:
                if (plane is None or plane == p) and min_x - reach <= x <= max_x + reach and min_y - reach <= y <= max_y + reach:
                    return True
        return False

    def walk_mask(x, y, plane):
        row = db.execute("SELECT walk_mask FROM tiles WHERE x = ? AND y = ? AND plane = ?", (x, y, plane)).fetchone()
        return None if row is None else row[0]

    next_id = (db.execute("SELECT MAX(id) FROM teleports_door_nodes").fetchone()[0] or 0) + 1
    added = skipped_known = skipped_unwalkable = skipped_open = skipped_object = 0
    with open(args.doors, encoding="utf8") as lines:
        for line in lines:
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 6:
                continue
            loc_id, option, x, y, plane, side = parts[0], parts[1], int(parts[2]), int(parts[3]), int(parts[4]), int(parts[5])
            dx, dy = ACROSS[side]
            far = (x + dx, y + dy, plane)
            if (x, y, plane) in known:
                skipped_known += 1
                continue
            # The graph only has nodes on tiles a player can reach; a door into nowhere adds nothing.
            near_mask, far_mask = walk_mask(x, y, plane), walk_mask(*far)
            if near_mask is None or far_mask is None:
                skipped_unwalkable += 1
                continue
            # A door that stood open when the planner's map was recorded is already walked through.
            out_bit, back_bit = STEP_BITS[side]
            if near_mask & out_bit and far_mask & back_bit:
                skipped_open += 1
                continue
            if modelled_as_object(int(loc_id), [(x, y, plane), far]):
                skipped_object += 1
                continue
            db.execute(
                "INSERT INTO teleports_door_nodes (id, real_id_closed, location_closed_x, location_closed_y, location_closed_plane,"
                " tile_inside_x, tile_inside_y, tile_inside_plane, tile_outside_x, tile_outside_y, tile_outside_plane, open_action, cost)"
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (next_id, int(loc_id), x, y, plane, far[0], far[1], far[2], x, y, plane, option, DOOR_COST_MS),
            )
            known.add((x, y, plane))
            next_id += 1
            added += 1
    db.commit()
    db.close()
    print(f"tiles: {tiles_added} added behind doors")
    print(f"doors: {added} added, {skipped_known} already known, {skipped_open} already walked through, {skipped_object} crossed by an object link, {skipped_unwalkable} on tiles nobody reaches")


if __name__ == "__main__":
    main()
