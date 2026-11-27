#!/usr/bin/env python3
"""Build a snapshot from the shipped map with each built builder, then start its service on it and route.

    navpath-smoke.py <navpath archive dir> <navpath-map.db>

The archive dir holds what the release ships: navpath-builder, navpath-service and their -baseline copies. Each
pair has to build a snapshot, answer /health and plan Lumbridge to Castle Wars, which is everything a source update,
a map update or a toolchain change can break before the launcher runs it.
"""

import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

READY_SECONDS = 180
EXE = ".exe" if os.name == "nt" else ""
ROUTE = {"start": {"wx": 3222, "wy": 3218, "plane": 0}, "goal": {"wx": 2440, "wy": 3090, "plane": 0},
         "profile": {"requirements": []}, "options": {"return_geometry": False, "only_actions": True}}


def spare_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def get(url):
    with urllib.request.urlopen(url, timeout=5) as reply:
        return json.loads(reply.read())


def post(url, body):
    request = urllib.request.Request(url, json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as reply:
        return json.loads(reply.read())


def serve_and_route(service, snapshot):
    port = spare_port()
    base = f"http://127.0.0.1:{port}"
    env = dict(os.environ, SNAPSHOT_PATH=snapshot, NAVPATH_HOST="127.0.0.1", NAVPATH_PORT=str(port), RUST_LOG="warn")
    process = subprocess.Popen([service], env=env)
    try:
        deadline = time.monotonic() + READY_SECONDS
        while True:
            if process.poll() is not None:
                sys.exit(f"{os.path.basename(service)} exited with {process.returncode} before it was ready")
            try:
                if get(f"{base}/health").get("ready"):
                    break
            except (urllib.error.URLError, ConnectionError, TimeoutError, ValueError):
                pass
            if time.monotonic() > deadline:
                sys.exit(f"{os.path.basename(service)} was not ready after {READY_SECONDS} s")
            time.sleep(1)
        answer = post(f"{base}/route", ROUTE)
        actions = answer.get("actions") or []
        if not answer.get("found") or not actions:
            sys.exit(f"{os.path.basename(service)} found no route: {json.dumps(answer)[:500]}")
        print(f"{os.path.basename(service)}: {len(actions)} actions, cost {answer.get('cost')}")
    finally:
        process.kill()
        process.wait()


def main():
    archive, map_db = sys.argv[1], os.path.abspath(sys.argv[2])
    with tempfile.TemporaryDirectory() as scratch:
        for suffix in ("", "-baseline"):
            snapshot = os.path.join(scratch, f"graph{suffix}.snapshot")
            subprocess.run([os.path.join(archive, f"navpath-builder{suffix}{EXE}"), "--sqlite", map_db,
                            "--out-snapshot", snapshot, "--out-tiles", os.path.join(scratch, f"tiles{suffix}.bin"),
                            "--no-walkable", "--landmarks", "64"], check=True)
            serve_and_route(os.path.join(archive, f"navpath-service{suffix}{EXE}"), snapshot)


if __name__ == "__main__":
    main()
