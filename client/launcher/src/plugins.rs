//! The release manifest the launcher installs the engine home from, the scripts folder the engine scans,
//! and the clean-up of the script channels the launcher used to manage.

use anyhow::{bail, Context, Result};
use directories::BaseDirs;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs;
use std::io::ErrorKind;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime};
use url::Url;

use crate::config::PluginsConfig;

const DEFAULT_API_HOST: &str = "https://api.github.com";
const DEFAULT_WEB_HOST: &str = "https://github.com";
/// The engine repository is private, so the engine home artifacts and the manifest are published here.
const DEFAULT_PROJECT_PATH: &str = "iEasyScript/launcher";

const MANIFEST_LINK: &str = "plugins-manifest.json";
const ENGINE_LINK: &str = "projectx-engine.jar";

const CATALOG_TTL: Duration = Duration::from_secs(10 * 60);

/// Script channels the launcher once installed jars for. Their scripts are sold on the store now, so a
/// jar left from one would run a second copy of every script the store also delivers.
const RETIRED_CHANNELS: [&str; 2] = ["official", "community"];

/// One published launcher build, offered when it is newer than the one running.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct LauncherDownload {
    pub platform: String,
    pub file: String,
    pub url: String,
    #[serde(default)]
    pub sha256: String,
    #[serde(default)]
    pub size: u64,
    #[serde(default)]
    pub label: String,
}

/// The engine shadow jar, the supervisor jar, or a platform's native bootstrap. They ride one manifest so
/// the engine jar can never be updated past the bootstrap that has to load it. `platform` is set only for
/// the bootstrap.
#[derive(Debug, Clone, Deserialize)]
pub struct ReleaseArtifact {
    pub file: String,
    #[serde(default)]
    pub version: String,
    pub url: String,
    #[serde(default)]
    pub sha256: String,
    #[serde(default)]
    pub size: u64,
    #[serde(default)]
    pub platform: String,
}

#[derive(Debug, Clone, Default, Deserialize)]
struct ReleaseManifest {
    #[serde(default)]
    version: String,
    #[serde(default)]
    launcher: Vec<LauncherDownload>,
    #[serde(default)]
    engine: Option<ReleaseArtifact>,
    #[serde(default)]
    supervisor: Option<ReleaseArtifact>,
    #[serde(default)]
    bootstrap: Vec<ReleaseArtifact>,
    #[serde(default)]
    navpath: Vec<ReleaseArtifact>,
    #[serde(default, rename = "navpathMap")]
    navpath_map: Option<ReleaseArtifact>,
}

pub fn platform_tag() -> Option<&'static str> {
    match (std::env::consts::OS, std::env::consts::ARCH) {
        ("linux", "x86_64") => Some("linux-x86_64"),
        ("windows", "x86_64") => Some("windows-x86_64"),
        ("macos", _) => Some("macos-universal"),
        _ => None,
    }
}

fn platform_label(platform: &str) -> String {
    match platform {
        "linux-x86_64" => "Linux (x86-64)".to_string(),
        "windows-x86_64" => "Windows (x86-64)".to_string(),
        "macos-universal" => "macOS (Universal)".to_string(),
        other => other.replace('-', " "),
    }
}

#[derive(Debug, Deserialize)]
struct AssetLink {
    name: String,
    #[serde(rename = "browser_download_url")]
    url: String,
}

#[derive(Debug, Deserialize)]
struct Release {
    tag_name: String,
    #[serde(default)]
    assets: Vec<AssetLink>,
}

/// What the newest release offers.
#[derive(Debug, Clone)]
pub struct Catalog {
    pub release_tag: String,
    pub launcher: Vec<LauncherDownload>,
    pub engine: Option<ReleaseArtifact>,
    pub supervisor: Option<ReleaseArtifact>,
    pub bootstrap: Vec<ReleaseArtifact>,
    pub navpath: Vec<ReleaseArtifact>,
    pub navpath_map: Option<ReleaseArtifact>,
    pub fetched_at: SystemTime,
}

impl Catalog {
    pub fn bootstrap_for(&self, platform: &str) -> Option<&ReleaseArtifact> {
        self.bootstrap.iter().find(|a| a.platform == platform)
    }

    pub fn navpath_for(&self, platform: &str) -> Option<&ReleaseArtifact> {
        self.navpath.iter().find(|a| a.platform == platform)
    }

    pub fn is_stale(&self) -> bool {
        self.fetched_at
            .elapsed()
            .map(|age| age > CATALOG_TTL)
            .unwrap_or(true)
    }
}

/// The `major.minor.patch` of a released version, or `None` for anything that is not one.
///
/// A pre-release, which is what a branch pipeline and a local `cargo build` stamp, is deliberately
/// unparseable rather than sorted below every release: a developer's own build is beside the release, not
/// behind it, and a prompt that fires on every launch gets ignored on the one launch it mattered.
fn release_version(version: &str) -> Option<(u32, u32, u32)> {
    let version = version.trim().trim_start_matches('v');
    if version.contains('-') || version.contains('+') {
        return None;
    }
    let mut parts = version.split('.');
    let mut next = || parts.next()?.parse::<u32>().ok();
    let parsed = (next()?, next()?, next()?);
    parts.next().is_none().then_some(parsed)
}

pub fn launcher_update(catalog: &Catalog) -> Option<&LauncherDownload> {
    let current = release_version(crate::VERSION)?;
    let available = release_version(&catalog.release_tag)?;
    if available <= current {
        return None;
    }
    let platform = platform_tag()?;
    catalog.launcher.iter().find(|l| l.platform == platform)
}

fn api_host(cfg: &PluginsConfig) -> String {
    cfg.api_host
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or(DEFAULT_API_HOST)
        .trim_end_matches('/')
        .to_string()
}

fn project_path(cfg: &PluginsConfig) -> String {
    cfg.project_path
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or(DEFAULT_PROJECT_PATH)
        .trim_matches('/')
        .to_string()
}

fn api_project_base(cfg: &PluginsConfig) -> String {
    format!("{}/repos/{}", api_host(cfg), project_path(cfg))
}

async fn get_json<T: DeserializeOwned>(client: &reqwest::Client, url: &str) -> Result<T> {
    let response = client
        .get(url)
        .send()
        .await
        .with_context(|| format!("GET {} failed", url))?;
    let status = response.status();
    if !status.is_success() {
        bail!("GET {} returned {}", url, status);
    }
    response
        .json::<T>()
        .await
        .with_context(|| format!("GET {} returned a body this launcher cannot read", url))
}

/// The GitHub web host to read releases from without the REST API, or `None` when a different API host is
/// configured. Unauthenticated API calls are limited to 60 an hour per IP; release downloads are not.
fn web_host(cfg: &PluginsConfig) -> Option<&'static str> {
    (api_host(cfg) == DEFAULT_API_HOST).then_some(DEFAULT_WEB_HOST)
}

pub async fn fetch_catalog(client: &reqwest::Client, cfg: &PluginsConfig) -> Result<Catalog> {
    if let Some(web) = web_host(cfg) {
        match fetch_catalog_web(client, cfg, web).await {
            Ok(catalog) => return Ok(catalog),
            Err(e) => log::warn!(
                "release manifest download failed ({}); asking the GitHub API instead",
                e
            ),
        }
    }
    fetch_catalog_api(client, cfg).await
}

async fn fetch_catalog_web(client: &reqwest::Client, cfg: &PluginsConfig, web: &str) -> Result<Catalog> {
    let url = format!(
        "{}/{}/releases/latest/download/{}",
        web,
        project_path(cfg),
        MANIFEST_LINK
    );
    let manifest: ReleaseManifest = get_json(client, &url).await?;
    let version = manifest.version.trim().trim_start_matches('v');
    if version.is_empty() {
        bail!("{} carries no version", url);
    }
    let tag = format!("v{}", version);
    Ok(catalog_from_manifest(tag, manifest))
}

fn catalog_from_manifest(release_tag: String, mut manifest: ReleaseManifest) -> Catalog {
    log::info!(
        "Release {} lists {} launcher build(s), {} engine, {} supervisor, {} bootstrap(s), {} route planner build(s) and {} map",
        release_tag,
        manifest.launcher.len(),
        if manifest.engine.is_some() { "an" } else { "no" },
        if manifest.supervisor.is_some() { "a" } else { "no" },
        manifest.bootstrap.len(),
        manifest.navpath.len(),
        if manifest.navpath_map.is_some() { "a" } else { "no" }
    );
    for download in &mut manifest.launcher {
        download.label = platform_label(&download.platform);
    }
    Catalog {
        release_tag,
        launcher: manifest.launcher,
        engine: manifest.engine,
        supervisor: manifest.supervisor,
        bootstrap: manifest.bootstrap,
        navpath: manifest.navpath,
        navpath_map: manifest.navpath_map,
        fetched_at: SystemTime::now(),
    }
}

async fn fetch_catalog_api(client: &reqwest::Client, cfg: &PluginsConfig) -> Result<Catalog> {
    let base = api_project_base(cfg);

    let release: Release = match get_json(client, &format!("{}/releases/latest", base)).await {
        Ok(release) => release,
        Err(e) => {
            // `releases/latest` 404s when every release is a draft or a prerelease; the list still has them.
            log::debug!("latest release unavailable ({}); falling back to the list", e);
            let mut releases: Vec<Release> =
                get_json(client, &format!("{}/releases?per_page=1", base)).await?;
            if releases.is_empty() {
                bail!("no releases published yet");
            }
            releases.remove(0)
        }
    };

    let manifest_url = release
        .assets
        .iter()
        .find(|l| l.name == MANIFEST_LINK)
        .map(|l| l.url.clone());

    let manifest = match manifest_url {
        Some(url) => get_json::<ReleaseManifest>(client, &url).await?,
        // Without a manifest nothing names a checksum, and the bootstrap and supervisor install under fixed
        // names, so they are left to whatever shipped beside the launcher.
        None => ReleaseManifest {
            launcher: launcher_from_links(&release),
            engine: engine_from_links(&release),
            ..ReleaseManifest::default()
        },
    };

    Ok(catalog_from_manifest(release.tag_name, manifest))
}

fn launcher_from_links(release: &Release) -> Vec<LauncherDownload> {
    release
        .assets
        .iter()
        .filter_map(|link| {
            let platform = link.name.strip_prefix("project-x-launcher-")?;
            let platform = [".tar.gz", ".zip", ".exe"]
                .iter()
                .find_map(|ext| platform.strip_suffix(ext))
                .unwrap_or(platform);
            Some(LauncherDownload {
                platform: platform.to_string(),
                file: link.name.clone(),
                url: link.url.clone(),
                sha256: String::new(),
                size: 0,
                label: String::new(),
            })
        })
        .collect()
}

fn engine_from_links(release: &Release) -> Option<ReleaseArtifact> {
    let link = release.assets.iter().find(|l| l.name == ENGINE_LINK)?;
    let version = release.tag_name.trim_start_matches('v').to_string();
    Some(ReleaseArtifact {
        file: format!("projectx-engine-{}.jar", version),
        version,
        url: link.url.clone(),
        sha256: String::new(),
        size: 0,
        platform: String::new(),
    })
}

/// A jar the launcher put in the scripts directory for one of the retired channels.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InstalledPlugin {
    pub id: String,
    pub version: String,
    pub file: String,
    #[serde(default)]
    pub sha256: String,
    #[serde(default)]
    pub installed_at: u64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct InstalledPlugins {
    #[serde(default)]
    pub entries: Vec<InstalledPlugin>,
}

/// Where the injected engine scans for script jars.
///
/// The engine reads `user.home`, which the JVM resolves from the account's home directory, not from the
/// `HOME` the launcher redirects for the client process, so this must be the real home.
pub fn scripts_dir() -> Result<PathBuf> {
    let dir = home_dir()?.join(".projectx").join("scripts");
    fs::create_dir_all(&dir)
        .with_context(|| format!("Failed to create {}", dir.display()))?;
    Ok(dir)
}

/// Shared with the store token, so a test redirecting one never touches the developer's real sign-in.
pub(crate) fn home_dir() -> Result<PathBuf> {
    #[cfg(test)]
    if let Some(home) = tests::HOME_OVERRIDE.lock().unwrap_or_else(|e| e.into_inner()).clone() {
        fs::create_dir_all(&home)?;
        return Ok(home);
    }
    BaseDirs::new()
        .map(|dirs| dirs.home_dir().to_path_buf())
        .context("Could not determine the home directory")
}

pub fn state_file(config_dir: &Path) -> PathBuf {
    config_dir.join("plugins.json")
}

pub fn load_state(config_dir: &Path) -> InstalledPlugins {
    match fs::read_to_string(state_file(config_dir)) {
        Ok(contents) => serde_json::from_str(&contents).unwrap_or_else(|e| {
            log::warn!("Ignoring unreadable plugins.json: {}", e);
            InstalledPlugins::default()
        }),
        Err(_) => InstalledPlugins::default(),
    }
}

fn save_state(config_dir: &Path, state: &InstalledPlugins) -> Result<()> {
    let json = serde_json::to_string_pretty(state)?;
    fs::write(state_file(config_dir), json)
        .with_context(|| format!("Failed to write {}", state_file(config_dir).display()))
}

/// Deletes the jars this launcher recorded installing for a retired channel and forgets them, one outcome
/// per jar. A jar someone built themselves is never touched. A jar the running engine still has open stays
/// recorded, so it goes on the next start.
pub fn retire_script_channels(config_dir: &Path) -> Vec<Result<String>> {
    let mut state = load_state(config_dir);
    let retired: Vec<InstalledPlugin> = state
        .entries
        .iter()
        .filter(|e| RETIRED_CHANNELS.contains(&e.id.as_str()))
        .cloned()
        .collect();
    if retired.is_empty() {
        return Vec::new();
    }
    let dir = match scripts_dir() {
        Ok(dir) => dir,
        Err(e) => return vec![Err(e)],
    };

    let mut removed = Vec::new();
    let mut outcomes: Vec<Result<String>> = retired
        .into_iter()
        .map(|entry| {
            let path = dir.join(&entry.file);
            match fs::remove_file(&path) {
                Ok(()) => {}
                Err(e) if e.kind() == ErrorKind::NotFound => {}
                Err(e) => bail!("Failed to remove {}: {}", path.display(), e),
            }
            removed.push(entry.id);
            Ok(entry.file)
        })
        .collect();

    state.entries.retain(|e| !removed.contains(&e.id));
    if let Err(e) = save_state(config_dir, &state) {
        outcomes.push(Err(e));
    }
    outcomes
}

/// The launcher's own version and, when the release is newer, the build to download. The launcher cannot
/// replace itself while it runs, so this is a prompt, not an install.
#[derive(Debug, Clone, Serialize)]
pub struct LauncherStatus {
    pub launcher_version: String,
    pub release_tag: Option<String>,
    pub launcher_update: Option<LauncherDownload>,
}

pub fn launcher_status(catalog: Option<&Catalog>) -> LauncherStatus {
    LauncherStatus {
        launcher_version: crate::VERSION.to_string(),
        release_tag: catalog.map(|c| c.release_tag.clone()),
        launcher_update: catalog.and_then(launcher_update).cloned(),
    }
}

pub fn hex_digest(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    hasher
        .finalize()
        .iter()
        .map(|b| format!("{:02x}", b))
        .collect()
}

/// Hand a web link to the desktop browser.
///
/// The scheme is checked rather than trusted: the URLs come from downloaded data, and `file:`/`javascript:`
/// reaching a system opener would turn it into local code execution.
pub fn open_url(url: &str) -> Result<()> {
    let parsed = Url::parse(url).with_context(|| format!("Not a URL: {}", url))?;
    if !matches!(parsed.scheme(), "http" | "https") {
        bail!("Refusing to open a {} link", parsed.scheme());
    }
    open_with_desktop(parsed.as_str())
}

/// Windows goes through the shell's own "open" verb rather than `explorer.exe`: Explorer mis-parses a URL
/// with a query string and opens a File Explorer window instead of the browser.
#[cfg(target_os = "windows")]
fn open_with_desktop(target: &str) -> Result<()> {
    use windows_sys::Win32::UI::Shell::ShellExecuteW;
    use windows_sys::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL;

    fn wide(s: &str) -> Vec<u16> {
        s.encode_utf16().chain(std::iter::once(0)).collect()
    }
    let verb = wide("open");
    let file = wide(target);
    // SAFETY: both strings are NUL-terminated UTF-16 buffers that outlive the call; the
    // window handle, parameters and directory are optional and passed as null.
    let result = unsafe {
        ShellExecuteW(
            std::ptr::null_mut(),
            verb.as_ptr(),
            file.as_ptr(),
            std::ptr::null(),
            std::ptr::null(),
            SW_SHOWNORMAL,
        )
    };
    // ShellExecuteW reports success as a value greater than 32.
    if (result as isize) <= 32 {
        bail!("Windows could not open {} (error {})", target, result as isize);
    }
    Ok(())
}

#[cfg(target_os = "macos")]
fn open_with_desktop(target: &str) -> Result<()> {
    run_opener(&["open"], target)
}

/// `gio open` is the fallback for minimal installs that ship GLib but not xdg-utils.
#[cfg(not(any(target_os = "macos", target_os = "windows")))]
fn open_with_desktop(target: &str) -> Result<()> {
    run_opener(&["xdg-open"], target).or_else(|first| {
        run_opener(&["gio", "open"], target).map_err(|_| first)
    })
}

/// Waits for the opener on a background thread so it is reaped rather than left as a zombie.
#[cfg(not(target_os = "windows"))]
fn run_opener(command: &[&str], target: &str) -> Result<()> {
    use std::process::{Command, Stdio};

    let mut child = Command::new(command[0])
        .args(&command[1..])
        .arg(target)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .with_context(|| format!("Failed to run {} {}", command.join(" "), target))?;
    std::thread::spawn(move || {
        let _ = child.wait();
    });
    Ok(())
}

#[cfg(test)]
// The home-redirect guard is held across awaits on purpose: it stops two tests racing on the home directory.
#[allow(clippy::await_holding_lock)]
pub(crate) mod tests {
    use super::*;
    use std::io::{BufRead, BufReader, Write};
    use std::net::{TcpListener, TcpStream};
    use std::sync::atomic::{AtomicU16, Ordering};
    use std::sync::{Mutex, MutexGuard};

    /// Serves the release lookup and its manifest, then stops.
    struct FakeForge {
        port: u16,
    }

    impl FakeForge {
        fn start(manifest: &str, requests: u16) -> Self {
            let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
            let port = listener.local_addr().unwrap().port();
            let manifest = manifest.to_string();
            let served = AtomicU16::new(0);

            std::thread::spawn(move || {
                for stream in listener.incoming() {
                    let Ok(stream) = stream else { break };
                    Self::respond(stream, port, &manifest);
                    if served.fetch_add(1, Ordering::SeqCst) + 1 >= requests {
                        break;
                    }
                }
            });

            FakeForge { port }
        }

        fn respond(mut stream: TcpStream, port: u16, manifest: &str) {
            let mut reader = BufReader::new(stream.try_clone().unwrap());
            let mut request_line = String::new();
            if reader.read_line(&mut request_line).is_err() {
                return;
            }
            let mut header = String::new();
            while reader.read_line(&mut header).map(|n| n > 2).unwrap_or(false) {
                header.clear();
            }

            let path = request_line.split_whitespace().nth(1).unwrap_or("").to_string();
            let body = if path.contains("/releases/latest") {
                format!(
                    r#"{{"tag_name":"v1.2.3","assets":[
                        {{"name":"plugins-manifest.json","browser_download_url":"http://127.0.0.1:{port}/manifest"}}
                    ]}}"#
                )
            } else {
                manifest.to_string()
            };

            let head = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                body.len()
            );
            let _ = stream.write_all(head.as_bytes());
            let _ = stream.write_all(body.as_bytes());
            let _ = stream.flush();
        }

        fn config(&self) -> PluginsConfig {
            PluginsConfig {
                api_host: Some(format!("http://127.0.0.1:{}", self.port)),
                project_path: Some("acme/engine".to_string()),
            }
        }
    }

    /// `scripts_dir` resolves against the process-wide home directory, so a test
    /// that redirects it must not overlap another one doing the same.
    pub(crate) static HOME_REDIRECT: Mutex<()> = Mutex::new(());

    /// Where `scripts_dir` resolves while a test holds [`HOME_REDIRECT`]. Setting `HOME` alone
    /// is not enough: on Windows the home directory comes from the user profile, so tests
    /// would install into, and delete from, the developer's real scripts folder.
    pub(crate) static HOME_OVERRIDE: Mutex<Option<PathBuf>> = Mutex::new(None);

    fn temp_home(label: &str) -> (MutexGuard<'static, ()>, PathBuf, PathBuf) {
        let guard = HOME_REDIRECT.lock().unwrap_or_else(|e| e.into_inner());
        let home = std::env::temp_dir().join(format!("projectx-plugins-test-{}", label));
        let _ = fs::remove_dir_all(&home);
        let config_dir = home.join("config");
        fs::create_dir_all(&config_dir).unwrap();
        std::env::set_var("HOME", &home);
        *HOME_OVERRIDE.lock().unwrap_or_else(|e| e.into_inner()) = Some(home.clone());
        (guard, home, config_dir)
    }

    #[tokio::test]
    async fn the_engine_catalog_is_read_from_the_release_manifest() {
        let server = FakeForge::start(
            r#"{"schema":1,"version":"1.2.3",
                "plugins":[{"id":"community","name":"Community Scripts","version":"1.2.3",
                            "file":"community-scripts-1.2.3.jar","url":"https://example/c"}],
                "engine":{"file":"projectx-engine-1.2.3.jar","version":"1.2.3",
                          "url":"https://example/e","sha256":"aa","size":1}}"#,
            2,
        );

        let catalog = fetch_catalog(&reqwest::Client::new(), &server.config())
            .await
            .expect("catalog");
        assert_eq!(catalog.release_tag, "v1.2.3");
        assert_eq!(catalog.engine.expect("engine").file, "projectx-engine-1.2.3.jar");
    }

    #[test]
    fn retiring_the_script_channels_deletes_only_the_jars_the_launcher_installed() {
        let (_home_guard, _home, config_dir) = temp_home("retire-channels");
        let scripts = scripts_dir().unwrap();
        fs::write(scripts.join("official-scripts-1.12.1.jar"), b"installed").unwrap();
        fs::write(scripts.join("community-scripts-1.5.0.jar"), b"installed").unwrap();
        fs::write(scripts.join("community-scripts-1.0.0.jar"), b"built by hand").unwrap();
        fs::write(scripts.join("GatesOfElidinis.jar"), b"from the store").unwrap();
        fs::write(
            state_file(&config_dir),
            r#"{"entries":[
                {"id":"official","version":"1.12.1","file":"official-scripts-1.12.1.jar"},
                {"id":"community","version":"1.5.0","file":"community-scripts-1.5.0.jar"}
            ]}"#,
        )
        .unwrap();

        let retired: Vec<String> = retire_script_channels(&config_dir)
            .into_iter()
            .map(|outcome| outcome.expect("retire"))
            .collect();
        assert_eq!(retired, vec!["official-scripts-1.12.1.jar", "community-scripts-1.5.0.jar"]);
        assert!(!scripts.join("official-scripts-1.12.1.jar").exists());
        assert!(!scripts.join("community-scripts-1.5.0.jar").exists());
        assert!(scripts.join("community-scripts-1.0.0.jar").is_file());
        assert!(scripts.join("GatesOfElidinis.jar").is_file());
        assert!(load_state(&config_dir).entries.is_empty());

        assert!(retire_script_channels(&config_dir).is_empty());
    }

    #[test]
    fn a_community_jar_already_gone_is_still_forgotten() {
        let (_home_guard, _home, config_dir) = temp_home("retire-missing");
        fs::write(
            state_file(&config_dir),
            r#"{"entries":[{"id":"community","version":"1.5.0","file":"community-scripts-1.5.0.jar"}]}"#,
        )
        .unwrap();

        let retired: Vec<String> = retire_script_channels(&config_dir)
            .into_iter()
            .map(|outcome| outcome.expect("retire"))
            .collect();
        assert_eq!(retired, vec!["community-scripts-1.5.0.jar"]);
        assert!(load_state(&config_dir).entries.is_empty());
    }

    /// The engine home's three artifacts move as one release, so the manifest has to carry all three: a
    /// bootstrap the launcher cannot see is a bootstrap it can never update past.
    #[test]
    fn a_manifest_carries_the_whole_engine_home_not_just_the_jar() {
        let manifest: ReleaseManifest = serde_json::from_str(
            r#"{"schema":1,"version":"3.2.0","plugins":[],"launcher":[],
                "engine":{"file":"projectx-engine-3.2.0.jar","version":"3.2.0",
                          "url":"https://example/e","sha256":"aa","size":1},
                "supervisor":{"file":"projectx-supervisor-3.2.0.jar","version":"3.2.0",
                              "url":"https://example/s","sha256":"bb","size":2},
                "bootstrap":[
                  {"platform":"linux-x86_64","file":"libprojectxbootstrap-3.2.0.so",
                   "version":"3.2.0","url":"https://example/l","sha256":"cc","size":3},
                  {"platform":"windows-x86_64","file":"projectxbootstrap-3.2.0.dll",
                   "version":"3.2.0","url":"https://example/w","sha256":"dd","size":4}]}"#,
        )
        .expect("manifest");

        let catalog = catalog_from_manifest("v3.2.0".into(), manifest);

        assert_eq!(catalog.supervisor.as_ref().expect("supervisor").sha256, "bb");
        assert_eq!(catalog.bootstrap_for("linux-x86_64").expect("linux").sha256, "cc");
        assert_eq!(catalog.bootstrap_for("windows-x86_64").expect("windows").sha256, "dd");
        assert!(catalog.bootstrap_for("macos-universal").is_none());
    }

    #[test]
    fn only_the_default_api_host_reads_releases_from_the_web() {
        let mut cfg = PluginsConfig::default();
        assert_eq!(web_host(&cfg), Some(DEFAULT_WEB_HOST));
        cfg.api_host = Some("http://127.0.0.1:9999".to_string());
        assert_eq!(web_host(&cfg), None);
    }

    #[test]
    fn only_a_newer_release_build_counts_as_an_update() {
        assert_eq!(release_version("3.2.0"), Some((3, 2, 0)));
        assert_eq!(release_version("v3.2.0"), Some((3, 2, 0)));
        assert_eq!(release_version(" 3.2.0 "), Some((3, 2, 0)));
        assert!(release_version("3.2").is_none());
        assert!(release_version("3.2.0.1").is_none());
        assert!(release_version("not-a-version").is_none());

        assert!(release_version("0.0.0-dev.12.abc1234").is_none());
        assert!(release_version(concat!(env!("CARGO_PKG_VERSION"), "-dev")).is_none());

        assert!(release_version("3.2.0") > release_version("3.1.9"));
        assert!(release_version("3.10.0") > release_version("3.9.0"));
        assert!(release_version("4.0.0") > release_version("3.99.99"));
    }

    #[test]
    fn a_manifest_without_the_engine_home_fields_still_parses() {
        let manifest: ReleaseManifest =
            serde_json::from_str(r#"{"schema":1,"version":"3.1.0","plugins":[]}"#)
                .expect("manifest");
        assert!(manifest.supervisor.is_none());
        assert!(manifest.bootstrap.is_empty());
        assert!(manifest.navpath.is_empty());
        assert!(manifest.navpath_map.is_none());
    }

    #[test]
    fn a_manifest_names_the_route_planner_per_platform_and_one_map() {
        let manifest: ReleaseManifest = serde_json::from_str(
            r#"{"version":"3.3.0",
                "navpath":[
                  {"platform":"linux-x86_64","file":"navpath-2.0.0-linux-x86_64.tar.gz",
                   "version":"2.0.0","url":"https://example/nl","sha256":"ee","size":5},
                  {"platform":"windows-x86_64","file":"navpath-2.0.0-windows-x86_64.zip",
                   "version":"2.0.0","url":"https://example/nw","sha256":"ff","size":6}],
                "navpathMap":{"platform":"any","file":"navpath-map-2.0.0.db","version":"2.0.0",
                              "url":"https://example/m","sha256":"0a","size":7}}"#,
        )
        .expect("manifest");

        let catalog = catalog_from_manifest("v3.3.0".into(), manifest);

        assert_eq!(catalog.navpath_for("linux-x86_64").expect("linux").file, "navpath-2.0.0-linux-x86_64.tar.gz");
        assert_eq!(catalog.navpath_for("windows-x86_64").expect("windows").sha256, "ff");
        assert!(catalog.navpath_for("macos-universal").is_none());
        let map = catalog.navpath_map.expect("map");
        assert_eq!((map.file.as_str(), map.platform.as_str()), ("navpath-map-2.0.0.db", "any"));
    }
}
