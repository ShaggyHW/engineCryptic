import contextlib
import io
import json
import pathlib
import sqlite3
import tempfile
import unittest

import navpath_map

BOX = "dest_min_x INTEGER, dest_max_x INTEGER, dest_min_y INTEGER, dest_max_y INTEGER, dest_plane INTEGER"
ORIG = "orig_min_x INTEGER, orig_max_x INTEGER, orig_min_y INTEGER, orig_max_y INTEGER, orig_plane INTEGER"
NEXT = "next_node_type TEXT, next_node_id INTEGER, requirements TEXT"
SCHEMA = [
    "CREATE TABLE tiles (x INTEGER, y INTEGER, plane INTEGER, walk_mask INTEGER, RegionID INTEGER, "
    "PRIMARY KEY (x, y, plane)) WITHOUT ROWID",
    "CREATE TABLE teleports_requirements (id INTEGER PRIMARY KEY, metaInfo TEXT, key TEXT, value TEXT, comparison TEXT)",
    f"CREATE TABLE teleports_object_nodes (id INTEGER PRIMARY KEY, match_type TEXT, object_id INTEGER, "
    f"object_name TEXT, action TEXT, {BOX}, {ORIG}, search_radius INTEGER, cost INTEGER, {NEXT})",
    f"CREATE TABLE teleports_npc_nodes (id INTEGER PRIMARY KEY, match_type TEXT, npc_id INTEGER, npc_name TEXT, "
    f"action TEXT, {BOX}, search_radius INTEGER, cost INTEGER, {ORIG}, {NEXT})",
    "CREATE TABLE teleports_door_nodes (id INTEGER PRIMARY KEY, direction TEXT, real_id_open INTEGER, "
    "real_id_closed INTEGER, location_open_x INTEGER, location_open_y INTEGER, location_open_plane INTEGER, "
    "location_closed_x INTEGER, location_closed_y INTEGER, location_closed_plane INTEGER, tile_inside_x INTEGER, "
    "tile_inside_y INTEGER, tile_inside_plane INTEGER, tile_outside_x INTEGER, tile_outside_y INTEGER, "
    f"tile_outside_plane INTEGER, open_action TEXT, cost INTEGER, {NEXT})",
    f"CREATE TABLE teleports_item_nodes (id INTEGER PRIMARY KEY, match_type TEXT, name TEXT, item_id INTEGER, "
    f"action TEXT, {BOX}, next_node_type TEXT, next_node_id INTEGER, cost INTEGER, requirements TEXT)",
    f"CREATE TABLE teleports_ifslot_nodes (id INTEGER PRIMARY KEY, interface_id INTEGER, component_id INTEGER, "
    f"slot_id INTEGER, click_id INTEGER, {BOX}, cost INTEGER, {NEXT})",
    "CREATE TABLE teleports_lodestone_nodes (id INTEGER PRIMARY KEY, lodestone TEXT, dest_x INTEGER, dest_y INTEGER, "
    f"dest_plane INTEGER, cost INTEGER, {NEXT})",
    f"CREATE TABLE teleports_POA_nodes (id INTEGER PRIMARY KEY, item_id INTEGER, action TEXT, action2 TEXT, "
    f"action3 TEXT, {BOX}, cost INTEGER, requirements TEXT)",
    f"CREATE TABLE teleports_useOn_nodes (id INTEGER PRIMARY KEY, item_id INTEGER, object_id INTEGER, {BOX}, {ORIG}, "
    "next_node_type TEXT, next_node_id INTEGER, cost INTEGER, requirements TEXT)",
    f"CREATE TABLE teleports_spell_nodes (id INTEGER PRIMARY KEY, spell_id INTEGER, action TEXT, {BOX}, "
    f"cost INTEGER, {NEXT})",
    "CREATE TABLE teleports_fairy_rings_nodes (id INTEGER PRIMARY KEY, object_id INTEGER, x INTEGER, y INTEGER, "
    f"plane INTEGER, cost INTEGER, code TEXT, action TEXT, {NEXT})",
]


def link(kind, action, object_id, start, end, **extra):
    return {"kind": kind, "action": action, "objectId": object_id, "searchRadius": 20, "costTiles": 2,
            "fromMinX": start[0], "fromMaxX": start[1], "fromMinY": start[2], "fromMaxY": start[3], "fromPlane": 0,
            "toMinX": end[0], "toMaxX": end[1], "toMinY": end[2], "toMaxY": end[3], "toPlane": end[4],
            "requirements": None, **extra}


class NavpathMapTest(unittest.TestCase):
    def setUp(self):
        self.dir = pathlib.Path(tempfile.mkdtemp())
        self.upstream = self.dir / "upstream.db"
        db = sqlite3.connect(self.upstream)
        for statement in SCHEMA:
            db.execute(statement)
        tiles = [(x, y, p, 255, 0) for p in (0, 1) for x in range(3200, 3220) for y in range(3200, 3220)]
        db.executemany("insert into tiles values (?, ?, ?, ?, ?)", tiles + [(3230, 3230, 0, 0, 0)])
        db.executemany("insert into teleports_requirements values (?, ?, ?, ?, ?)", [
            (1, "Coins", "coins", "30", ">="), (2, "Quest", "varp_9", "5", ">=")])
        db.execute("insert into teleports_object_nodes (id, match_type, object_id, action, dest_min_x, dest_max_x, "
                   "dest_min_y, dest_max_y, dest_plane, orig_min_x, orig_max_x, orig_min_y, orig_max_y, orig_plane, "
                   "search_radius, cost) values (1, 'id', 100, 'Climb-up', 3201, 3201, 3201, 3201, 1, 3201, 3201, "
                   "3201, 3201, 0, 20, 600)")
        db.execute("insert into teleports_fairy_rings_nodes (id, object_id, x, y, plane, cost, code, action, "
                   "requirements) values (1, 12128, 3210, 3210, 0, 7500, 'aip', 'Configure', '1')")
        db.commit()
        db.close()
        self.links = {
            "requirements": {
                "1": {"about": "Coins", "key": "coins", "value": "30", "comparison": ">="},
                "2": {"about": "Ours", "key": "agilityLevel", "value": "35", "comparison": ">="},
                "7": {"about": "Quest again", "key": "varp_9", "value": "5", "comparison": ">="},
            },
            "links": [
                link("OBJECT", "Climb-up", 100, (3201, 3201, 3201, 3201), (3201, 3201, 3201, 3201, 1), requirements=[2]),
                link("OBJECT", "Enter", 200, (3205, 3206, 3205, 3206), (3210, 3212, 3210, 3212, 1),
                     requirements=[7], steps=[[1188, 8, -1, 0], {"objectId": 201, "action": "Push", "searchRadius": 8}]),
                link("DOOR", "Open", 300, (3207, 3207, 3207, 3207), (3207, 3207, 3208, 3208, 0)),
                link("DOOR", "Open", 300, (3207, 3207, 3208, 3208), (3207, 3207, 3207, 3207, 0)),
                link("OBJECT", "Climb", 400, (3300, 3301, 3300, 3301), (3201, 3201, 3201, 3201, 0)),
                link("OBJECT", "Squeeze", 500, (3230, 3230, 3230, 3230), (3201, 3201, 3201, 3201, 0)),
                {"kind": "ITEM", "action": "Rub", "objectId": -1, "searchRadius": 0, "costTiles": 6, "global": True,
                 "itemId": 1712, "toMinX": 3215, "toMaxX": 3215, "toMinY": 3215, "toMaxY": 3215, "toPlane": 0,
                 "requirements": [1], "steps": [[720, 1, -1, 0]]},
            ],
            "fairyRings": [
                {"objectId": 12128, "x": 3210, "y": 3210, "plane": 0, "code": "aip", "action": "Configure",
                 "costTiles": 12, "requirements": [1]},
                {"objectId": 12128, "x": 3210, "y": 3210, "plane": 0, "code": "aip", "action": "Configure",
                 "costTiles": 12, "requirements": [7]},
            ],
        }
        self.links_path = self.dir / "links.json"
        self.links_path.write_text(json.dumps(self.links), encoding="utf-8")

    def merged(self, name="out.db"):
        out = self.dir / name
        with contextlib.redirect_stdout(io.StringIO()):
            report = navpath_map.merge(self.upstream, self.links_path, out)
        db = sqlite3.connect(out.as_uri() + "?mode=ro", uri=True)
        db.row_factory = sqlite3.Row
        return report, db

    def test_requirements_reuse_upstream_ids_and_renumber_a_clash(self):
        _, db = self.merged()
        rows = {r["id"]: r["key"] for r in db.execute("select * from teleports_requirements")}
        self.assertEqual({1: "coins", 2: "varp_9", 3: "agilityLevel"}, rows)

    def test_a_link_upstream_has_is_not_added_but_takes_our_requirements(self):
        report, db = self.merged()
        self.assertEqual(1, report["upstream"])
        rows = db.execute("select * from teleports_object_nodes where object_id=100").fetchall()
        self.assertEqual(1, len(rows))
        self.assertEqual("3", rows[0]["requirements"])

    def test_steps_become_chained_rows(self):
        _, db = self.merged()
        enter = db.execute("select * from teleports_object_nodes where object_id=200").fetchone()
        self.assertEqual(("ifslot", "2"), (enter["next_node_type"], enter["requirements"]))
        self.assertEqual((3210, 3212, 1, 1200), (enter["dest_min_x"], enter["dest_max_x"], enter["dest_plane"], enter["cost"]))
        click = db.execute("select * from teleports_ifslot_nodes where id=?", (enter["next_node_id"],)).fetchone()
        self.assertEqual((1188, 8, -1, 0, "object"), tuple(click)[1:5] + (click["next_node_type"],))
        push = db.execute("select * from teleports_object_nodes where id=?", (click["next_node_id"],)).fetchone()
        self.assertEqual((201, "Push", 8, None), (push["object_id"], push["action"], push["search_radius"], push["orig_min_x"]))

    def test_a_door_and_its_way_back_are_one_row(self):
        report, db = self.merged()
        doors = db.execute("select * from teleports_door_nodes").fetchall()
        self.assertEqual(1, len(doors))
        self.assertEqual((300, "Open", 3207, 3207, 3207, 3208), (
            doors[0]["real_id_closed"], doors[0]["open_action"], doors[0]["tile_outside_x"], doors[0]["tile_outside_y"],
            doors[0]["tile_inside_x"], doors[0]["tile_inside_y"]))
        self.assertEqual(1, report["skipped"]["the way back through a door of ours already added"])

    def test_links_navpath_would_drop_are_skipped(self):
        report, db = self.merged()
        self.assertEqual(2, report["skipped"]["no walkable tile in its start area"])
        self.assertIsNone(db.execute("select * from teleports_object_nodes where object_id in (400, 500)").fetchone())

    def test_a_global_item_lands_and_chains(self):
        report, db = self.merged()
        item = db.execute("select * from teleports_item_nodes").fetchone()
        self.assertEqual(("id", 1712, "Rub", "1", "ifslot"), (
            item["match_type"], item["item_id"], item["action"], item["requirements"], item["next_node_type"]))
        self.assertEqual({"OBJECT": 1, "DOOR": 1, "ITEM": 1}, dict(report["added"]))

    def test_a_ring_is_new_only_when_its_requirements_differ(self):
        report, db = self.merged()
        self.assertEqual(1, report["rings"])
        self.assertEqual(["1", "2"], [r[0] for r in db.execute("select requirements from teleports_fairy_rings_nodes order by id")])

    def test_the_same_inputs_give_the_same_bytes(self):
        self.merged("a.db")
        self.merged("b.db")
        self.assertEqual((self.dir / "a.db").read_bytes(), (self.dir / "b.db").read_bytes())

    def test_the_upstream_database_is_left_alone(self):
        before = self.upstream.read_bytes()
        self.merged()
        self.assertEqual(before, self.upstream.read_bytes())


if __name__ == "__main__":
    unittest.main()
