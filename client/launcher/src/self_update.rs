//! Replacing the running launcher with a newer published build. The launcher downloads and verifies the
//! build itself: handing an executable link to the browser left the update to whatever the browser, its
//! download settings and SmartScreen made of it, and some cancelled it without saying so.

use anyhow::{anyhow, bail, Context, Result};
use std::fs;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use crate::plugins::{LauncherDownload, ReleaseArtifact};

/// Passed to the replacement, which then waits for this instance to let go of the single-instance lock.
pub const REPLACED_FLAG: &str = "--replaced";

/// How long a replacement waits for the instance it replaced to exit.
pub const REPLACED_LOCK_WAIT: Duration = Duration::from_secs(20);

const PREVIOUS_SUFFIX: &str = ".previous";
const STAGED_SUFFIX: &str = ".update";

/// Whether this platform's build can be installed in place; anything else is left to the download page.
pub fn supported(download: &LauncherDownload) -> bool {
    match std::env::consts::OS {
        "windows" => download.file.ends_with(".exe"),
        "linux" => download.file.ends_with(".tar.gz"),
        _ => false,
    }
}

/// Downloads [download], checks it against the release's checksum and puts it where the running launcher
/// is, returning the executable to start. The running copy keeps working until it exits.
pub async fn install(
    client: &reqwest::Client,
    download: &LauncherDownload,
    progress: &(dyn Fn(String) + Sync),
) -> Result<PathBuf> {
    if download.sha256.trim().is_empty() {
        bail!("{} has no published checksum, so it is not installed automatically", download.file);
    }
    let exe = std::env::current_exe().context("Could not find the running launcher")?;
    let exe = fs::canonicalize(&exe).unwrap_or(exe);
    let dir = exe.parent().ok_or_else(|| anyhow!("{} has no folder", exe.display()))?.to_path_buf();
    let name = file_name(&exe)?;

    let artifact = ReleaseArtifact {
        file: format!("{}{}", name, STAGED_SUFFIX),
        version: String::new(),
        url: download.download_url().to_string(),
        sha256: download.sha256.clone(),
        size: download.size,
        platform: download.platform.clone(),
    };
    let staged = crate::engine::download_to(client, &artifact, &dir, "the launcher update", progress)
        .await
        .with_context(|| format!("Could not download the update into {}", dir.display()))?;

    let installed = if download.file.ends_with(".tar.gz") {
        let result = unpack_beside(&staged, &dir, &name);
        let _ = fs::remove_file(&staged);
        result?
    } else {
        swap_in(&staged, &exe)?;
        exe.clone()
    };
    log::info!("Installed launcher {} over {}", download.file, installed.display());
    Ok(installed)
}

/// Moves the running executable aside and puts [staged] under its name. Windows lets a running program be
/// renamed but not overwritten; the copy moved aside is deleted by the next start.
fn swap_in(staged: &Path, exe: &Path) -> Result<()> {
    let previous = sibling(exe, PREVIOUS_SUFFIX)?;
    let _ = fs::remove_file(&previous);
    fs::rename(exe, &previous).with_context(|| format!("Could not move {} aside", exe.display()))?;
    if let Err(e) = fs::rename(staged, exe) {
        let _ = fs::rename(&previous, exe);
        let _ = fs::remove_file(staged);
        bail!("Could not put the update in place of {}: {}", exe.display(), e);
    }
    Ok(())
}

/// Replaces the launcher and the files packed beside it with the archive's. Linux lets a running program's
/// file be replaced, so each file is written beside its target and renamed over it.
fn unpack_beside(archive: &Path, dir: &Path, exe_name: &str) -> Result<PathBuf> {
    use flate2::read::GzDecoder;

    let file = fs::File::open(archive).with_context(|| format!("Could not open {}", archive.display()))?;
    let mut tar = tar::Archive::new(GzDecoder::new(file));
    let mut launcher = None;
    for entry in tar.entries().context("The update archive is unreadable")? {
        let mut entry = entry.context("The update archive is unreadable")?;
        if !entry.header().entry_type().is_file() {
            continue;
        }
        let path = entry.path()?.into_owned();
        let Some(relative) = inside_top_folder(&path) else { continue };
        let is_launcher = relative.as_os_str() == "project-x-launcher";
        let target = if is_launcher { dir.join(exe_name) } else { dir.join(&relative) };
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent).with_context(|| format!("Could not create {}", parent.display()))?;
        }
        let temp = sibling(&target, STAGED_SUFFIX)?;
        entry.unpack(&temp).with_context(|| format!("Could not write {}", temp.display()))?;
        if is_launcher {
            make_executable(&temp)?;
        }
        fs::rename(&temp, &target).map_err(|e| {
            let _ = fs::remove_file(&temp);
            anyhow!("Could not replace {}: {}", target.display(), e)
        })?;
        if is_launcher {
            launcher = Some(target);
        }
    }
    launcher.ok_or_else(|| anyhow!("The update archive has no launcher in it"))
}

/// The path under the archive's single top folder, or `None` for anything that would land outside it.
fn inside_top_folder(path: &Path) -> Option<PathBuf> {
    use std::path::Component;

    let mut components = path.components();
    components.next()?;
    let rest: PathBuf = components.collect();
    let safe = !rest.as_os_str().is_empty() && rest.components().all(|c| matches!(c, Component::Normal(_)));
    safe.then_some(rest)
}

#[cfg(unix)]
fn make_executable(path: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o755))
        .with_context(|| format!("Could not make {} executable", path.display()))
}

#[cfg(not(unix))]
fn make_executable(_path: &Path) -> Result<()> {
    Ok(())
}

/// Starts [exe] with this launcher's arguments, telling it to wait for this instance to exit.
pub fn relaunch(exe: &Path) -> Result<()> {
    let args = std::env::args().skip(1).filter(|a| a != REPLACED_FLAG);
    std::process::Command::new(exe)
        .args(args)
        .arg(REPLACED_FLAG)
        .spawn()
        .with_context(|| format!("Could not start {}", exe.display()))?;
    Ok(())
}

/// Deletes the copy a previous update moved aside, once nothing runs it.
pub fn sweep_previous() {
    let Ok(exe) = std::env::current_exe() else { return };
    if let Ok(previous) = sibling(&exe, PREVIOUS_SUFFIX) {
        if previous.exists() {
            match fs::remove_file(&previous) {
                Ok(()) => log::info!("Removed the launcher an update replaced"),
                Err(e) => log::warn!("Could not remove {}: {}", previous.display(), e),
            }
        }
    }
}

/// Calls [try_lock] until it succeeds or [wait] runs out; the instance being replaced is still exiting.
pub fn retry_lock(wait: Duration, mut try_lock: impl FnMut() -> bool) -> bool {
    let deadline = Instant::now() + wait;
    loop {
        if try_lock() {
            return true;
        }
        if Instant::now() >= deadline {
            return false;
        }
        std::thread::sleep(Duration::from_millis(200));
    }
}

fn file_name(path: &Path) -> Result<String> {
    path.file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .ok_or_else(|| anyhow!("{} has no file name", path.display()))
}

fn sibling(path: &Path, suffix: &str) -> Result<PathBuf> {
    Ok(path.with_file_name(format!("{}{}", file_name(path)?, suffix)))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch(label: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("projectx-self-update-{}", label));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn archive_paths_stay_inside_the_launcher_folder() {
        assert_eq!(
            inside_top_folder(Path::new("project-x-launcher-linux-x86_64-1.28.0/project-x-launcher")),
            Some(PathBuf::from("project-x-launcher"))
        );
        assert_eq!(
            inside_top_folder(Path::new("pkg/engine/projectx-supervisor.jar")),
            Some(PathBuf::from("engine/projectx-supervisor.jar"))
        );
        assert_eq!(inside_top_folder(Path::new("pkg/../../etc/passwd")), None);
        assert_eq!(inside_top_folder(Path::new("pkg")), None);
    }

    #[test]
    fn a_running_executable_is_swapped_and_kept_aside() {
        let dir = scratch("swap");
        let exe = dir.join("launcher.exe");
        let staged = dir.join("launcher.exe.update");
        fs::write(&exe, b"old").unwrap();
        fs::write(&staged, b"new").unwrap();

        swap_in(&staged, &exe).unwrap();

        assert_eq!(fs::read(&exe).unwrap(), b"new");
        assert_eq!(fs::read(dir.join("launcher.exe.previous")).unwrap(), b"old");
        assert!(!staged.exists());
    }

    #[test]
    fn a_failed_swap_puts_the_running_executable_back() {
        let dir = scratch("failed-swap");
        let exe = dir.join("launcher.exe");
        fs::write(&exe, b"old").unwrap();

        assert!(swap_in(&dir.join("missing.update"), &exe).is_err());
        assert_eq!(fs::read(&exe).unwrap(), b"old");
    }

    #[test]
    fn the_launcher_and_its_files_unpack_beside_it() {
        let dir = scratch("unpack");
        let archive = dir.join("update.tar.gz");
        {
            let gz = flate2::write::GzEncoder::new(fs::File::create(&archive).unwrap(), flate2::Compression::fast());
            let mut builder = tar::Builder::new(gz);
            for (path, body) in [("pkg/project-x-launcher", &b"binary"[..]), ("pkg/engine/supervisor.jar", &b"jar"[..])] {
                let mut header = tar::Header::new_gnu();
                header.set_size(body.len() as u64);
                header.set_mode(0o644);
                header.set_cksum();
                builder.append_data(&mut header, path, body).unwrap();
            }
            builder.into_inner().unwrap().finish().unwrap();
        }
        let install = dir.join("bin");
        fs::create_dir_all(&install).unwrap();
        fs::write(install.join("my-launcher"), b"old").unwrap();

        let launcher = unpack_beside(&archive, &install, "my-launcher").unwrap();

        assert_eq!(launcher, install.join("my-launcher"));
        assert_eq!(fs::read(&launcher).unwrap(), b"binary");
        assert_eq!(fs::read(install.join("engine/supervisor.jar")).unwrap(), b"jar");
    }
}
