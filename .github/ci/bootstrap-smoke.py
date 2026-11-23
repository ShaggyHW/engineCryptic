#!/usr/bin/env python3
"""Load a built engine bootstrap into a throwaway process and check that Java and the supervisor start.

    bootstrap-smoke.py <bootstrap .so/.dll> <projectx-supervisor.jar>

The bootstrap and supervisor are copied into a fresh engine home under the names the launcher installs
them as, with no engine jar beside them. The library is loaded the way the launcher's injection loads
it (by a canonical `\\\\?\\` path on Windows), and the run passes once the supervisor reports the missing
engine jar: by then the bootstrap has found its own folder and a JDK, started the JVM and handed over to
the supervisor, which is everything a revision update or a platform change can break before the engine
itself runs.

JAVA_HOME picks the JDK, and it reaches the bootstrap the way each launcher hands it over: on Linux as
JAVA_HOME in the client (the injector sets it with gdb), on Windows as a java-home.txt beside the library.
"""

import os
import shutil
import subprocess
import sys
import tempfile
import time

TIMEOUT_SECONDS = 90
SUPERVISOR_REACHED = "[Supervisor] no engine jar found"
FAILURE_MARKER = "[ENGINE FAILED]"

LOADER = """
import ctypes, sys, time
ctypes.CDLL(sys.argv[1], mode=getattr(ctypes, "RTLD_GLOBAL", 0))
time.sleep(600)
"""


def bootstrap_temp_dir():
    if os.name == "nt":
        return os.environ.get("TMP") or os.environ.get("TEMP") or tempfile.gettempdir()
    return "/tmp"


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    bootstrap, supervisor = sys.argv[1], sys.argv[2]

    home = tempfile.mkdtemp(prefix="projectx-smoke-")
    library_name = "projectxbootstrap.dll" if os.name == "nt" else "libprojectxbootstrap.so"
    library = os.path.join(home, library_name)
    shutil.copy(bootstrap, library)
    shutil.copy(supervisor, os.path.join(home, "projectx-supervisor.jar"))

    load_path = os.path.realpath(library)
    if os.name == "nt":
        load_path = "\\\\?\\" + load_path

    java_home = os.environ["JAVA_HOME"]
    env = dict(os.environ)
    env.pop("PROJECTX_HOME_DIR", None)
    if os.name == "nt":
        env.pop("JAVA_HOME", None)
        env.pop("JDK_HOME", None)
        with open(os.path.join(home, "java-home.txt"), "w", encoding="utf-8") as hint:
            hint.write(java_home + "\n")
        expected_source = "from the launcher's java-home.txt"
    else:
        expected_source = "from JAVA_HOME"
    child = subprocess.Popen([sys.executable, "-c", LOADER, load_path], env=env)

    temp = bootstrap_temp_dir()
    log_path = os.path.join(temp, f"projectx_log_{child.pid}.txt")
    stderr_path = os.path.join(temp, f"projectx_stderr_{child.pid}.txt")
    print(f"loaded {load_path} into pid {child.pid} with Java {java_home}")

    outcome = None
    deadline = time.monotonic() + TIMEOUT_SECONDS
    while outcome is None:
        log, stderr = read(log_path), read(stderr_path)
        failure = next((l for l in log.splitlines() if l.startswith(FAILURE_MARKER)), None)
        if failure:
            outcome = f"the bootstrap gave up: {failure}"
        elif SUPERVISOR_REACHED in stderr:
            outcome = "ok" if expected_source in log else f"Java was not taken {expected_source}"
        elif child.poll() is not None:
            outcome = f"the host process died with exit code {child.returncode}"
        elif time.monotonic() > deadline:
            outcome = f"the supervisor did not start within {TIMEOUT_SECONDS}s"
        else:
            time.sleep(0.5)

    if child.poll() is None:
        child.kill()
        child.wait()

    print(f"--- {log_path}\n{read(log_path)}--- {stderr_path}\n{read(stderr_path)}---")
    if outcome != "ok":
        sys.exit(f"bootstrap smoke test failed: {outcome}")
    print("bootstrap smoke test passed: Java started and the supervisor ran")


if __name__ == "__main__":
    main()
