fn main() {
    stamp_version();
    embed_helpers();

    #[cfg(windows)]
    embed_resource::compile("assets/projectx.rc", embed_resource::NONE)
        .manifest_optional()
        .expect("failed to embed assets/projectx.rc");
}

/// The patcher and injectors to carry inside the launcher, so a Windows release is one exe. The release
/// pipeline builds them first and points `PROJECTX_EMBED_DIR` at them; a local build without it finds
/// them beside the exe or in the source tree as before.
fn embed_helpers() {
    const HELPERS: [(&str, &str); 3] = [
        ("PROJECTX_EMBED_PATCHER", "projectx_patcher.dll"),
        ("PROJECTX_EMBED_INJECTOR", "projectx_injector.exe"),
        ("PROJECTX_EMBED_ENGINE_INJECTOR", "projectx_engine_injector.exe"),
    ];
    println!("cargo:rustc-check-cfg=cfg(embedded_helpers)");
    println!("cargo:rerun-if-env-changed=PROJECTX_EMBED_DIR");
    let Some(dir) = std::env::var_os("PROJECTX_EMBED_DIR").filter(|dir| !dir.is_empty()) else {
        return;
    };
    let dir = std::path::PathBuf::from(dir);
    for (key, name) in HELPERS {
        let path = dir.join(name);
        assert!(path.is_file(), "PROJECTX_EMBED_DIR has no {}", path.display());
        println!("cargo:rerun-if-changed={}", path.display());
        println!("cargo:rustc-env={}={}", key, path.display());
    }
    println!("cargo:rustc-cfg=embedded_helpers");
}

/// Bake in the version the release pipeline stamps every artifact with, so the
/// launcher can compare itself against the release it reads the plugin catalog
/// from.
///
/// Without `PROJECTX_VERSION` — a local `cargo build` — the crate version stands
/// in, suffixed so it reads as a pre-release. A bare `0.1.0` is a perfectly good
/// release number, and one that every release outranks, which had a developer's
/// own build prompting itself to download the release on every launch.
fn stamp_version() {
    println!("cargo:rerun-if-env-changed=PROJECTX_VERSION");
    let version = std::env::var("PROJECTX_VERSION")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .unwrap_or_else(|| {
            let crate_version =
                std::env::var("CARGO_PKG_VERSION").unwrap_or_else(|_| "0.0.0".to_string());
            format!("{}-dev", crate_version)
        });
    println!("cargo:rustc-env=PROJECTX_LAUNCHER_VERSION={}", version);
}
