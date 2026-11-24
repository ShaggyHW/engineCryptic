//! `exclude_macros` on `/route` and `/route_area`, and the `macro` block on actions.

use std::collections::HashMap;
use std::sync::Arc;

use arc_swap::ArcSwap;
use axum::{body::Body, http::{Request, StatusCode}};
use http_body_util::BodyExt;
use navpath_core::snapshot::{pack_coord, write_snapshot_v8, SnapshotSections};
use navpath_service::engine_adapter::{build_component_graph, build_fairy_rings, build_neighbor_provider};
use navpath_service::{build_router, AppState, SnapshotState};
use serde_json::{json, Value};
use tempfile::NamedTempFile;
use tower::ServiceExt;

const DOOR: (&str, u32) = ("door", 7);
const OBJECT: (&str, u32) = ("object", 11);
const RING_AJR: (&str, u32) = ("fairy_ring", 11018);
const NPC: (&str, u32) = ("npc", 21);

/// West cell (3200..=3202, 3200) and east cell (3205..=3207, 3200), joined only by
/// special edges, cheapest first from (3200,3200) to (3207,3200):
/// door 7 (600) and object 11 (1500), both (3202) -> (3205); fairy ring `aip` at 3201 to
/// `ajr` at 3206 (5000, `ajr` listed twice as in the database); global npc 21 landing on
/// 3205 (10000).
fn make_snapshot() -> tempfile::TempPath {
    let tmp = NamedTempFile::new().unwrap();
    let path = tmp.path().to_path_buf();
    let tiles: Vec<(i32, i32)> = [3200, 3201, 3202, 3205, 3206, 3207].iter().map(|&x| (x, 3200)).collect();
    let index: HashMap<(i32, i32), u32> = tiles.iter().enumerate().map(|(i, &t)| (t, i as u32)).collect();
    let coords_packed: Vec<u32> = tiles.iter().map(|&(x, y)| pack_coord(x, y, 0)).collect();
    let mut walk_offsets: Vec<u32> = vec![0];
    let mut walk_dst: Vec<u32> = Vec::new();
    for &(x, y) in &tiles {
        for n in [(x - 1, y), (x, y - 1), (x + 1, y), (x, y + 1)] {
            if let Some(&v) = index.get(&n) {
                walk_dst.push(v);
            }
        }
        walk_offsets.push(walk_dst.len() as u32);
    }
    let walk_diag = vec![0u8; walk_dst.len().div_ceil(8)];
    let comp: Vec<u16> = tiles.iter().map(|&(x, _)| if x <= 3202 { 0 } else { 1 }).collect();

    let (gate, landing) = (index[&(3202, 3200)], index[&(3205, 3200)]);
    let meta_door = json!({"kind": "door", "first_id": 7, "steps": [{"kind": "door", "id": 7}], "requirements": []}).to_string();
    let meta_obj = json!({"kind": "object", "first_id": 11, "steps": [{"kind": "object", "id": 11}], "requirements": []}).to_string();
    let meta_global = json!({
        "global": [{ "dst": landing, "cost_ms": 10000.0, "steps": [{"kind": "npc", "id": 21}], "requirements": [] }]
    })
    .to_string();
    let metas = [meta_door, meta_obj, meta_global];
    let (mut offs, mut lens, mut blob) = (Vec::new(), Vec::new(), Vec::new());
    for m in &metas {
        offs.push(blob.len() as u32);
        lens.push(m.len() as u32);
        blob.extend_from_slice(m.as_bytes());
    }

    let ring = |x: i32, code: &str| {
        json!({"object_id": 12128, "x": x, "y": 3200, "plane": 0, "code": code, "action": "Configure", "requirements": []}).to_string()
    };
    let rings = [(3201, "aip"), (3206, "ajr"), (3206, "ajr")];
    let fairy_nodes: Vec<u32> = rings.iter().map(|&(x, _)| index[&(x, 3200)]).collect();
    let (mut f_offs, mut f_lens, mut f_blob) = (Vec::new(), Vec::new(), Vec::new());
    for &(x, code) in &rings {
        let m = ring(x, code);
        f_offs.push(f_blob.len() as u32);
        f_lens.push(m.len() as u32);
        f_blob.extend_from_slice(m.as_bytes());
    }

    write_snapshot_v8(
        &path,
        &SnapshotSections {
            coords_packed: &coords_packed,
            walk_offsets: &walk_offsets,
            walk_dst: &walk_dst,
            walk_diag: &walk_diag,
            comp: &comp,
            walk_components: 2,
            macro_src: &[gate, gate, 0],
            macro_dst: &[landing, landing, 0],
            macro_w: &[600.0, 1500.0, 0.0],
            macro_kind_first: &[1, 4, 0],
            macro_id_first: &[7, 11, 0],
            macro_meta_offs: &offs,
            macro_meta_lens: &lens,
            macro_meta_blob: &blob,
            req_tags: &[],
            landmarks: &[],
            lm_tab: &[],
            fairy_nodes: &fairy_nodes,
            fairy_cost_ms: &[5000.0, 5000.0, 5000.0],
            fairy_meta_offs: &f_offs,
            fairy_meta_lens: &f_lens,
            fairy_meta_blob: &f_blob,
        },
    )
    .expect("write snapshot");
    tmp.into_temp_path()
}

fn app_for(path: &std::path::Path, with_comp_graph: bool) -> axum::Router {
    let opened = navpath_core::Snapshot::open(path).unwrap();
    let (neighbors, neighbors_rev, globals, macro_lookup) = build_neighbor_provider(&opened);
    let (fairy_rings, node_to_fairy_ring) = build_fairy_rings(&opened);
    let comp_graph = with_comp_graph.then(|| Arc::new(build_component_graph(&opened, &globals, &fairy_rings, &macro_lookup)));
    let state = AppState {
        current: Arc::new(ArcSwap::from_pointee(SnapshotState {
            path: path.to_path_buf(),
            snapshot: Some(Arc::new(opened)),
            neighbors: Some(Arc::new(neighbors)),
            neighbors_rev: Some(Arc::new(neighbors_rev)),
            globals: Arc::new(globals),
            macro_lookup: Arc::new(macro_lookup),
            loaded_at_unix: 123,
            snapshot_hash_hex: None,
            route_cache: navpath_service::new_route_cache(),
            seed_shadow: navpath_service::new_seed_shadow(),
            fairy_rings: Arc::new(fairy_rings),
            node_to_fairy_ring: Arc::new(node_to_fairy_ring),
            comp_graph,
            canonical_grid: None,
            profile_cache: navpath_service::new_profile_cache(),
            subpath_cache: navpath_service::new_subpath_cache(),
            warm_state: Default::default(),
            danger: None,
        })),
        search_permits: navpath_service::default_search_permits(),
        metrics: Arc::new(navpath_service::Metrics::default()),
        ctx_pool: navpath_service::ContextPool::new(),
        ready: Arc::new(std::sync::atomic::AtomicBool::new(true)),
    };
    build_router(state)
}

async fn post(app: &axum::Router, uri: &str, body: Value) -> Value {
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(uri)
                .header("content-type", "application/json")
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let bytes = res.into_body().collect().await.unwrap().to_bytes();
    let mut v: Value = serde_json::from_slice(&bytes).unwrap();
    let obj = v.as_object_mut().unwrap();
    obj.remove("duration_ms");
    obj.remove("duration_us");
    v
}

fn tile(x: i32) -> Value {
    json!({"wx": x, "wy": 3200, "plane": 0})
}

fn route_body(start: Value, exclude: &[(&str, u32)]) -> Value {
    let mut body = json!({"start": start, "goal": tile(3207), "options": {"only_actions": true}});
    if !exclude.is_empty() {
        body["exclude_macros"] = exclude.iter().map(|&(k, id)| json!({"kind": k, "id": id})).collect();
    }
    body
}

/// The `macro` blocks of a response's actions, in order.
fn macros(v: &Value) -> Vec<(String, u64)> {
    v["actions"]
        .as_array()
        .map(|a| {
            a.iter()
                .filter_map(|act| act.get("macro"))
                .map(|m| (m["kind"].as_str().unwrap().to_string(), m["id"].as_u64().unwrap()))
                .collect()
        })
        .unwrap_or_default()
}

fn named(m: (&str, u32)) -> (String, u64) {
    (m.0.to_string(), m.1 as u64)
}

#[tokio::test]
async fn each_exclusion_routes_around_its_macro() {
    let snap = make_snapshot();
    for with_comp_graph in [true, false] {
        let app = app_for(&snap, with_comp_graph);
        let steps: [(&[(&str, u32)], Option<(&str, u32)>, f64); 5] = [
            (&[], Some(DOOR), 1800.0),
            (&[DOOR], Some(OBJECT), 2700.0),
            (&[DOOR, OBJECT], Some(RING_AJR), 5600.0),
            (&[DOOR, OBJECT, RING_AJR], Some(NPC), 10600.0),
            (&[DOOR, OBJECT, RING_AJR, NPC], None, 0.0),
        ];
        for (exclude, taken, cost) in steps {
            let v = post(&app, "/route", route_body(tile(3200), exclude)).await;
            match taken {
                Some(m) => {
                    assert_eq!(v["found"], json!(true), "{exclude:?}: {v}");
                    assert_eq!(macros(&v), vec![named(m)], "{exclude:?}: {v}");
                    assert_eq!(v["cost"], json!(cost), "{exclude:?}: {v}");
                }
                None => {
                    assert_eq!(v["found"], json!(false), "{exclude:?}: {v}");
                    assert!(v.get("actions").is_none(), "{v}");
                }
            }
        }
    }
}

#[tokio::test]
async fn actions_carry_their_macro() {
    let snap = make_snapshot();
    let app = app_for(&snap, true);
    let v = post(&app, "/route", route_body(tile(3200), &[])).await;
    let door = v["actions"].as_array().unwrap().iter().find(|a| a["type"] == "door").unwrap();
    assert_eq!(door["macro"], json!({"kind": "door", "id": 7}), "{v}");
    assert_eq!(door["node"], json!({"type": "door", "id": 7}), "{v}");

    let v = post(&app, "/route", route_body(tile(3200), &[DOOR, OBJECT])).await;
    let ring = v["actions"].as_array().unwrap().iter().find(|a| a["type"] == "fairy_ring").unwrap();
    assert_eq!(ring["macro"], json!({"kind": "fairy_ring", "id": 11018}), "{v}");
    assert_eq!(ring["code"], json!("ajr"), "{v}");

    // Off the snapshot: the global teleport is the only way in, named on the virtual start.
    let off = json!({"wx": 3100, "wy": 3100, "plane": 0});
    let v = post(&app, "/route", route_body(off.clone(), &[])).await;
    assert_eq!(v["found"], json!(true), "{v}");
    assert_eq!(v["actions"][0]["macro"], json!({"kind": "npc", "id": 21}), "{v}");
    let v = post(&app, "/route", route_body(off, &[NPC])).await;
    assert_eq!(v["found"], json!(false), "{v}");
}

#[tokio::test]
async fn empty_or_unknown_exclusions_answer_identically() {
    let snap = make_snapshot();
    let app = app_for(&snap, true);
    let plain = post(&app, "/route", route_body(tile(3200), &[])).await;
    for exclude in [
        json!(null),
        json!([]),
        json!([{"kind": "door", "id": 999}]),
        json!([{"kind": "bogus", "id": 7}]),
        json!([{"kind": "door", "id": "7"}]),
        json!([{"kind": 1, "id": 7}]),
        json!([{"id": 7}]),
        json!([{"kind": "door", "id": -7}]),
    ] {
        let mut body = route_body(tile(3200), &[]);
        body["exclude_macros"] = exclude.clone();
        let v = post(&app, "/route", body).await;
        assert_eq!(v, plain, "exclude_macros = {exclude}");
    }
}

#[tokio::test]
async fn exclusions_never_reach_the_shared_caches() {
    let snap = make_snapshot();
    // A cached plain route (and its sub-path index) must not answer an excluding request.
    let app = app_for(&snap, true);
    for _ in 0..2 {
        assert_eq!(macros(&post(&app, "/route", route_body(tile(3200), &[])).await), vec![named(DOOR)]);
        assert_eq!(macros(&post(&app, "/route", route_body(tile(3200), &[DOOR])).await), vec![named(OBJECT)]);
        assert_eq!(macros(&post(&app, "/route", route_body(tile(3201), &[DOOR])).await), vec![named(OBJECT)]);
    }
    // An excluding request first must not leave its detour behind for plain requests.
    let app = app_for(&snap, true);
    assert_eq!(macros(&post(&app, "/route", route_body(tile(3200), &[DOOR, OBJECT])).await), vec![named(RING_AJR)]);
    assert_eq!(macros(&post(&app, "/route", route_body(tile(3200), &[])).await), vec![named(DOOR)]);
    assert_eq!(macros(&post(&app, "/route", route_body(tile(3200), &[DOOR])).await), vec![named(OBJECT)]);
}

#[tokio::test]
async fn route_area_honours_exclusions() {
    let snap = make_snapshot();
    let app = app_for(&snap, true);
    let body = |exclude: Value| {
        json!({
            "start": tile(3200),
            "goal_area": {"min_wx": 3206, "min_wy": 3200, "max_wx": 3207, "max_wy": 3200, "plane": 0},
            "options": {"only_actions": true},
            "exclude_macros": exclude,
        })
    };
    let v = post(&app, "/route_area", body(json!([]))).await;
    assert_eq!(macros(&v), vec![named(DOOR)], "{v}");
    let v = post(&app, "/route_area", body(json!([{"kind": "door", "id": 7}, {"kind": "object", "id": 11}]))).await;
    assert_eq!(macros(&v), vec![named(RING_AJR)], "{v}");
    assert_eq!(v["goal_reached"], tile(3206), "{v}");
    let all = json!([{"kind": "door", "id": 7}, {"kind": "object", "id": 11}, {"kind": "fairy_ring", "id": 11018}, {"kind": "npc", "id": 21}]);
    let v = post(&app, "/route_area", body(all)).await;
    assert_eq!(v["found"], json!(false), "{v}");
}
