# navpath

The route planner the web walker plans on. The launcher runs one copy per machine on localhost.

The source is ShaggyHW's navpathService (https://github.com/ShaggyHW/navpathService), vendored at
commit 47d6a57141a80af2cfdc70bb04f5bfffcdfa5211 (2026-11-24). The upstream repository has no licence;
we build it, ship it to players and keep its source here, in this public repository, with its author's permission.

Our changes on top of upstream:
- `rust/navpath-core/src/snapshot/reader.rs`: the memory-map advice and locking calls are Unix-only
  in memmap2, so they are compiled on Unix only. On Windows the snapshot reads without them.
- `.cargo/config.toml` targets x86-64-v3 instead of the build host's CPU.
- No `rust-toolchain.toml`: it builds on stable Rust.
- `/route` and `/route_area` name every macro, global teleport and fairy ring action with a `macro`
  block (`{"kind", "id"}`), and take `exclude_macros` (a list of those) to route around them. Excluding
  requests skip the route and sub-path caches and build their own profile artifacts.

`worldReachableTiles.db` is upstream's map at the commit in `UPSTREAM`, unchanged. CI merges our measured links
from `links.json` into a copy of it (`.github/scripts/navpath_map.py`) and ships that as the launcher's map. To
take a newer upstream map, replace the file and the commit together.
