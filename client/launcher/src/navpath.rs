//! The local route planner: one `navpath-service` per OS user, shared by every injected client, which finds
//! it through `~/.projectx/navpath/endpoint.json`.

use anyhow::{anyhow, bail, Context, Result};
use flate2::read::GzDecoder;
use fs2::FileExt;
use serde::{Deserialize, Serialize};
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::{Mutex, MutexGuard};
use std::thread;
use std::time::{Duration, Instant};
use tokio::sync::Mutex as AsyncMutex;
use tokio::task::spawn_blocking;
use zip::ZipArchive;

use crate::config::{atomic_write, Paths, PluginsConfig};
use crate::engine::download_to;
use crate::plugins::{self, Catalog, ReleaseArtifact};
use crate::ui::ipc::set_executable;

const EXE: &str = std::env::consts::EXE_SUFFIX;
const SERVICE: &str = "navpath-service";
const BUILDER: &str = "navpath-builder";
const BASELINE: &str = "-baseline";
const SNAPSHOT: &str = "graph.snapshot";
const LABEL: &str = "route planner";
const MAP_LABEL: &str = "route planner map";

const READY_TIMEOUT: Duration = Duration::from_secs(90);
const READY_POLL: Duration = Duration::from_millis(100);
const HEALTH_TIMEOUT: Duration = Duration::from_secs(2);
const START_ATTEMPTS: usize = 3;
const WATCH_INTERVAL: Duration = Duration::from_secs(2);
const STABLE_RUN: Duration = Duration::from_secs(300);
const MAX_BACKOFF: Duration = Duration::from_secs(60);
const STOP_TIMEOUT: Duration = Duration::from_secs(5);
const EXIT_LOCK_WAIT: Duration = Duration::from_secs(5);
const LAUNCH_GRACE: Duration = Duration::from_secs(600);
const LOG_ROTATE_BYTES: u64 = 5 * 1024 * 1024;
const LOG_TAIL_LINES: usize = 20;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Endpoint {
    pub url: String,
    pub pid: u32,
    pub map: String,
    pub version: String,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
struct Installed {
    #[serde(default)]
    version: String,
    #[serde(default)]
    archive_sha256: String,
    #[serde(default)]
    binaries: String,
    #[serde(default)]
    map: String,
    #[serde(default)]
    map_sha256: String,
    #[serde(default)]
    graph: String,
    #[serde(default)]
    graph_key: String,
}

#[derive(Debug, Clone)]
struct Layout {
    root: PathBuf,
    endpoint: PathBuf,
}

impl Layout {
    fn resolve() -> Result<Self> {
        Ok(Layout {
            root: Paths::new()?.data_dir.join("navpath"),
            endpoint: endpoint_file()?,
        })
    }

    fn state(&self) -> PathBuf {
        self.root.join("state.json")
    }

    fn lock(&self) -> PathBuf {
        self.root.join("navpath.lock")
    }

    fn service_log(&self) -> PathBuf {
        self.root.join("service.log")
    }

    fn builder_log(&self) -> PathBuf {
        self.root.join("builder.log")
    }

    fn downloads(&self) -> PathBuf {
        self.root.join("downloads")
    }
}

#[derive(Debug, Clone)]
struct Wanted {
    service: PathBuf,
    snapshot: PathBuf,
    map: String,
    version: String,
}

#[derive(Debug, PartialEq)]
enum Plan {
    Reuse(Endpoint),
    Start { retire: Option<Endpoint> },
}

fn plan(
    existing: Option<Endpoint>,
    map: &str,
    version: &str,
    alive: impl Fn(u32) -> bool,
    ready: impl Fn(&str) -> bool,
) -> Plan {
    let Some(endpoint) = existing.filter(|e| alive(e.pid)) else {
        return Plan::Start { retire: None };
    };
    if endpoint.map.eq_ignore_ascii_case(map) && endpoint.version == version && ready(&endpoint.url) {
        Plan::Reuse(endpoint)
    } else {
        Plan::Start { retire: Some(endpoint) }
    }
}

pub fn supported() -> bool {
    matches!(plugins::platform_tag(), Some("linux-x86_64" | "windows-x86_64"))
}

/// The engine reads `user.home`, which the JVM takes from the account, never from the `HOME` the launcher
/// redirects for the client.
fn endpoint_file() -> Result<PathBuf> {
    Ok(real_home()?.join(".projectx").join("navpath").join("endpoint.json"))
}

#[cfg(target_os = "linux")]
fn real_home() -> Result<PathBuf> {
    passwd_home().map(Ok).unwrap_or_else(plugins::home_dir)
}

#[cfg(not(target_os = "linux"))]
fn real_home() -> Result<PathBuf> {
    plugins::home_dir()
}

#[cfg(target_os = "linux")]
fn passwd_home() -> Option<PathBuf> {
    use std::ffi::{CStr, OsStr};
    use std::os::unix::ffi::OsStrExt;

    let mut buf = vec![0 as libc::c_char; 16 * 1024];
    // SAFETY: passwd is plain data; getpwuid_r writes it and the strings it points at into `buf`.
    let mut entry: libc::passwd = unsafe { std::mem::zeroed() };
    let mut found: *mut libc::passwd = std::ptr::null_mut();
    let rc = unsafe { libc::getpwuid_r(libc::getuid(), &mut entry, buf.as_mut_ptr(), buf.len(), &mut found) };
    if rc != 0 || found.is_null() || entry.pw_dir.is_null() {
        return None;
    }
    let dir = unsafe { CStr::from_ptr(entry.pw_dir) };
    let home = PathBuf::from(OsStr::from_bytes(dir.to_bytes()));
    home.is_absolute().then_some(home)
}

fn cpu_has_avx2() -> bool {
    #[cfg(target_arch = "x86_64")]
    {
        is_x86_feature_detected!("avx2")
    }
    #[cfg(not(target_arch = "x86_64"))]
    {
        true
    }
}

fn binary_name(base: &str, avx2: bool) -> String {
    if avx2 {
        format!("{base}{EXE}")
    } else {
        format!("{base}{BASELINE}{EXE}")
    }
}

fn archive_members() -> Vec<String> {
    [SERVICE, BUILDER]
        .iter()
        .flat_map(|base| [binary_name(base, true), binary_name(base, false)])
        .collect()
}

fn short(sha: &str) -> String {
    sha.chars().take(16).collect::<String>().to_ascii_lowercase()
}

fn graph_key(state: &Installed) -> String {
    short(&plugins::hex_digest(
        format!("{}:{}", state.map_sha256, state.archive_sha256).as_bytes(),
    ))
}

fn load_state(layout: &Layout) -> Installed {
    fs::read_to_string(layout.state())
        .ok()
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default()
}

fn save_state(layout: &Layout, state: &Installed) -> Result<()> {
    atomic_write(&layout.state(), &serde_json::to_vec_pretty(state)?)
}

fn binaries_present(layout: &Layout, state: &Installed) -> bool {
    let avx2 = cpu_has_avx2();
    !state.binaries.is_empty()
        && [SERVICE, BUILDER]
            .iter()
            .all(|base| layout.root.join(&state.binaries).join(binary_name(base, avx2)).is_file())
}

fn map_present(layout: &Layout, state: &Installed) -> bool {
    !state.map.is_empty() && !state.map_sha256.is_empty() && layout.root.join(&state.map).is_file()
}

fn graph_present(layout: &Layout, state: &Installed) -> bool {
    !state.graph.is_empty()
        && state.graph_key == graph_key(state)
        && layout.root.join(&state.graph).join(SNAPSHOT).is_file()
}

fn read_endpoint(path: &Path) -> Option<Endpoint> {
    serde_json::from_str(&fs::read_to_string(path).ok()?).ok()
}

/// A reader holding the file open makes a Windows rename fail for a moment, so it is retried.
fn write_endpoint(path: &Path, endpoint: &Endpoint) -> Result<()> {
    let json = serde_json::to_vec(endpoint)?;
    let mut attempt = 0;
    loop {
        match atomic_write(path, &json) {
            Ok(()) => return Ok(()),
            Err(e) if attempt >= 10 => return Err(e),
            Err(_) => {
                attempt += 1;
                thread::sleep(Duration::from_millis(50));
            }
        }
    }
}

fn remove_endpoint_for(path: &Path, pid: u32) {
    if read_endpoint(path).is_some_and(|e| e.pid == pid) {
        let _ = fs::remove_file(path);
    }
}

fn unpack(archive: &Path, dest: &Path, wanted: &[String]) -> Result<()> {
    fs::create_dir_all(dest).with_context(|| format!("Failed to create {}", dest.display()))?;
    let name = archive
        .file_name()
        .map(|n| n.to_string_lossy().to_ascii_lowercase())
        .unwrap_or_default();
    let mut found: Vec<String> = Vec::new();
    let mut take = |entry: &str, reader: &mut dyn Read| -> Result<()> {
        let base = entry.rsplit(['/', '\\']).next().unwrap_or_default();
        let Some(member) = wanted.iter().find(|w| w.as_str() == base) else {
            return Ok(());
        };
        let target = dest.join(member);
        let mut out = File::create(&target).with_context(|| format!("Failed to create {}", target.display()))?;
        io::copy(reader, &mut out).with_context(|| format!("Failed to extract {}", member))?;
        drop(out);
        set_executable(&target);
        found.push(member.clone());
        Ok(())
    };

    let file = File::open(archive).with_context(|| format!("Failed to open {}", archive.display()))?;
    if name.ends_with(".zip") {
        let mut zip = ZipArchive::new(file).with_context(|| format!("{} is not a zip", archive.display()))?;
        for index in 0..zip.len() {
            let mut entry = zip.by_index(index)?;
            if entry.is_file() {
                let entry_name = entry.name()?.into_owned();
                take(&entry_name, &mut entry)?;
            }
        }
    } else if name.ends_with(".tar.gz") || name.ends_with(".tgz") {
        let mut tar = tar::Archive::new(GzDecoder::new(file));
        for entry in tar.entries()? {
            let mut entry = entry?;
            if entry.header().entry_type().is_file() {
                let entry_name = entry.path()?.to_string_lossy().to_string();
                take(&entry_name, &mut entry)?;
            }
        }
    } else {
        bail!("{} is neither a .zip nor a .tar.gz", archive.display());
    }

    let missing: Vec<&String> = wanted.iter().filter(|w| !found.contains(w)).collect();
    if !missing.is_empty() {
        bail!("{} lacks {:?}", archive.display(), missing);
    }
    Ok(())
}

static ENSURE: AsyncMutex<()> = AsyncMutex::const_new(());

/// Install, update and start the route planner, or adopt the one already serving. `None` on a platform the
/// release publishes no route planner for.
pub async fn ensure(
    client: &reqwest::Client,
    cfg: &PluginsConfig,
    progress: &(dyn Fn(String) + Sync),
) -> Result<Option<Endpoint>> {
    if !supported() {
        log::info!("The route planner is not published for this platform; skipping it");
        return Ok(None);
    }
    let _serial = ENSURE.lock().await;
    let layout = Layout::resolve()?;
    fs::create_dir_all(&layout.root).with_context(|| format!("Failed to create {}", layout.root.display()))?;

    let catalog = match plugins::fetch_catalog(client, cfg).await {
        Ok(catalog) => Some(catalog),
        Err(e) => {
            log::warn!("Route planner update check failed ({}); using what is installed", e);
            None
        }
    };

    let mut state = load_state(&layout);
    install_binaries(client, catalog.as_ref(), &layout, &mut state, progress).await?;
    install_map(client, catalog.as_ref(), &layout, &mut state, progress).await?;

    if !graph_present(&layout, &state) {
        progress("Building the route planner graph...".to_string());
        let builder = layout.root.join(&state.binaries).join(binary_name(BUILDER, cpu_has_avx2()));
        let map = layout.root.join(&state.map);
        let key = graph_key(&state);
        let build_layout = layout.clone();
        let graph = spawn_blocking(move || build_graph(&build_layout, &builder, &map, &key))
            .await
            .map_err(|e| anyhow!("graph build task failed: {}", e))??;
        state.graph = graph;
        state.graph_key = graph_key(&state);
        save_state(&layout, &state)?;
    }

    let wanted = Wanted {
        service: layout.root.join(&state.binaries).join(binary_name(SERVICE, cpu_has_avx2())),
        snapshot: layout.root.join(&state.graph).join(SNAPSHOT),
        map: state.map_sha256.clone(),
        version: state.version.clone(),
    };
    progress("Starting the route planner...".to_string());
    let endpoint = spawn_blocking(move || {
        let endpoint = ensure_running(&layout, &wanted)?;
        prune(&layout, &state);
        Ok::<_, anyhow::Error>(endpoint)
    })
    .await
    .map_err(|e| anyhow!("route planner start task failed: {}", e))??;
    Ok(Some(endpoint))
}

/// Watch a route planner left running by an earlier launcher, without installing anything.
pub async fn adopt() {
    if !supported() {
        return;
    }
    let result = spawn_blocking(|| {
        let layout = Layout::resolve()?;
        let _lock = lock(&layout, None)?;
        let Some(endpoint) = read_endpoint(&layout.endpoint) else {
            return Ok(None);
        };
        if !os::is_alive(endpoint.pid) || !health_ready(&endpoint.url) {
            return Ok(None);
        }
        let state = load_state(&layout);
        let wanted = Wanted {
            service: layout.root.join(&state.binaries).join(binary_name(SERVICE, cpu_has_avx2())),
            snapshot: layout.root.join(&state.graph).join(SNAPSHOT),
            map: endpoint.map.clone(),
            version: endpoint.version.clone(),
        };
        supervisor().adopt(&layout, &wanted, endpoint.clone());
        Ok::<_, anyhow::Error>(Some(endpoint))
    })
    .await;
    match result {
        Ok(Ok(Some(endpoint))) => log::info!("Adopted the route planner at {} (pid {})", endpoint.url, endpoint.pid),
        Ok(Ok(None)) => {}
        Ok(Err(e)) => log::warn!("Could not check for a running route planner: {:#}", e),
        Err(e) => log::warn!("Route planner adopt task failed: {}", e),
    }
}

/// The Jagex launcher starts `rs2client` well after it is spawned, so a launcher closing right after a launch
/// would otherwise see no client.
pub fn note_client_launch() {
    supervisor().launched = Some(Instant::now());
}

/// Stop the route planner unless a client may still use it; a running one is left for the next launcher.
pub fn on_launcher_exit() {
    if !supported() {
        return;
    }
    if crate::game::control::any_client_running() {
        log::info!("Clients are still running, so the route planner stays up for them");
        return;
    }
    if supervisor().launched.is_some_and(|at| at.elapsed() < LAUNCH_GRACE) {
        log::info!("A client was launched moments ago and may still be starting, so the route planner stays up");
        return;
    }
    let Ok(layout) = Layout::resolve() else {
        return;
    };
    let Ok(_lock) = lock(&layout, Some(EXIT_LOCK_WAIT)) else {
        log::warn!("The route planner is busy; leaving it running");
        return;
    };
    let mut sup = supervisor();
    sup.stopped = true;
    let child = sup.child.take();
    match read_endpoint(&layout.endpoint) {
        Some(endpoint) => {
            stop(endpoint.pid, child);
            remove_endpoint_for(&layout.endpoint, endpoint.pid);
            log::info!("Stopped the route planner (pid {})", endpoint.pid);
        }
        None => {
            if let Some(child) = child {
                kill_child(child);
            }
        }
    }
}

async fn install_binaries(
    client: &reqwest::Client,
    catalog: Option<&Catalog>,
    layout: &Layout,
    state: &mut Installed,
    progress: &(dyn Fn(String) + Sync),
) -> Result<()> {
    let artifact = catalog
        .zip(plugins::platform_tag())
        .and_then(|(catalog, platform)| catalog.navpath_for(platform))
        .filter(|a| !a.sha256.is_empty());
    let Some(artifact) = artifact else {
        if binaries_present(layout, state) {
            return Ok(());
        }
        bail!("The route planner is not installed and the release publishes none for this platform");
    };
    if state.archive_sha256.eq_ignore_ascii_case(&artifact.sha256) && binaries_present(layout, state) {
        return Ok(());
    }

    progress("Updating the route planner...".to_string());
    match fetch_binaries(client, artifact, layout, progress).await {
        Ok(dir) => {
            state.version = artifact.version.clone();
            state.archive_sha256 = artifact.sha256.to_ascii_lowercase();
            state.binaries = dir;
            save_state(layout, state)
        }
        Err(e) if binaries_present(layout, state) => {
            log::warn!("Could not update the route planner ({:#}); keeping the installed one", e);
            Ok(())
        }
        Err(e) => Err(e),
    }
}

async fn fetch_binaries(
    client: &reqwest::Client,
    artifact: &ReleaseArtifact,
    layout: &Layout,
    progress: &(dyn Fn(String) + Sync),
) -> Result<String> {
    let name = format!("bin-{}", short(&artifact.sha256));
    let dir = layout.root.join(&name);
    let members = archive_members();
    if members.iter().all(|m| dir.join(m).is_file()) {
        return Ok(name);
    }

    let downloads = layout.downloads();
    fs::create_dir_all(&downloads).with_context(|| format!("Failed to create {}", downloads.display()))?;
    let archive = download_to(client, artifact, &downloads, LABEL, progress).await?;
    let part = layout.root.join(format!("{name}.part"));
    spawn_blocking(move || {
        let _ = fs::remove_dir_all(&part);
        let unpacked = unpack(&archive, &part, &members);
        let _ = fs::remove_file(&archive);
        unpacked?;
        let _ = fs::remove_dir_all(&dir);
        fs::rename(&part, &dir).with_context(|| format!("Failed to install {}", dir.display()))
    })
    .await
    .map_err(|e| anyhow!("unpack task failed: {}", e))??;
    Ok(name)
}

async fn install_map(
    client: &reqwest::Client,
    catalog: Option<&Catalog>,
    layout: &Layout,
    state: &mut Installed,
    progress: &(dyn Fn(String) + Sync),
) -> Result<()> {
    let artifact = catalog
        .and_then(|c| c.navpath_map.as_ref())
        .filter(|a| !a.sha256.is_empty());
    let Some(artifact) = artifact else {
        if map_present(layout, state) {
            return Ok(());
        }
        bail!("The route planner map is not installed and the release publishes none");
    };
    if state.map_sha256.eq_ignore_ascii_case(&artifact.sha256) && map_present(layout, state) {
        return Ok(());
    }

    progress("Updating the route planner map...".to_string());
    match download_to(client, artifact, &layout.root, MAP_LABEL, progress).await {
        Ok(_) => {
            state.map = artifact.file.clone();
            state.map_sha256 = artifact.sha256.to_ascii_lowercase();
            save_state(layout, state)
        }
        Err(e) if map_present(layout, state) => {
            log::warn!("Could not update the route planner map ({:#}); keeping the installed one", e);
            Ok(())
        }
        Err(e) => Err(e),
    }
}

fn build_graph(layout: &Layout, builder: &Path, map: &Path, key: &str) -> Result<String> {
    let name = format!("graph-{key}");
    let dir = layout.root.join(&name);
    let part = layout.root.join(format!("{name}.part"));
    let _ = fs::remove_dir_all(&part);
    fs::create_dir_all(&part).with_context(|| format!("Failed to create {}", part.display()))?;

    log::info!("Building the route planner graph from {}", map.display());
    let started = Instant::now();
    let log_path = layout.builder_log();
    let log = File::create(&log_path).with_context(|| format!("Failed to create {}", log_path.display()))?;
    let mut cmd = Command::new(builder);
    cmd.arg("--sqlite")
        .arg(map)
        .arg("--out-snapshot")
        .arg(part.join(SNAPSHOT))
        .arg("--out-tiles")
        .arg(part.join("tiles.bin"))
        .arg("--out-walkable")
        .arg(part.join("walkableTiles.bin"))
        .arg("--landmarks")
        .arg("64")
        .stdin(Stdio::null())
        .stdout(log.try_clone()?)
        .stderr(log);
    os::hide_window(&mut cmd);
    let status = cmd
        .status()
        .with_context(|| format!("Failed to run {}", builder.display()))?;
    if !status.success() || !part.join(SNAPSHOT).is_file() {
        log_tail(&log_path);
        let _ = fs::remove_dir_all(&part);
        bail!("{} failed ({})", BUILDER, status);
    }

    if dir.exists() && fs::remove_dir_all(&dir).is_err() && dir.join(SNAPSHOT).is_file() {
        // A complete graph a running service still maps cannot be deleted on Windows, and is the same graph.
        let _ = fs::remove_dir_all(&part);
        return Ok(name);
    }
    fs::rename(&part, &dir).with_context(|| format!("Failed to install {}", dir.display()))?;
    log::info!("Built the route planner graph in {:.1}s", started.elapsed().as_secs_f64());
    Ok(name)
}

/// Delete what an earlier install left behind. A file a running service still holds stays until next time.
fn prune(layout: &Layout, state: &Installed) {
    let Ok(entries) = fs::read_dir(&layout.root) else {
        return;
    };
    let keep = [state.binaries.as_str(), state.graph.as_str(), state.map.as_str()];
    for path in entries.flatten().map(|e| e.path()) {
        let Some(name) = path.file_name().map(|n| n.to_string_lossy().to_string()) else {
            continue;
        };
        let ours = name.starts_with("bin-") || name.starts_with("graph-") || name.starts_with("navpath-map-");
        if !ours || keep.contains(&name.as_str()) {
            continue;
        }
        let removed = if path.is_dir() { fs::remove_dir_all(&path) } else { fs::remove_file(&path) };
        if removed.is_ok() {
            log::info!("Removed superseded {}", path.display());
        }
    }
}

struct Supervisor {
    layout: Option<Layout>,
    wanted: Option<Wanted>,
    child: Option<Child>,
    endpoint: Option<Endpoint>,
    since: Option<Instant>,
    restarts: u32,
    retry_at: Option<Instant>,
    watching: bool,
    stopped: bool,
    launched: Option<Instant>,
}

static SUPERVISOR: Mutex<Supervisor> = Mutex::new(Supervisor {
    layout: None,
    wanted: None,
    child: None,
    endpoint: None,
    since: None,
    restarts: 0,
    retry_at: None,
    watching: false,
    stopped: false,
    launched: None,
});

fn supervisor() -> MutexGuard<'static, Supervisor> {
    SUPERVISOR.lock().unwrap_or_else(|e| e.into_inner())
}

impl Supervisor {
    fn reap(&mut self) {
        if let Some(child) = self.child.as_mut() {
            if !matches!(child.try_wait(), Ok(None)) {
                self.child = None;
            }
        }
    }

    fn track(&mut self, layout: &Layout, wanted: &Wanted, endpoint: Endpoint) {
        if self.endpoint.as_ref().map(|e| e.pid) != Some(endpoint.pid) {
            self.since = Some(Instant::now());
        }
        self.layout = Some(layout.clone());
        self.wanted = Some(wanted.clone());
        self.endpoint = Some(endpoint);
        self.retry_at = None;
        self.stopped = false;
        if !self.watching {
            self.watching = true;
            let spawned = thread::Builder::new()
                .name("navpath-watchdog".into())
                .spawn(|| loop {
                    thread::sleep(WATCH_INTERVAL);
                    watch_tick();
                });
            if let Err(e) = spawned {
                self.watching = false;
                log::warn!("Could not start the route planner watchdog: {}", e);
            }
        }
    }

    fn adopt(&mut self, layout: &Layout, wanted: &Wanted, endpoint: Endpoint) {
        if self.child.as_ref().is_some_and(|c| c.id() != endpoint.pid) {
            if let Some(stale) = self.child.take() {
                kill_child(stale);
            }
        }
        self.track(layout, wanted, endpoint);
    }
}

fn lock(layout: &Layout, wait: Option<Duration>) -> Result<File> {
    fs::create_dir_all(&layout.root)?;
    let file = OpenOptions::new()
        .create(true)
        .truncate(false)
        .write(true)
        .open(layout.lock())
        .with_context(|| format!("Failed to open {}", layout.lock().display()))?;
    let Some(wait) = wait else {
        file.lock_exclusive().context("Failed to lock the route planner")?;
        return Ok(file);
    };
    let deadline = Instant::now() + wait;
    while file.try_lock_exclusive().is_err() {
        if Instant::now() >= deadline {
            bail!("the route planner lock is held");
        }
        thread::sleep(Duration::from_millis(100));
    }
    Ok(file)
}

fn ensure_running(layout: &Layout, wanted: &Wanted) -> Result<Endpoint> {
    let _lock = lock(layout, None)?;
    let mut sup = supervisor();
    sup.reap();

    match plan(read_endpoint(&layout.endpoint), &wanted.map, &wanted.version, os::is_alive, health_ready) {
        Plan::Reuse(endpoint) => {
            log::info!("Reusing the route planner at {} (pid {})", endpoint.url, endpoint.pid);
            sup.adopt(layout, wanted, endpoint.clone());
            Ok(endpoint)
        }
        Plan::Start { retire } => {
            let (child, endpoint) = start(layout, wanted)?;
            if let Err(e) = write_endpoint(&layout.endpoint, &endpoint) {
                kill_child(child);
                return Err(e.context("Failed to publish the route planner endpoint"));
            }
            log::info!(
                "Route planner {} serving at {} (pid {})",
                endpoint.version,
                endpoint.url,
                endpoint.pid
            );
            let previous = sup.child.replace(child);
            match retire {
                Some(old) => {
                    log::info!("Stopping the superseded route planner (pid {})", old.pid);
                    stop(old.pid, previous);
                }
                None => {
                    if let Some(previous) = previous {
                        kill_child(previous);
                    }
                }
            }
            sup.track(layout, wanted, endpoint.clone());
            Ok(endpoint)
        }
    }
}

fn start(layout: &Layout, wanted: &Wanted) -> Result<(Child, Endpoint)> {
    if !wanted.service.is_file() {
        bail!("{} is missing", wanted.service.display());
    }
    let mut last = anyhow!("the route planner did not start");
    for attempt in 1..=START_ATTEMPTS {
        let port = free_port()?;
        let url = format!("http://127.0.0.1:{port}");
        let mut child = spawn_service(layout, wanted, port)?;
        match await_ready(&mut child, &url) {
            Ok(()) => {
                let endpoint = Endpoint {
                    url,
                    pid: child.id(),
                    map: wanted.map.clone(),
                    version: wanted.version.clone(),
                };
                return Ok((child, endpoint));
            }
            Err(e) => {
                log::warn!("Route planner start {} of {} failed: {:#}", attempt, START_ATTEMPTS, e);
                kill_child(child);
                log_tail(&layout.service_log());
                last = e;
            }
        }
    }
    Err(last)
}

fn free_port() -> Result<u16> {
    let listener = TcpListener::bind("127.0.0.1:0").context("No free localhost port")?;
    Ok(listener.local_addr()?.port())
}

fn spawn_service(layout: &Layout, wanted: &Wanted, port: u16) -> Result<Child> {
    let log_path = layout.service_log();
    if fs::metadata(&log_path).is_ok_and(|m| m.len() > LOG_ROTATE_BYTES) {
        let _ = fs::rename(&log_path, layout.root.join("service.previous.log"));
    }
    let log = OpenOptions::new()
        .create(true)
        .append(true)
        .open(&log_path)
        .with_context(|| format!("Failed to open {}", log_path.display()))?;

    let mut cmd = Command::new(&wanted.service);
    cmd.env("SNAPSHOT_PATH", &wanted.snapshot)
        .env("NAVPATH_HOST", "127.0.0.1")
        .env("NAVPATH_PORT", port.to_string())
        .env("RUST_LOG", "warn")
        .env("NAVPATH_WORKER_THREADS", "2")
        .env("NAVPATH_MAX_CONCURRENT_SEARCHES", "4")
        .current_dir(&layout.root)
        .stdin(Stdio::null())
        .stdout(log.try_clone()?)
        .stderr(log);
    os::hide_window(&mut cmd);
    os::detach(&mut cmd);
    cmd.spawn()
        .with_context(|| format!("Failed to start {}", wanted.service.display()))
}

fn await_ready(child: &mut Child, url: &str) -> Result<()> {
    let deadline = Instant::now() + READY_TIMEOUT;
    loop {
        if let Some(status) = child.try_wait()? {
            bail!("it exited ({}) before it was ready", status);
        }
        if health_ready(url) {
            return Ok(());
        }
        if Instant::now() >= deadline {
            bail!("it was not ready after {:?}", READY_TIMEOUT);
        }
        thread::sleep(READY_POLL);
    }
}

fn health_ready(url: &str) -> bool {
    let Some(addr) = url.strip_prefix("http://").and_then(|a| a.trim_end_matches('/').parse::<SocketAddr>().ok()) else {
        return false;
    };
    if !addr.ip().is_loopback() {
        return false;
    }
    let Ok(mut stream) = TcpStream::connect_timeout(&addr, HEALTH_TIMEOUT) else {
        return false;
    };
    let _ = stream.set_read_timeout(Some(HEALTH_TIMEOUT));
    let _ = stream.set_write_timeout(Some(HEALTH_TIMEOUT));
    let request = format!("GET /health HTTP/1.1\r\nHost: {addr}\r\nConnection: close\r\n\r\n");
    if stream.write_all(request.as_bytes()).is_err() {
        return false;
    }
    let mut response = Vec::new();
    let _ = stream.take(64 * 1024).read_to_end(&mut response);
    reports_ready(&String::from_utf8_lossy(&response))
}

/// The service flips `ready` even when its snapshot failed to load, so loaded counts are required too.
fn reports_ready(response: &str) -> bool {
    #[derive(Deserialize)]
    struct Health {
        #[serde(default)]
        ready: bool,
        #[serde(default)]
        counts: Option<serde_json::Value>,
    }
    let body = response.split_once("\r\n\r\n").map(|(_, b)| b).unwrap_or_default();
    let (Some(open), Some(close)) = (body.find('{'), body.rfind('}')) else {
        return false;
    };
    serde_json::from_str::<Health>(&body[open..=close])
        .is_ok_and(|h| h.ready && h.counts.is_some_and(|c| !c.is_null()))
}

fn stop(pid: u32, child: Option<Child>) {
    match child {
        Some(child) if child.id() == pid => kill_child(child),
        other => {
            if let Some(child) = other {
                kill_child(child);
            }
            if os::is_navpath(pid) {
                os::terminate(pid);
                let deadline = Instant::now() + STOP_TIMEOUT;
                while os::is_alive(pid) && Instant::now() < deadline {
                    thread::sleep(Duration::from_millis(50));
                }
            } else if os::is_alive(pid) {
                log::warn!("pid {} is no longer a route planner; leaving it alone", pid);
            }
        }
    }
}

fn kill_child(mut child: Child) {
    let _ = child.kill();
    let _ = child.wait();
}

fn backoff(restarts: u32) -> Duration {
    Duration::from_secs(1u64 << restarts.min(6)).min(MAX_BACKOFF)
}

fn watch_tick() {
    let now = Instant::now();
    let (layout, wanted) = {
        let mut sup = supervisor();
        if sup.stopped {
            return;
        }
        let Some(endpoint) = sup.endpoint.clone() else {
            return;
        };
        let running = match sup.child.as_mut() {
            Some(child) if child.id() == endpoint.pid => matches!(child.try_wait(), Ok(None)),
            _ => os::is_alive(endpoint.pid),
        };
        if running {
            if sup.since.is_some_and(|s| s.elapsed() >= STABLE_RUN) {
                sup.restarts = 0;
            }
            return;
        }
        let retry_at = match sup.retry_at {
            Some(at) => at,
            None => {
                let delay = backoff(sup.restarts);
                log::error!(
                    "The route planner (pid {}) stopped; restarting it in {:?}",
                    endpoint.pid,
                    delay
                );
                if let Some(layout) = sup.layout.as_ref() {
                    log_tail(&layout.service_log());
                }
                sup.retry_at = Some(now + delay);
                now + delay
            }
        };
        if now < retry_at {
            return;
        }
        sup.reap();
        sup.restarts += 1;
        sup.retry_at = Some(now + backoff(sup.restarts));
        let (Some(layout), Some(wanted)) = (sup.layout.clone(), sup.wanted.clone()) else {
            return;
        };
        (layout, wanted)
    };
    if let Err(e) = ensure_running(&layout, &wanted) {
        log::error!("Could not restart the route planner: {:#}", e);
    }
}

fn log_tail(path: &Path) {
    let Ok(text) = fs::read_to_string(path) else {
        return;
    };
    let lines: Vec<&str> = text.lines().collect();
    if lines.is_empty() {
        return;
    }
    let from = lines.len().saturating_sub(LOG_TAIL_LINES);
    log::warn!("Last {} lines of {}:\n{}", lines.len() - from, path.display(), lines[from..].join("\n"));
}

#[cfg(target_os = "linux")]
mod os {
    use std::os::unix::process::CommandExt;
    use std::process::Command;
    use std::thread;
    use std::time::{Duration, Instant};

    pub fn is_alive(pid: u32) -> bool {
        let Ok(pid) = libc::pid_t::try_from(pid) else {
            return false;
        };
        // SAFETY: signal 0 only checks that the process exists and may be signalled.
        pid > 0 && unsafe { libc::kill(pid, 0) } == 0 && !is_zombie(pid)
    }

    fn is_zombie(pid: libc::pid_t) -> bool {
        std::fs::read_to_string(format!("/proc/{pid}/stat"))
            .ok()
            .and_then(|stat| stat.rsplit_once(')').map(|(_, rest)| rest.trim_start().starts_with('Z')))
            .unwrap_or(false)
    }

    pub fn is_navpath(pid: u32) -> bool {
        std::fs::read_link(format!("/proc/{pid}/exe"))
            .ok()
            .and_then(|exe| exe.file_name().map(|n| n.to_string_lossy().starts_with(super::SERVICE)))
            .unwrap_or(false)
    }

    pub fn terminate(pid: u32) {
        let Ok(pid_t) = libc::pid_t::try_from(pid) else {
            return;
        };
        // SAFETY: plain signal delivery to a pid confirmed to be a navpath-service.
        unsafe { libc::kill(pid_t, libc::SIGTERM) };
        let deadline = Instant::now() + Duration::from_secs(3);
        while is_alive(pid) && Instant::now() < deadline {
            thread::sleep(Duration::from_millis(50));
        }
        if is_alive(pid) {
            unsafe { libc::kill(pid_t, libc::SIGKILL) };
        }
    }

    pub fn hide_window(_cmd: &mut Command) {}

    /// Its own process group, so a Ctrl+C in the launcher's terminal does not take down a planner clients use.
    pub fn detach(cmd: &mut Command) {
        cmd.process_group(0);
    }
}

#[cfg(windows)]
mod os {
    use std::os::windows::process::CommandExt;
    use std::process::Command;
    use windows_sys::Win32::Foundation::{CloseHandle, FALSE, HANDLE, STILL_ACTIVE};
    use windows_sys::Win32::System::Threading::{
        GetExitCodeProcess, OpenProcess, QueryFullProcessImageNameW, TerminateProcess, WaitForSingleObject,
        PROCESS_ACCESS_RIGHTS, PROCESS_NAME_WIN32, PROCESS_QUERY_LIMITED_INFORMATION, PROCESS_SYNCHRONIZE,
        PROCESS_TERMINATE,
    };

    const CREATE_NO_WINDOW: u32 = 0x0800_0000;

    struct Handle(HANDLE);

    impl Drop for Handle {
        fn drop(&mut self) {
            unsafe { CloseHandle(self.0) };
        }
    }

    fn open(pid: u32, access: PROCESS_ACCESS_RIGHTS) -> Option<Handle> {
        // SAFETY: OpenProcess has no preconditions; a null handle means it could not be opened.
        let handle = unsafe { OpenProcess(access, FALSE, pid) };
        (!handle.is_null()).then_some(Handle(handle))
    }

    pub fn is_alive(pid: u32) -> bool {
        let Some(handle) = open(pid, PROCESS_QUERY_LIMITED_INFORMATION) else {
            return false;
        };
        let mut code = 0u32;
        let queried = unsafe { GetExitCodeProcess(handle.0, &mut code) } != 0;
        queried && code == STILL_ACTIVE as u32
    }

    pub fn is_navpath(pid: u32) -> bool {
        let Some(handle) = open(pid, PROCESS_QUERY_LIMITED_INFORMATION) else {
            return false;
        };
        let mut buf = [0u16; 1024];
        let mut len = buf.len() as u32;
        if unsafe { QueryFullProcessImageNameW(handle.0, PROCESS_NAME_WIN32, buf.as_mut_ptr(), &mut len) } == 0 {
            return false;
        }
        let path = String::from_utf16_lossy(&buf[..len as usize]);
        path.rsplit(['\\', '/'])
            .next()
            .is_some_and(|name| name.to_ascii_lowercase().starts_with(super::SERVICE))
    }

    pub fn terminate(pid: u32) {
        let Some(handle) = open(pid, PROCESS_TERMINATE | PROCESS_SYNCHRONIZE) else {
            return;
        };
        unsafe {
            TerminateProcess(handle.0, 1);
            WaitForSingleObject(handle.0, 5000);
        }
    }

    pub fn hide_window(cmd: &mut Command) {
        cmd.creation_flags(CREATE_NO_WINDOW);
    }

    pub fn detach(_cmd: &mut Command) {}
}

#[cfg(not(any(target_os = "linux", windows)))]
mod os {
    use std::process::Command;

    pub fn is_alive(_pid: u32) -> bool {
        false
    }

    pub fn is_navpath(_pid: u32) -> bool {
        false
    }

    pub fn terminate(_pid: u32) {}

    pub fn hide_window(_cmd: &mut Command) {}

    pub fn detach(_cmd: &mut Command) {}
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::write::GzEncoder;
    use flate2::Compression;
    use std::cell::Cell;
    use zip::write::SimpleFileOptions;
    use zip::ZipWriter;

    fn temp(label: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("projectx-navpath-test-{}-{}", label, std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn endpoint(pid: u32, map: &str, version: &str) -> Endpoint {
        Endpoint {
            url: "http://127.0.0.1:4567".into(),
            pid,
            map: map.into(),
            version: version.into(),
        }
    }

    #[test]
    fn the_endpoint_file_round_trips_in_the_shape_the_engine_reads() {
        let dir = temp("endpoint");
        let path = dir.join("navpath").join("endpoint.json");
        let written = endpoint(4242, "abc123", "2.0.0");
        write_endpoint(&path, &written).unwrap();

        assert_eq!(
            fs::read_to_string(&path).unwrap(),
            r#"{"url":"http://127.0.0.1:4567","pid":4242,"map":"abc123","version":"2.0.0"}"#
        );
        assert_eq!(read_endpoint(&path), Some(written));

        remove_endpoint_for(&path, 1);
        assert!(path.is_file());
        remove_endpoint_for(&path, 4242);
        assert!(!path.exists());
        assert_eq!(read_endpoint(&path), None);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_cpu_without_avx2_runs_the_baseline_binaries() {
        assert_eq!(binary_name(SERVICE, true), format!("navpath-service{EXE}"));
        assert_eq!(binary_name(SERVICE, false), format!("navpath-service-baseline{EXE}"));
        assert_eq!(binary_name(BUILDER, false), format!("navpath-builder-baseline{EXE}"));
        assert_eq!(archive_members().len(), 4);
    }

    #[test]
    fn a_healthy_service_on_the_same_map_and_version_is_reused() {
        let running = endpoint(10, "MAP", "2.0.0");
        let decided = plan(Some(running.clone()), "map", "2.0.0", |_| true, |_| true);
        assert_eq!(decided, Plan::Reuse(running));
    }

    #[test]
    fn nothing_running_starts_one_and_retires_nothing() {
        assert_eq!(plan(None, "map", "2.0.0", |_| true, |_| true), Plan::Start { retire: None });
        let dead = endpoint(10, "map", "2.0.0");
        let ready_asked = Cell::new(false);
        let decided = plan(Some(dead), "map", "2.0.0", |_| false, |_| {
            ready_asked.set(true);
            true
        });
        assert_eq!(decided, Plan::Start { retire: None });
        assert!(!ready_asked.get());
    }

    #[test]
    fn an_update_or_an_unhealthy_service_starts_a_new_one_and_retires_the_old() {
        let old_map = endpoint(10, "old", "2.0.0");
        assert_eq!(
            plan(Some(old_map.clone()), "new", "2.0.0", |_| true, |_| true),
            Plan::Start { retire: Some(old_map) }
        );
        let old_version = endpoint(10, "map", "1.9.0");
        assert_eq!(
            plan(Some(old_version.clone()), "map", "2.0.0", |_| true, |_| true),
            Plan::Start { retire: Some(old_version) }
        );
        let hung = endpoint(10, "map", "2.0.0");
        assert_eq!(
            plan(Some(hung.clone()), "map", "2.0.0", |_| true, |_| false),
            Plan::Start { retire: Some(hung) }
        );
    }

    #[test]
    fn only_a_loaded_ready_service_counts_as_ready() {
        let head = "HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: 1\r\n\r\n";
        assert!(reports_ready(&format!(
            r#"{head}{{"ready":true,"version":3,"snapshot_hash":"aa","loaded_at":1,"counts":{{"nodes":5}}}}"#
        )));
        assert!(!reports_ready(&format!(r#"{head}{{"ready":false,"counts":{{"nodes":5}}}}"#)));
        assert!(!reports_ready(&format!(r#"{head}{{"ready":true,"counts":null}}"#)));
        assert!(!reports_ready("HTTP/1.1 503 Service Unavailable\r\n\r\n"));
        assert!(!health_ready("http://10.0.0.1:80"));
        assert!(!health_ready("not a url"));
    }

    fn wanted_names() -> Vec<String> {
        vec!["navpath-service".into(), "navpath-builder".into()]
    }

    #[test]
    fn a_zip_archive_yields_only_the_named_binaries() {
        let dir = temp("zip");
        let archive = dir.join("navpath-2.0.0-windows-x86_64.zip");
        let mut zip = ZipWriter::new(File::create(&archive).unwrap());
        let options = SimpleFileOptions::default();
        for (name, body) in [
            ("navpath/navpath-service", "service"),
            ("navpath-builder", "builder"),
            ("../evil", "nope"),
        ] {
            zip.start_file(name, options).unwrap();
            zip.write_all(body.as_bytes()).unwrap();
        }
        zip.finish().unwrap();

        let out = dir.join("out");
        unpack(&archive, &out, &wanted_names()).unwrap();
        assert_eq!(fs::read_to_string(out.join("navpath-service")).unwrap(), "service");
        assert_eq!(fs::read_to_string(out.join("navpath-builder")).unwrap(), "builder");
        assert!(!out.join("evil").exists() && !dir.join("evil").exists());

        let short_list = vec!["navpath-service".to_string(), "navpath-service-baseline".to_string()];
        assert!(unpack(&archive, &dir.join("short"), &short_list).is_err());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_tar_gz_archive_yields_only_the_named_binaries() {
        let dir = temp("tgz");
        let archive = dir.join("navpath-2.0.0-linux-x86_64.tar.gz");
        let mut tar = tar::Builder::new(GzEncoder::new(File::create(&archive).unwrap(), Compression::fast()));
        for (name, body) in [("navpath-service", "service"), ("bin/navpath-builder", "builder"), ("README", "x")] {
            let mut header = tar::Header::new_gnu();
            header.set_size(body.len() as u64);
            header.set_mode(0o644);
            header.set_cksum();
            tar.append_data(&mut header, name, body.as_bytes()).unwrap();
        }
        tar.into_inner().unwrap().finish().unwrap();

        let out = dir.join("out");
        unpack(&archive, &out, &wanted_names()).unwrap();
        assert_eq!(fs::read_to_string(out.join("navpath-builder")).unwrap(), "builder");
        assert!(!out.join("README").exists());
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = fs::metadata(out.join("navpath-service")).unwrap().permissions().mode();
            assert_eq!(mode & 0o111, 0o111);
        }
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_graph_is_rebuilt_when_the_map_or_the_binaries_change() {
        let dir = temp("graph-key");
        let layout = Layout { root: dir.clone(), endpoint: dir.join("endpoint.json") };
        let mut state = Installed {
            map_sha256: "aa".into(),
            archive_sha256: "bb".into(),
            graph: "graph-x".into(),
            ..Installed::default()
        };
        state.graph_key = graph_key(&state);
        fs::create_dir_all(dir.join("graph-x")).unwrap();
        assert!(!graph_present(&layout, &state));
        fs::write(dir.join("graph-x").join(SNAPSHOT), b"g").unwrap();
        assert!(graph_present(&layout, &state));

        state.map_sha256 = "cc".into();
        assert!(!graph_present(&layout, &state));
        state.map_sha256 = "aa".into();
        state.archive_sha256 = "dd".into();
        assert!(!graph_present(&layout, &state));
        let _ = fs::remove_dir_all(&dir);
    }

    fn serve(files: Vec<(&'static str, Vec<u8>)>, requests: usize) -> u16 {
        use std::io::{BufRead, BufReader};

        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        thread::spawn(move || {
            for stream in listener.incoming().take(requests) {
                let Ok(mut stream) = stream else { break };
                let mut reader = BufReader::new(stream.try_clone().unwrap());
                let mut line = String::new();
                let _ = reader.read_line(&mut line);
                let mut header = String::new();
                while reader.read_line(&mut header).map(|n| n > 2).unwrap_or(false) {
                    header.clear();
                }
                let path = line.split_whitespace().nth(1).unwrap_or_default().to_string();
                let body = files.iter().find(|(p, _)| *p == path).map(|(_, b)| b.clone()).unwrap_or_default();
                let head = format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len());
                let _ = stream.write_all(head.as_bytes());
                let _ = stream.write_all(&body);
            }
        });
        port
    }

    fn artifact(port: u16, path: &str, file: &str, platform: &str, bytes: &[u8]) -> ReleaseArtifact {
        ReleaseArtifact {
            file: file.into(),
            version: "2.0.0".into(),
            url: format!("http://127.0.0.1:{port}{path}"),
            sha256: plugins::hex_digest(bytes),
            size: bytes.len() as u64,
            platform: platform.into(),
        }
    }

    #[tokio::test]
    async fn installing_verifies_and_unpacks_then_survives_a_lost_release() {
        let Some(platform) = plugins::platform_tag() else {
            return;
        };
        let dir = temp("install");
        let layout = Layout { root: dir.clone(), endpoint: dir.join("endpoint.json") };

        let mut archive = io::Cursor::new(Vec::new());
        let mut zip = ZipWriter::new(&mut archive);
        for member in archive_members() {
            zip.start_file(member.as_str(), SimpleFileOptions::default()).unwrap();
            zip.write_all(member.as_bytes()).unwrap();
        }
        zip.finish().unwrap();
        let archive = archive.into_inner();
        let map = b"sqlite map bytes".to_vec();
        let port = serve(vec![("/a", archive.clone()), ("/m", map.clone())], 2);

        let catalog = Catalog {
            release_tag: "v3.3.0".into(),
            launcher: Vec::new(),
            engine: None,
            supervisor: None,
            bootstrap: Vec::new(),
            navpath: vec![artifact(port, "/a", "navpath-2.0.0.zip", platform, &archive)],
            navpath_map: Some(artifact(port, "/m", "navpath-map-2.0.0.db", "any", &map)),
            fetched_at: std::time::SystemTime::now(),
        };
        let client = reqwest::Client::new();
        let progress = |_: String| {};
        let mut state = Installed::default();
        install_binaries(&client, Some(&catalog), &layout, &mut state, &progress).await.expect("binaries");
        install_map(&client, Some(&catalog), &layout, &mut state, &progress).await.expect("map");

        assert_eq!(state.version, "2.0.0");
        assert!(binaries_present(&layout, &state));
        assert_eq!(fs::read(dir.join(&state.map)).unwrap(), map);
        assert_eq!(state.map_sha256, plugins::hex_digest(&map));
        assert_eq!(load_state(&layout), state);
        assert!(fs::read_dir(layout.downloads()).unwrap().next().is_none());

        let mut kept = state.clone();
        install_binaries(&client, None, &layout, &mut kept, &progress).await.expect("offline binaries");
        install_map(&client, None, &layout, &mut kept, &progress).await.expect("offline map");
        assert_eq!(kept, state);

        let mut tampered = catalog.clone();
        tampered.navpath[0].sha256 = "00".repeat(32);
        install_binaries(&client, Some(&tampered), &layout, &mut kept, &progress)
            .await
            .expect("a failed update keeps the installed binaries");
        assert_eq!(kept, state);
        let mut nothing = Installed::default();
        let empty = Layout { root: temp("install-empty"), endpoint: dir.join("unused.json") };
        assert!(install_map(&client, None, &empty, &mut nothing, &progress).await.is_err());

        let _ = fs::remove_dir_all(&empty.root);
        let _ = fs::remove_dir_all(&dir);
    }

    /// Runs the real service when `NAVPATH_TEST_BIN` names a folder with the binaries and `NAVPATH_TEST_MAP`
    /// a map db: one service starts, the endpoint is published, a second ensure reuses it, and exit stops it.
    #[test]
    #[ignore]
    fn a_real_service_starts_once_and_is_reused() {
        let (Some(bin), Some(map)) = (std::env::var_os("NAVPATH_TEST_BIN"), std::env::var_os("NAVPATH_TEST_MAP")) else {
            return;
        };
        let bin = PathBuf::from(bin);
        let root = temp("live");
        let layout = Layout { root: root.clone(), endpoint: root.join("home").join("endpoint.json") };
        let avx2 = cpu_has_avx2();
        let builder = bin.join(binary_name(BUILDER, avx2));
        let graph = build_graph(&layout, &builder, Path::new(&map), "live").expect("graph");
        let wanted = Wanted {
            service: bin.join(binary_name(SERVICE, avx2)),
            snapshot: root.join(&graph).join(SNAPSHOT),
            map: "mapsha".into(),
            version: "test".into(),
        };

        let first = ensure_running(&layout, &wanted).expect("first start");
        assert_eq!(read_endpoint(&layout.endpoint), Some(first.clone()));
        assert!(health_ready(&first.url));
        assert!(os::is_navpath(first.pid));

        let second = ensure_running(&layout, &wanted).expect("reuse");
        assert_eq!(second, first);

        supervisor().child = None;
        let adopted = ensure_running(&layout, &wanted).expect("adopt");
        assert_eq!(adopted.pid, first.pid);

        let updated = Wanted { version: "test2".into(), ..wanted.clone() };
        let replaced = ensure_running(&layout, &updated).expect("update");
        assert_ne!(replaced.pid, first.pid);
        assert!(!os::is_alive(first.pid));
        assert_eq!(read_endpoint(&layout.endpoint), Some(replaced.clone()));

        let mut sup = supervisor();
        sup.stopped = true;
        let child = sup.child.take();
        drop(sup);
        stop(replaced.pid, child);
        assert!(!os::is_alive(replaced.pid));
        let _ = fs::remove_dir_all(&root);
    }
}
