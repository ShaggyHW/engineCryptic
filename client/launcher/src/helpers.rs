//! The patcher and injectors ride inside the Windows launcher and are unpacked into its data folder, so a release
//! is a single exe rather than a folder of files.

use std::fs;
use std::path::PathBuf;

#[cfg(embedded_helpers)]
const EMBEDDED: &[(&str, &[u8])] = &[
    ("projectx_patcher.dll", include_bytes!(env!("PROJECTX_EMBED_PATCHER"))),
    ("projectx_injector.exe", include_bytes!(env!("PROJECTX_EMBED_INJECTOR"))),
    ("projectx_engine_injector.exe", include_bytes!(env!("PROJECTX_EMBED_ENGINE_INJECTOR"))),
];

#[cfg(not(embedded_helpers))]
const EMBEDDED: &[(&str, &[u8])] = &[];

/// Where the embedded helpers are unpacked to.
pub fn dir() -> Option<PathBuf> {
    directories::ProjectDirs::from("", "", "project-x-launcher").map(|dirs| dirs.data_dir().join("bin"))
}

/// Writes out each embedded helper whose copy on disk is missing or differs. A helper still loaded by a running
/// client cannot be replaced; it keeps the old copy until the next start.
pub fn unpack() {
    if EMBEDDED.is_empty() {
        return;
    }
    let Some(dir) = dir() else { return };
    if let Err(e) = fs::create_dir_all(&dir) {
        log::warn!("Could not create {}: {}", dir.display(), e);
        return;
    }
    for (name, bytes) in EMBEDDED {
        let path = dir.join(name);
        if fs::read(&path).is_ok_and(|on_disk| on_disk == *bytes) {
            continue;
        }
        let part = dir.join(format!(".{name}.part"));
        match fs::write(&part, bytes).and_then(|_| fs::rename(&part, &path)) {
            Ok(()) => log::info!("Unpacked {} to {}", name, dir.display()),
            Err(e) => {
                let _ = fs::remove_file(&part);
                log::warn!("Could not unpack {} to {}: {}", name, dir.display(), e);
            }
        }
    }
}
