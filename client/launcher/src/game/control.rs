//! Client control plane for the Project X engine's hot-reload feature.
//!
//! Once injected, the engine runs a permanent supervisor that listens on a
//! per-pid Unix-domain socket (see `client-plugin-engine-supervisor`'s `ControlSocket`). The
//! launcher's "Clients" panel uses this module to discover running `rs2client`
//! processes and drive uninject/reinject without restarting the client.
//!
//! Protocol (one request line, one response line):
//!   STATUS   -> `OK <state> pid=<p> version=<v> reloads=<n>`
//!               (state: active|unloaded|loading|error)
//!   PING     -> `OK pong`
//!   INJECT   -> starts a load if unloaded    -> `OK loading` / `OK active version=<v>`
//!   UNINJECT -> tears the engine down        -> `OK unloaded`
//!   REINJECT -> unload + reload the jar      -> `OK loading`
//!   (unknown -> `ERR <msg>`)
//!
//! INJECT/REINJECT acknowledge before the load finishes: the supervisor serves one
//! command at a time on one thread, so running a multi-second load inline would stall
//! the STATUS polls that report its progress. Completion is observed by polling STATUS.
//!
//! The FIRST injection of a process still goes through [`crate::game::inject`]
//! (GDB-`dlopen` on Linux, `LoadLibraryW` on Windows); once the bootstrap is
//! mapped, every subsequent control action is a plain socket command.

use serde::Serialize;
use std::path::PathBuf;

#[cfg(unix)]
use std::os::unix::net::UnixStream;
#[cfg(windows)]
use uds_windows::UnixStream;

/// One discovered `rs2client` process and its engine state.
///
/// `state` is one of:
///   - `"not-injected"` — a live `rs2client` with the engine `.so` not yet mapped
///   - `"starting"` — engine coming up, from either loading window: the `.so` is mapped
///     but the socket is not up yet (still inside the grace window), or the socket is up
///     and the supervisor reports a load in flight
///   - `"active"` — engine loaded (from the supervisor's STATUS reply)
///   - `"unloaded"` — engine injected but currently uninjected (vanilla client)
///   - `"error"` — `.so` is mapped and the socket stayed unreachable past the grace
///     window, or the supervisor reported an error state
#[derive(Debug, Clone, Serialize)]
pub struct ClientStatus {
    pub pid: u32,
    pub state: String,
    pub version: Option<String>,
    pub reloads: u32,
    /// Why a client is stuck, for the states where the supervisor never answered and so has
    /// nothing to say for itself. Read from the bootstrap's own log — see [`bootstrap_complaint`].
    pub detail: Option<String>,
}

/// Per-pid control-socket path, matching the engine supervisor's
/// `ControlSocket.resolveSocketPath` exactly:
///   - `$XDG_RUNTIME_DIR/projectx/<pid>.sock` when `XDG_RUNTIME_DIR` is set+non-empty
///   - else `<java.io.tmpdir>/projectx-<user.name>/<pid>.sock`
pub fn socket_path(pid: u32) -> PathBuf {
    let base = match std::env::var("XDG_RUNTIME_DIR") {
        Ok(runtime) if !runtime.is_empty() => PathBuf::from(runtime).join("projectx"),
        _ => jvm_temp_dir().join(format!("projectx-{}", jvm_user_name())),
    };
    base.join(format!("{}.sock", pid))
}

/// The JVM's `java.io.tmpdir` default. On Windows it comes from `GetTempPath`,
/// which is what `std::env::temp_dir` uses; on Linux the JVM hardcodes `/tmp`
/// rather than honouring `$TMPDIR`, so it cannot go through `temp_dir` there.
#[cfg(windows)]
fn jvm_temp_dir() -> PathBuf {
    std::env::temp_dir()
}

#[cfg(not(windows))]
fn jvm_temp_dir() -> PathBuf {
    PathBuf::from("/tmp")
}

/// The JVM's `user.name` system property, which the supervisor falls back to
/// `"user"` for.
fn jvm_user_name() -> String {
    #[cfg(windows)]
    let vars = ["USERNAME"];
    #[cfg(not(windows))]
    let vars = ["USER", "LOGNAME"];

    vars.iter()
        .filter_map(|v| std::env::var(v).ok())
        .find(|s| !s.is_empty())
        .unwrap_or_else(|| "user".to_string())
}

/// Connect the per-pid control socket, write `cmd\n`, and read exactly one
/// response line. Uses short connect/read/write timeouts so an unresponsive
/// supervisor can never hang the UI dispatch.
#[cfg(any(unix, windows))]
pub fn send_command(pid: u32, cmd: &str) -> anyhow::Result<String> {
    use anyhow::Context;
    use std::io::{BufRead, BufReader, Write};
    use std::time::Duration;

    const TIMEOUT: Duration = Duration::from_secs(5);

    let path = socket_path(pid);
    let stream = UnixStream::connect(&path)
        .with_context(|| format!("connecting to control socket {}", path.display()))?;
    stream.set_read_timeout(Some(TIMEOUT))?;
    stream.set_write_timeout(Some(TIMEOUT))?;

    let mut writer = stream.try_clone().context("cloning control socket")?;
    writer
        .write_all(cmd.as_bytes())
        .context("writing control command")?;
    writer.write_all(b"\n").context("writing command newline")?;
    writer.flush().context("flushing control command")?;

    let mut reader = BufReader::new(stream);
    let mut line = String::new();
    reader
        .read_line(&mut line)
        .context("reading control response")?;
    let line = line.trim_end_matches(['\r', '\n']).to_string();
    if line.is_empty() {
        anyhow::bail!("empty response from control socket");
    }
    Ok(line)
}

#[cfg(not(any(unix, windows)))]
pub fn send_command(pid: u32, cmd: &str) -> anyhow::Result<String> {
    let _ = (pid, cmd);
    anyhow::bail!("control socket is not supported on this platform")
}

/// Report the engine state of every live `rs2client` process. Reuses the
/// process-scanning helpers in [`crate::game::inject`] so the rs2client /
/// injected detection stays in one place.
///
/// Per pid:
///   - bootstrap not mapped                -> `"not-injected"`
///   - bootstrap mapped + STATUS reachable  -> parse state/version/reloads from the reply
///   - bootstrap mapped + socket unreachable, within the grace window -> `"starting"`
///   - bootstrap mapped + socket unreachable, past it / malformed reply -> `"error"`
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
pub fn discover_clients() -> Vec<ClientStatus> {
    let pids = live_client_pids();
    let reports: Vec<(u32, String)> = mapping::forget_exited(&pids)
        .into_iter()
        .filter_map(|pid| exit_report(pid).map(|report| (pid, report)))
        .collect();
    for (pid, report) in &reports {
        log::warn!("Client {}: {}", pid, report);
    }
    exit_reports().lock().unwrap().extend(reports);
    let mut clients: Vec<ClientStatus> = pids.into_iter().map(classify).collect();
    clients.sort_by_key(|c| c.pid);
    clients
}

#[cfg(not(any(all(unix, not(target_os = "macos")), windows)))]
pub fn discover_clients() -> Vec<ClientStatus> {
    Vec::new()
}

/// Tracks when each pid was first *observed* carrying the bootstrap, so a client whose
/// engine is still coming up can be told apart from one whose supervisor never will.
///
/// Keyed on observation rather than on the user clicking Inject: auto-inject and a
/// launcher restart both have to land in the grace window with no click behind them.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
mod mapping {
    use std::collections::HashMap;
    use std::sync::{Mutex, OnceLock};
    use std::time::{Duration, Instant};

    /// Bounds the whole gap between the loader mapping the bootstrap and the supervisor
    /// binding its socket — the socket is the LAST thing `Supervisor.start` does, after
    /// the JVM boots, the shadow jar loads, and the engine finishes installing its hooks.
    /// Measured across the first injection of eleven real sessions at 5.4s–8.6s with a
    /// warm page cache; the bound is deliberately several times that because a cold cache
    /// or an on-access scan of the ~170 MB jar has no comparable ceiling. Overshooting
    /// only delays a real failure turning red — undershooting is the false "error".
    const DEFAULT_GRACE_SECS: u64 = 60;

    fn first_seen() -> &'static Mutex<HashMap<u32, Instant>> {
        static SEEN: OnceLock<Mutex<HashMap<u32, Instant>>> = OnceLock::new();
        SEEN.get_or_init(|| Mutex::new(HashMap::new()))
    }

    fn grace() -> Duration {
        let secs = std::env::var("INJECT_GRACE")
            .ok()
            .and_then(|v| v.parse::<u64>().ok())
            .unwrap_or(DEFAULT_GRACE_SECS);
        Duration::from_secs(secs)
    }

    pub fn observe(pid: u32) -> Instant {
        *first_seen()
            .lock()
            .unwrap()
            .entry(pid)
            .or_insert_with(Instant::now)
    }

    /// Drops the pids that are no longer running and returns them: each one carried the engine.
    pub fn forget_exited(live: &[u32]) -> Vec<u32> {
        let mut gone = Vec::new();
        first_seen().lock().unwrap().retain(|pid, _| {
            let alive = live.contains(pid);
            if !alive {
                gone.push(*pid);
            }
            alive
        });
        gone
    }

    pub fn still_starting(since: Instant) -> bool {
        since.elapsed() < grace()
    }

    pub fn grace_window() -> Duration {
        grace()
    }
}

#[cfg(any(all(unix, not(target_os = "macos")), windows))]
fn classify(pid: u32) -> ClientStatus {
    if !is_injected(pid) {
        return ClientStatus {
            pid,
            state: "not-injected".to_string(),
            version: None,
            reloads: 0,
            detail: None,
        };
    }

    let mapped_since = mapping::observe(pid);

    match send_command(pid, "STATUS") {
        Ok(resp) => parse_status(pid, &resp),
        Err(e) => {
            // A bootstrap that has given up says so, and waiting out the grace window after that
            // only hides the reason for another minute.
            let failure = bootstrap_failure(pid);
            let starting = failure.is_none() && mapping::still_starting(mapped_since);
            log::debug!(
                "discover_clients: pid {} has the engine mapped but STATUS failed after {:?}: {}",
                pid,
                mapped_since.elapsed(),
                e
            );
            // The supervisor is the only thing that answers the socket, so a client that never
            // binds one can only be explained by the bootstrap that would have started it.
            let detail = if starting { None } else { failure.or_else(|| bootstrap_complaint(pid)) };
            ClientStatus {
                pid,
                state: if starting { "starting" } else { "error" }.to_string(),
                version: None,
                reloads: 0,
                detail,
            }
        }
    }
}

/// Log the bootstrap left behind in the temp dir, written by the copy of itself loaded into `pid`
/// (`projectx_bootstrap.cpp`'s `projectx_bootstrap_start`).
///
/// Everything the bootstrap says about starting the JVM goes there and nowhere else, which is why a
/// client that maps the library and then fails to come up reaches the user as an unexplained
/// "Error" — the launcher log is silent because the launcher's part went fine.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
fn bootstrap_log_path(pid: u32) -> PathBuf {
    jvm_temp_dir().join(format!("projectx_log_{}.txt", pid))
}

/// How many trailing lines of the bootstrap log to put in the launcher log.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
const BOOTSTRAP_LOG_TAIL_LINES: usize = 20;

/// The bootstrap's account of why this client never came up: its last line for the UI, and the tail
/// around it into the launcher log for whoever has to read it afterwards.
///
/// Only called once a pid has already gone to "error", so re-reading the file per poll is bounded
/// by how long the user leaves a dead client on screen.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
fn bootstrap_complaint(pid: u32) -> Option<String> {
    let path = bootstrap_log_path(pid);
    let text = match std::fs::read_to_string(&path) {
        Ok(text) => text,
        Err(e) => {
            log::warn!(
                "pid {} has the engine mapped but never bound its control socket, and its \
                 bootstrap log {} could not be read: {}",
                pid,
                path.display(),
                e
            );
            return None;
        }
    };

    let lines: Vec<&str> = text.lines().map(str::trim_end).filter(|l| !l.is_empty()).collect();
    if lines.is_empty() {
        return None;
    }

    let from = lines.len().saturating_sub(BOOTSTRAP_LOG_TAIL_LINES);
    log::warn!(
        "pid {} has the engine mapped but never bound its control socket. Last {} lines of {}:\n{}",
        pid,
        lines.len() - from,
        path.display(),
        lines[from..].join("\n")
    );
    lines.last().map(|line| (*line).to_string())
}

/// Prefixes the bootstrap writes into its log (`projectx_bootstrap.cpp`): one when it is loaded into
/// a client, one on the line that says why the engine will not start.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
const SESSION_MARKER: &str = "[BOOTSTRAP] loaded into pid";
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
const FAILURE_MARKER: &str = "[ENGINE FAILED]";

/// The reason the bootstrap in `pid` gave for not starting the engine, if it has given up.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
pub(crate) fn bootstrap_failure(pid: u32) -> Option<String> {
    failure_in_log(&std::fs::read_to_string(bootstrap_log_path(pid)).ok()?)
}

/// Only the latest session counts: the log is appended to, and a reused pid can carry a previous
/// client's failure.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
fn failure_in_log(text: &str) -> Option<String> {
    let session = text.rfind(SESSION_MARKER).map_or(text, |at| &text[at..]);
    session
        .lines()
        .rev()
        .find_map(|line| line.trim().strip_prefix(FAILURE_MARKER))
        .map(|reason| reason.trim().to_string())
}

/// Crash and exit reports for injected clients that have gone, waiting for the UI to collect them.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
fn exit_reports() -> &'static std::sync::Mutex<Vec<(u32, String)>> {
    static REPORTS: std::sync::OnceLock<std::sync::Mutex<Vec<(u32, String)>>> = std::sync::OnceLock::new();
    REPORTS.get_or_init(Default::default)
}

/// Takes the reports for injected clients that crashed or exited abnormally since the last call.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
pub fn take_exit_reports() -> Vec<(u32, String)> {
    std::mem::take(&mut *exit_reports().lock().unwrap())
}

#[cfg(not(any(all(unix, not(target_os = "macos")), windows)))]
pub fn take_exit_reports() -> Vec<(u32, String)> {
    Vec::new()
}

/// What the bootstrap's forensics say about how an injected client ended, or `None` for a clean
/// close. The bootstrap writes them under the real home, not the one the launcher gives the client.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
fn exit_report(pid: u32) -> Option<String> {
    let logs = crate::plugins::home_dir().ok()?.join(".projectx").join("logs");
    let read = |name: &str| std::fs::read_to_string(logs.join(name)).ok();
    describe_exit(
        pid,
        read("last-crash.txt").as_deref(),
        read("last-exit.txt").as_deref(),
        read(&format!("hs_err_pid{}.log", pid)).as_deref(),
        &logs,
    )
}

/// `key: value` from a forensics file.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
fn forensics_field<'a>(text: &'a str, key: &str) -> Option<&'a str> {
    text.lines().find_map(|line| line.strip_prefix(key)?.strip_prefix(": ")).map(str::trim)
}

#[cfg(any(all(unix, not(target_os = "macos")), windows))]
fn describe_exit(
    pid: u32,
    crash: Option<&str>,
    exit: Option<&str>,
    hs_err: Option<&str>,
    logs: &std::path::Path,
) -> Option<String> {
    let pid_text = pid.to_string();
    let belongs = |text: &&str| forensics_field(text, "pid") == Some(pid_text.as_str());
    let crash = crash.filter(belongs);
    let exit_code = exit.filter(belongs).and_then(|text| forensics_field(text, "exit_code"));

    if let Some(report) = hs_err {
        let frame = report
            .lines()
            .skip_while(|line| !line.starts_with("# Problematic frame:"))
            .nth(1)
            .map(|line| format!(" in {}", line.trim_start_matches('#').trim()))
            .unwrap_or_default();
        let path = logs.join(format!("hs_err_pid{}.log", pid));
        return Some(format!("the client crashed in Java{}. Report: {}", frame, path.display()));
    }

    if let Some(crash) = crash.filter(|_| exit_code != Some("0")) {
        let kind = forensics_field(crash, "exception")
            .or_else(|| forensics_field(crash, "signal"))
            .unwrap_or("a fatal fault");
        let access = forensics_field(crash, "access").map(|a| format!(", {}", a)).unwrap_or_default();
        let location = crash
            .lines()
            .skip_while(|line| !line.starts_with("=== Native backtrace"))
            .nth(1)
            .map(|frame| frame.trim().rsplit(['\\', '/']).next().unwrap_or(frame).to_string())
            .filter(|frame| !frame.is_empty() && !frame.starts_with('?'))
            .map(|frame| format!(" in {}", frame))
            .unwrap_or_default();
        return Some(format!(
            "the client crashed: {}{}{}. Details: {}",
            kind,
            access,
            location,
            logs.join("last-crash.txt").display()
        ));
    }

    match exit_code {
        Some("0") => None,
        Some(code) => Some(format!(
            "the client exited with code {}. Details: {}",
            code,
            logs.join("last-exit.txt").display()
        )),
        // An exit file from a bootstrap too old to record its pid says nothing either way.
        None if exit.is_some_and(|text| forensics_field(text, "pid").is_none()) => None,
        None => Some(
            "the client closed without leaving a crash or exit record: it was killed, or died \
             before the engine could record why."
                .to_string(),
        ),
    }
}

/// Lines of the JVM's own stderr to copy into the launcher log when the engine fails to start.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
const STDERR_TAIL_LINES: usize = 15;

/// Follows a client that has just had the bootstrap loaded until its engine answers, its bootstrap
/// gives up, or the grace window runs out, and writes the outcome into the launcher log. Blocks, so
/// it belongs on the injection thread.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
pub fn report_engine_start(pid: u32) {
    use std::time::{Duration, Instant};

    let deadline = Instant::now() + mapping::grace_window();
    loop {
        if let Ok(reply) = send_command(pid, "STATUS") {
            let status = parse_status(pid, &reply);
            match status.state.as_str() {
                "active" => {
                    log::info!("Project X engine is running in pid {}.", pid);
                    return;
                }
                "error" => {
                    log::error!(
                        "The Project X engine failed to load in pid {}: {}",
                        pid,
                        status.detail.as_deref().unwrap_or("the supervisor gave no reason")
                    );
                    log_jvm_stderr_tail(pid);
                    return;
                }
                _ => {}
            }
        }
        if let Some(reason) = bootstrap_failure(pid) {
            log::error!("The Project X engine did not start in pid {}: {}", pid, reason);
            log_jvm_stderr_tail(pid);
            return;
        }
        if !is_injected(pid) {
            log::warn!(
                "pid {} exited or dropped the engine library before the engine started.",
                pid
            );
            return;
        }
        if Instant::now() >= deadline {
            log::warn!(
                "The Project X engine in pid {} has not answered after {}s.",
                pid,
                mapping::grace_window().as_secs()
            );
            bootstrap_complaint(pid);
            log_jvm_stderr_tail(pid);
            return;
        }
        std::thread::sleep(Duration::from_millis(500));
    }
}

#[cfg(any(all(unix, not(target_os = "macos")), windows))]
fn log_jvm_stderr_tail(pid: u32) {
    let path = jvm_temp_dir().join(format!("projectx_stderr_{}.txt", pid));
    let Ok(text) = std::fs::read_to_string(&path) else {
        return;
    };
    let lines: Vec<&str> = text.lines().filter(|l| !l.trim().is_empty()).collect();
    if lines.is_empty() {
        return;
    }
    let from = lines.len().saturating_sub(STDERR_TAIL_LINES);
    log::warn!("Last {} lines of {}:\n{}", lines.len() - from, path.display(), lines[from..].join("\n"));
}

#[cfg(all(unix, not(target_os = "macos")))]
fn live_client_pids() -> Vec<u32> {
    use crate::game::inject::linux::proc_is_rs2client;

    let entries = match std::fs::read_dir("/proc") {
        Ok(e) => e,
        Err(e) => {
            log::warn!("discover_clients: cannot read /proc: {}", e);
            return Vec::new();
        }
    };

    entries
        .flatten()
        .filter_map(|entry| entry.file_name().to_str().and_then(|s| s.parse().ok()))
        .filter(|pid| proc_is_rs2client(*pid))
        .collect()
}

#[cfg(windows)]
fn live_client_pids() -> Vec<u32> {
    crate::game::inject::windows::rs2client_pids()
}

/// Whether any `rs2client` is still running, injected or not. Skips the per-client STATUS round trips.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
pub fn any_client_running() -> bool {
    !live_client_pids().is_empty()
}

#[cfg(not(any(all(unix, not(target_os = "macos")), windows)))]
pub fn any_client_running() -> bool {
    false
}

/// First-inject the engine into a specific pid (delegates to
/// [`crate::game::inject::inject_into_pid`]). May block on a pkexec/sudo prompt
/// on Linux, so callers must run this off the UI thread.
pub fn inject_pid(pid: u32) -> anyhow::Result<()> {
    crate::game::inject::inject_into_pid(pid)
}

/// True when the engine bootstrap is currently mapped into `pid` — i.e. the
/// engine was injected at least once, so control actions go through the socket
/// rather than the first-injection path.
#[cfg(all(unix, not(target_os = "macos")))]
pub fn is_injected(pid: u32) -> bool {
    crate::game::inject::linux::is_already_injected(pid)
}

#[cfg(windows)]
pub fn is_injected(pid: u32) -> bool {
    crate::game::inject::windows::is_already_injected(pid)
}

#[cfg(not(any(all(unix, not(target_os = "macos")), windows)))]
pub fn is_injected(_pid: u32) -> bool {
    false
}

/// Parse a supervisor `STATUS` reply into a [`ClientStatus`].
///
/// Active/unloaded form: `OK <state> pid=<p> version=<v> reloads=<n>` with an
/// optional trailing ` error=<...>`. Anything that isn't a recognizable `OK`
/// reply (including `ERR ...`) maps to `"error"`.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
fn parse_status(pid: u32, resp: &str) -> ClientStatus {
    // The supervisor's error is free text with spaces, so it runs to the end of the line.
    let (fields, error) = match resp.split_once(" error=") {
        Some((fields, error)) => (fields, Some(error.trim().to_string()).filter(|e| !e.is_empty())),
        None => (resp, None),
    };
    let mut tokens = fields.split_whitespace();

    if tokens.next() != Some("OK") {
        return ClientStatus {
            pid,
            state: "error".to_string(),
            version: None,
            reloads: 0,
            // The supervisor answered, so it is the authority on its own failure — not a log
            // written before it existed.
            detail: Some(resp.trim().to_string()),
        };
    }

    // The supervisor calls its own in-flight load "loading"; the UI already has one
    // in-progress state for the not-yet-reachable case and it is called "starting".
    let state = match tokens.next().unwrap_or("error") {
        "loading" => "starting",
        reported => reported,
    }
    .to_string();

    let mut version: Option<String> = None;
    let mut reloads: u32 = 0;
    for tok in tokens {
        if let Some(v) = tok.strip_prefix("version=") {
            // The supervisor sends `-` for an unloaded engine; treat as None.
            version = if v == "-" || v.is_empty() {
                None
            } else {
                Some(v.to_string())
            };
        } else if let Some(n) = tok.strip_prefix("reloads=") {
            reloads = n.parse().unwrap_or(0);
        }
    }

    ClientStatus {
        pid,
        state,
        version,
        reloads,
        detail: error,
    }
}

#[cfg(all(test, any(all(unix, not(target_os = "macos")), windows)))]
mod tests {
    use super::*;

    #[test]
    fn the_failure_line_of_the_latest_session_is_the_reason() {
        let log = "[BOOTSTRAP] loaded into pid 7
                   [ENGINE FAILED] Java at /old refused to start
                   [BOOTSTRAP] loaded into pid 7
                   Using Java 27 from JAVA_HOME: /usr/lib/jvm/java-27-openjdk
                   [ENGINE FAILED] The engine supervisor threw while starting: boom
                   Shared library unloaded!
";
        assert_eq!(
            failure_in_log(log).as_deref(),
            Some("The engine supervisor threw while starting: boom")
        );
    }

    #[test]
    fn an_earlier_session_failure_does_not_count_against_a_new_one() {
        let log = "[BOOTSTRAP] loaded into pid 7
                   [ENGINE FAILED] No Java 25 or newer was found
                   [BOOTSTRAP] loaded into pid 7
                   Using Java 27 from JAVA_HOME: /usr/lib/jvm/java-27-openjdk
";
        assert_eq!(failure_in_log(log), None);
    }

    #[test]
    fn a_supervisor_error_reaches_the_client_card() {
        let status = parse_status(
            7,
            "OK error pid=7 version=- reloads=0 error=java.lang.ExceptionInInitializerError (caused by java.lang.IllegalStateException: no offset table for linux 951-5)",
        );
        assert_eq!(status.state, "error");
        assert_eq!(status.version, None);
        assert_eq!(
            status.detail.as_deref(),
            Some(
                "java.lang.ExceptionInInitializerError (caused by java.lang.IllegalStateException: no offset table for linux 951-5)"
            )
        );
    }

    #[test]
    fn an_active_engine_has_no_detail() {
        let status = parse_status(7, "OK active pid=7 version=1.8.0 reloads=2");
        assert_eq!(status.state, "active");
        assert_eq!(status.version.as_deref(), Some("1.8.0"));
        assert_eq!(status.reloads, 2);
        assert_eq!(status.detail, None);
    }

    const WINDOWS_CRASH: &str = "=== PROJECT X CRASH FORENSICS ===\npid: 4242\nexception: 0xc0000005 (EXCEPTION_ACCESS_VIOLATION)\nfault_addr: 0x7ff6aa0012ab\naccess: read of 0x18\ntid: 9\n\n=== Native backtrace ===\n  C:\\ProgramData\\Jagex\\launcher\\rs2client.exe+0x12ab\n=== END ===\n";

    fn logs() -> std::path::PathBuf {
        std::path::PathBuf::from("logs")
    }

    #[test]
    fn a_crash_names_the_fault_and_where_it_happened() {
        let report = describe_exit(4242, Some(WINDOWS_CRASH), None, None, &logs()).unwrap();
        assert!(report.starts_with(
            "the client crashed: 0xc0000005 (EXCEPTION_ACCESS_VIOLATION), read of 0x18 in rs2client.exe+0x12ab."
        ), "{}", report);
    }

    #[test]
    fn a_handled_fault_before_a_clean_close_is_not_a_crash() {
        let exit = "=== PROJECT X EXIT FORENSICS ===\npid: 4242\nvia: ExitProcess()\nexit_code: 0\n";
        assert_eq!(describe_exit(4242, Some(WINDOWS_CRASH), Some(exit), None, &logs()), None);
    }

    #[test]
    fn another_clients_records_are_ignored() {
        let exit = "=== PROJECT X EXIT FORENSICS ===\npid: 7\nvia: ExitProcess()\nexit_code: 0\n";
        let report = describe_exit(4242, Some(WINDOWS_CRASH), Some(exit), None, &logs());
        assert!(report.unwrap().starts_with("the client crashed"));
        let report = describe_exit(99, Some(WINDOWS_CRASH), Some(exit), None, &logs()).unwrap();
        assert!(report.starts_with("the client closed without leaving"), "{}", report);
    }

    #[test]
    fn a_java_crash_points_at_its_hs_err_report() {
        let hs_err = "# A fatal error has been detected\n# Problematic frame:\n# C  [jvm.dll+0x1234]\n";
        let report = describe_exit(4242, None, None, Some(hs_err), &logs()).unwrap();
        assert!(report.starts_with("the client crashed in Java in C  [jvm.dll+0x1234]."), "{}", report);
    }

    #[test]
    fn a_nonzero_exit_is_reported_and_a_clean_one_is_not() {
        let failed = "=== PROJECT X EXIT FORENSICS ===\npid: 5\nvia: TerminateProcess(self)\nexit_code: 3221225477\n";
        assert!(describe_exit(5, None, Some(failed), None, &logs()).unwrap().contains("code 3221225477"));
        let old_format = "=== PROJECT X EXIT FORENSICS ===\nvia: exit()\nexit_code: 0\n";
        assert_eq!(describe_exit(5, None, Some(old_format), None, &logs()), None);
    }

    #[test]
    fn a_log_from_a_bootstrap_without_markers_reports_nothing() {
        assert_eq!(failure_in_log("Failed to create JVM -1
"), None);
    }
}
