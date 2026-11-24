#!/usr/bin/env python3
"""Builds the map navpath plans on: upstream's worldReachableTiles.db with the web walker's links.json merged in.

    python3 .github/scripts/navpath_map.py <worldReachableTiles.db> <links.json> <out.db>

The reverse of navpath_links.py. Upstream's database is copied (never opened for writing) and every link, fairy ring
and requirement links.json holds that upstream lacks is added as the rows navpath reads, so the planner routes with
what we measured and the engine's NavRouteMapper turns the answer back into the same WebLink.

"Lacks" uses navpath_links.py's notion of the same way through, kept to one direction. A link of ours that is the same
way as an upstream chain is not added again; the chain's first row takes our requirements on top of its own, and a
link we measured in-game also moves the chain's start and landing areas to ours. A measured link that answers a prompt
with steps stands in for upstream's way through the same object, whose chain is removed, as navpath_links.py keeps it
out of links.json. One that answers with dialogue text cannot: no row carries the text back to the walker, so
upstream's way to the same landing stays, with our areas and requirements, and ours is not added.

A link becomes a chain: the row for its first action carries its requirements, its cost and, unless it is a door that
goes on, where it lands; each later step (an interface click or another object) is a row the one before names as its
next node. A door row is the way through both ways, as navpath has it, so a door of ours whose way back is the door
just added is covered by it. Rows navpath would drop are skipped instead and reported: a start area with no walkable
tile, a landing area with no tile at all, an area wider than any real crossing.

A requirement equal to one upstream has (same key, value and comparison) uses upstream's id; a new one keeps our id
when upstream has not used it and takes the next unused id when it has. Fairy rings are the same ring when the object,
tile, code and requirements all match: a ring listed twice is two ways of being allowed to use it.

The output is rebuilt with VACUUM INTO, so the same inputs always give the same bytes.
"""
import collections
import json
import pathlib
import shutil
import sqlite3
import sys
import tempfile

import navpath_links as nl

NEXT_TYPE = {"object": "object", "ifslot": "ifslot", "useon": "use_on", "door": "door", "npc": "npc", "item": "item",
             "lodestone": "lodestone", "spell": "spell"}
KIND_NODE = {"OBJECT": "object", "DOOR": "door", "NPC": "npc", "USE_ON": "useon", "ITEM": "item",
             "INTERFACE": "ifslot", "LODESTONE": "lodestone", "POA": "poa_item", "SPELL": "spell"}
BOX_TABLES = ("object", "npc", "useon")


class Map:
    def __init__(self, db):
        self.db = db
        self.next_ids = {}
        self.identities = {}

    def conditions(self, ids):
        """What a set of requirement ids asks for, so two ids for one condition compare equal."""
        if not self.identities:
            self.identities = {r["id"]: nl.requirement_identity(r) for r in self.db.execute("select * from teleports_requirements")}
        return frozenset(self.identities[i] for i in ids)

    def allocate(self, node):
        table = nl.TABLES.get(node, node)
        if table not in self.next_ids:
            self.next_ids[table] = (self.db.execute(f'select max(id) from "{table}"').fetchone()[0] or 0) + 1
        self.next_ids[table] += 1
        return self.next_ids[table] - 1

    def insert(self, node, row):
        table = nl.TABLES.get(node, node)
        columns = ", ".join(row)
        self.db.execute(f'insert into "{table}" ({columns}) values ({", ".join("?" * len(row))})', list(row.values()))

    def tiles(self, min_x, max_x, min_y, max_y, plane):
        return {(r[0], r[1]): r[2] for r in self.db.execute(
            "select x, y, walk_mask from tiles where plane=? and x between ? and ? and y between ? and ?",
            (plane, min_x, max_x, min_y, max_y))}

    def nearest_walkable(self, min_x, max_x, min_y, max_y, plane):
        """The walkable tile nearest the area's centre, lowest (y, x) on a tie: the tile navpath lands on."""
        tiles = self.tiles(min_x, max_x, min_y, max_y, plane)
        best = None
        for y in range(min_y, max_y + 1):
            for x in range(min_x, max_x + 1):
                if tiles.get((x, y)):
                    d = (2 * x - min_x - max_x) ** 2 + (2 * y - min_y - max_y) ** 2
                    if best is None or d < best[0]:
                        best = (d, x, y)
        return None if best is None else (best[1], best[2])

    def start_tile(self, link):
        """Where navpath starts the link: the area's min corner, the tile it reads, or the nearest walkable one."""
        box = (link["fromMinX"], link["fromMaxX"], link["fromMinY"], link["fromMaxY"], link["fromPlane"])
        if self.tiles(box[0], box[0], box[2], box[2], box[4]).get((box[0], box[2])):
            return box[0], box[2]
        return self.nearest_walkable(*box)

    def landing_tile(self, link):
        box = (link["toMinX"], link["toMaxX"], link["toMinY"], link["toMaxY"], link["toPlane"])
        found = self.nearest_walkable(*box)
        if found is None and (box[0], box[2]) in self.tiles(box[0], box[0], box[2], box[2], box[4]):
            found = (box[0], box[2])
        return found


def ids_text(ids):
    return ";".join(str(i) for i in sorted(set(ids))) or None


def adopt_requirements(db, ours):
    """Each of our requirement ids as the database's, adding those it lacks; returns (ids, added, renumbered)."""
    by_identity, by_id = {}, {}
    for r in db.execute("select * from teleports_requirements order by id"):
        by_id[r["id"]] = nl.requirement_identity(r)
        by_identity.setdefault(by_id[r["id"]], r["id"])
    used = set(by_id)
    ids, added, renumbered = {}, [], []
    for key in sorted(ours, key=int):
        requirement = ours[key]
        identity = nl.requirement_identity(requirement)
        if by_id.get(int(key)) == identity:
            ids[int(key)] = int(key)
            continue
        if identity not in by_identity:
            target = int(key)
            while target in used:
                target += 1
            used.add(target)
            by_identity[identity] = target
            db.execute("insert into teleports_requirements (id, metaInfo, key, value, comparison) values (?, ?, ?, ?, ?)",
                       (target, requirement.get("about"), requirement["key"], str(requirement["value"]),
                        requirement["comparison"]))
            added.append(target)
        ids[int(key)] = by_identity[identity]
        if ids[int(key)] != int(key):
            renumbered.append((int(key), ids[int(key)]))
    return ids, added, renumbered


def chain_rows(db, node, row_id):
    rows, seen = [], set()
    while node in nl.TABLES and (node, row_id) not in seen:
        seen.add((node, row_id))
        row = db.execute(f'select * from "{nl.TABLES[node]}" where id=?', (row_id,)).fetchone()
        if row is None:
            break
        rows.append((node, row))
        node, row_id = nl.next_of(row)
    return rows


def destination_box(link):
    return {"dest_min_x": link["toMinX"], "dest_max_x": link["toMaxX"], "dest_min_y": link["toMinY"],
            "dest_max_y": link["toMaxY"], "dest_plane": link["toPlane"]}


def origin_box(link, start):
    return {"orig_min_x": start[0], "orig_max_x": max(start[0], link["fromMaxX"]), "orig_min_y": start[1],
            "orig_max_y": max(start[1], link["fromMaxY"]), "orig_plane": link["fromPlane"]}


def follow_up_row(step):
    if isinstance(step, list):
        return "ifslot", {"interface_id": step[0], "component_id": step[1], "slot_id": step[2] if len(step) > 2 else -1,
                          "click_id": step[3] if len(step) > 3 else 1, "cost": 0}
    return "object", {"match_type": "id", "object_id": step["objectId"], "action": step["action"],
                      "search_radius": step.get("searchRadius"), "cost": 0}


def first_row(link, start, landing):
    """The row of a link's first action, or (None, reason)."""
    kind = link["kind"]
    if kind == "OBJECT":
        return {"match_type": "id", "object_id": link["objectId"], "action": link["action"],
                "search_radius": link["searchRadius"], **origin_box(link, start), **destination_box(link)}, None
    if kind == "NPC":
        row = {"match_type": "id" if link.get("npcId") is not None else "name", "npc_id": link.get("npcId"),
               "npc_name": link.get("npcName"), "action": link["action"], "search_radius": link["searchRadius"]}
        return {**row, **origin_box(link, start), **destination_box(link)}, None
    if kind == "USE_ON":
        return {"item_id": link["itemId"], "object_id": link["objectId"], **origin_box(link, start),
                **destination_box(link)}, None
    if kind == "DOOR":
        row = {"real_id_closed": link["objectId"], "open_action": link["action"], "tile_outside_x": start[0],
               "tile_outside_y": start[1], "tile_outside_plane": link["fromPlane"]}
        if not link.get("steps"):
            row.update({"tile_inside_x": landing[0], "tile_inside_y": landing[1], "tile_inside_plane": link["toPlane"]})
        return row, None
    if kind == "ITEM":
        row = {"match_type": "id" if link.get("itemId") is not None else "name", "item_id": link.get("itemId"),
               "name": link.get("itemName"), "action": link["action"]}
        return {**row, **destination_box(link)}, None
    if kind == "LODESTONE":
        return {"lodestone": link["lodestone"], "dest_x": landing[0], "dest_y": landing[1],
                "dest_plane": link["toPlane"]}, None
    if kind == "SPELL":
        return {"spell_id": link["spellId"], "action": link["action"], **destination_box(link)}, None
    if kind == "POA":
        if link.get("steps"):
            return None, "a Passage of the Abyss link with steps (navpath does not chain POA rows)"
        menu = (link.get("menu") or []) + [None, None]
        return {"item_id": link["itemId"], "action": link["action"], "action2": menu[0], "action3": menu[1],
                **destination_box(link)}, None
    if kind == "INTERFACE":
        node, row = follow_up_row(link["steps"][0])
        return {**row, **destination_box(link)}, None
    return None, f"a {kind} link"


def add_link(nav, link, start, landing, requirements):
    """Inserts the link's chain; the reason it cannot be, or None."""
    node = KIND_NODE[link["kind"]]
    row, refused = first_row(link, start, landing)
    if row is None:
        return refused
    steps = list(link.get("steps") or [])
    if link["kind"] == "INTERFACE":
        steps = steps[1:]
    chain = [follow_up_row(step) for step in steps]
    if link["kind"] == "DOOR" and chain:
        chain[-1][1].update(destination_box(link))
    ids = [nav.allocate(node)] + [nav.allocate(n) for n, _ in chain]
    row.update({"id": ids[0], "cost": link["costTiles"] * nl.TILE_MS, "requirements": ids_text(requirements)})
    rows = [(node, row)] + [(n, {"id": i, **r}) for (n, r), i in zip(chain, ids[1:])]
    for (n, r), (next_node, next_row) in zip(rows, rows[1:]):
        r.update({"next_node_type": NEXT_TYPE[next_node], "next_node_id": next_row["id"]})
    for n, r in rows:
        nav.insert(n, r)
    return None


def remove_chain(db, node, row_id):
    """Deletes a chain start and every later row nothing else still names."""
    for n, row in chain_rows(db, node, row_id):
        if (n, row["id"]) != (node, row_id) and (n, row["id"]) in nl.chained_into(db):
            break
        db.execute(f'delete from "{nl.TABLES[n]}" where id=?', (row["id"],))


def references(db, node, row_id):
    count = 0
    for table in nl.TABLES.values():
        if "next_node_type" in [c[1] for c in db.execute(f'pragma table_info("{table}")')]:
            for r in db.execute(f'select next_node_type from "{table}" where next_node_id=?', (row_id,)):
                count += nl.node_kind(r[0]) == node
    return count


def tighten(nav, node, row_id, requirements):
    row = nav.db.execute(f'select requirements from "{nl.TABLES[node]}" where id=?', (row_id,)).fetchone()
    have = set(nl.requirement_ids(row["requirements"]) or [])
    missing = [r for r in requirements if not nav.conditions([r]) <= nav.conditions(have)]
    if not missing:
        return False
    update(nav.db, node, row_id, {"requirements": ids_text(have | set(missing))})
    return True


def update(db, node, row_id, values):
    assignments = ", ".join(f"{column}=?" for column in values)
    db.execute(f'update "{nl.TABLES[node]}" set {assignments} where id=?', [*values.values(), row_id])


def remeasure(nav, link, node, row_id):
    """Moves an upstream chain's start and landing areas to the ones we measured, where navpath can use them."""
    if node not in BOX_TABLES:
        return False
    rows = chain_rows(nav.db, node, row_id)
    start = nav.start_tile(link)
    if start is not None:
        update(nav.db, node, row_id, origin_box(link, start))
    landing = [i for i, (n, r) in enumerate(rows) if n not in ("door", "lodestone") and nl.has(r, "dest_min_x")]
    if (landing and nav.landing_tile(link) is not None
            and all(references(nav.db, n, r["id"]) == 1 for n, r in rows[1:landing[-1] + 1])):
        n, r = rows[landing[-1]]
        update(nav.db, n, r["id"], destination_box(link))
    return True


def centre(link, end):
    return ((link[f"{end}MinX"] + link[f"{end}MaxX"]) / 2, (link[f"{end}MinY"] + link[f"{end}MaxY"]) / 2,
            link[f"{end}Plane"] * 1000)


def apart(a, b):
    return sum(abs(p - q) for p, q in zip(a, b))


def same_way(ours, other):
    """navpath_links' same way, kept to one direction: its slack would let a short crossing match its own way back."""
    if not nl.same_way(ours, other):
        return False
    if "fromMinX" not in ours or "fromMinX" not in other:
        return True
    along = apart(centre(ours, "from"), centre(other, "from")) + apart(centre(ours, "to"), centre(other, "to"))
    against = apart(centre(ours, "from"), centre(other, "to")) + apart(centre(ours, "to"), centre(other, "from"))
    return along <= against


def answers_by_text(link):
    """A measured answer given as dialogue text: a route row cannot carry it, so upstream's clicks answer instead."""
    return bool(link.get("measured") and link.get("choice"))


def way_key(link):
    return link["kind"], link.get("objectId"), link.get("action")


def merge_links(nav, ours, ids):
    db = nav.db
    upstream = collections.defaultdict(list)
    for link, node, row_id in nl.ways(db):
        if link is not None:
            upstream[way_key(link)].append((link, node, row_id))

    known = 0
    skipped = collections.Counter()
    added_kinds = collections.Counter()
    tightened, remeasured, removed = set(), set(), set()

    for link in ours:
        if not (link.get("measured") and link.get("steps") and not link.get("choice")):
            continue
        for way, node, row_id in upstream[way_key(link)]:
            if nl.prompt_twin(link, way) and not same_way(link, way) and (node, row_id) not in removed:
                remove_chain(db, node, row_id)
                removed.add((node, row_id))
    for key in upstream:
        upstream[key] = [w for w in upstream[key] if (w[1], w[2]) not in removed]

    added, doors = [], set()
    for link in ours:
        requirements = [ids[r] for r in link.get("requirements") or []]
        twins = [w for w in upstream[way_key(link)] if same_way(link, w[0])
                 or (answers_by_text(link) and nl.prompt_twin(link, w[0]))]
        if twins:
            known += 1
            for _, node, row_id in twins:
                if requirements and tighten(nav, node, row_id, requirements):
                    tightened.add((node, row_id))
                if link.get("measured") and remeasure(nav, link, node, row_id):
                    remeasured.add((node, row_id))
            continue
        if link.get("choice"):
            skipped["a dialogue answer a route row cannot carry"] += 1
            continue
        if any(same_way(link, a) for a in added):
            skipped["the same way as another of ours"] += 1
            continue
        if not nl.sane(link):
            skipped["an area wider than any real crossing"] += 1
            continue
        start = None if link.get("global") else nav.start_tile(link)
        if not link.get("global") and start is None:
            skipped["no walkable tile in its start area"] += 1
            continue
        landing = nav.landing_tile(link)
        if landing is None:
            skipped["no map tile in its landing area"] += 1
            continue
        if link["kind"] == "DOOR" and not link.get("steps"):
            way = (link["objectId"], link["action"], (start, link["fromPlane"]), (landing, link["toPlane"]))
            if (way[0], way[1], way[3], way[2]) in doors:
                skipped["the way back through a door of ours already added"] += 1
                continue
            doors.add(way)
        refused = add_link(nav, link, start, landing, requirements)
        if refused:
            skipped[refused] += 1
            continue
        added.append(link)
        added_kinds[link["kind"]] += 1
    return known, added_kinds, skipped, len(tightened), len(remeasured), len(removed)


def merge_rings(nav, rings, ids):
    db = nav.db
    have = set()
    for r in db.execute("select * from teleports_fairy_rings_nodes order by id"):
        have.add((r["object_id"], r["x"], r["y"], r["plane"], (r["code"] or "").lower(),
                  nav.conditions(nl.requirement_ids(r["requirements"]) or [])))
    added, skipped = 0, collections.Counter()
    for ring in rings:
        requirements = tuple(sorted({ids[r] for r in ring.get("requirements") or []}))
        identity = (ring["objectId"], ring["x"], ring["y"], ring["plane"], ring["code"].lower(), nav.conditions(requirements))
        if identity in have:
            continue
        if (ring["x"], ring["y"]) not in nav.tiles(ring["x"], ring["x"], ring["y"], ring["y"], ring["plane"]):
            skipped["no map tile under the ring"] += 1
            continue
        have.add(identity)
        nav.insert("teleports_fairy_rings_nodes", {
            "id": nav.allocate("teleports_fairy_rings_nodes"), "object_id": ring["objectId"], "x": ring["x"],
            "y": ring["y"], "plane": ring["plane"], "cost": ring["costTiles"] * nl.TILE_MS, "code": ring["code"].lower(),
            "action": ring.get("action") or "Configure", "requirements": ids_text(requirements)})
        added += 1
    return added, skipped


def describe(counter):
    return ", ".join(f"{k} {v}" for k, v in sorted(counter.items())) or "none"


def merge(upstream_path, links_path, out_path):
    document = json.loads(pathlib.Path(links_path).read_text(encoding="utf-8"))
    out = pathlib.Path(out_path).resolve()
    with tempfile.TemporaryDirectory() as scratch:
        work = pathlib.Path(scratch) / "work.db"
        shutil.copyfile(upstream_path, work)
        db = sqlite3.connect(work)
        db.row_factory = sqlite3.Row
        nav = Map(db)
        ids, new_requirements, renumbered = adopt_requirements(db, document["requirements"])
        upstream, added, skipped, tightened, remeasured, removed = merge_links(nav, document["links"], ids)
        rings_added, rings_skipped = merge_rings(nav, document.get("fairyRings", []), ids)
        db.commit()
        out.unlink(missing_ok=True)
        db.execute("vacuum into ?", (str(out),))
        db.close()

    links = document["links"]
    rings = document.get("fairyRings", [])
    print(f"requirements: {len(document['requirements'])} of ours, {len(new_requirements)} added {new_requirements}, "
          f"{len(renumbered)} under upstream's id " + " ".join(f"{a}->{b}" for a, b in renumbered))
    print(f"links: {len(links)} of ours, {upstream} already upstream ({tightened} upstream chains took our "
          f"requirements, {remeasured} our measured areas, {removed} replaced by our measured answer), "
          f"{sum(added.values())} added ({describe(added)})")
    print(f"links skipped: {sum(skipped.values())} ({describe(skipped)})")
    print(f"fairy rings: {len(rings)} of ours, {rings_added} added, {len(rings) - rings_added - sum(rings_skipped.values())} "
          f"already upstream, skipped: {describe(rings_skipped)}")
    return {"requirements": new_requirements, "added": added, "skipped": skipped, "upstream": upstream,
            "tightened": tightened, "remeasured": remeasured, "removed": removed, "rings": rings_added}


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__.strip().splitlines()[2].strip())
    merge(sys.argv[1], sys.argv[2], sys.argv[3])


if __name__ == "__main__":
    main()
